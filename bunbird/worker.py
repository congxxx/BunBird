import asyncio
import contextlib
import logging

import httpx

from bunbird.config import ControlConfig
from bunbird.control import async_client
from bunbird.model import Model, ModelError

log = logging.getLogger(__name__)


async def execute(http: httpx.AsyncClient, model: Model, job: dict):
    task_id, token = job["taskId"], job["leaseToken"]
    root = f"/internal/worker/tasks/{task_id}"

    async def monitor():
        while True:
            await asyncio.sleep(3)
            try:
                response = await http.post(root + "/heartbeat", json={"leaseToken": token})
                response.raise_for_status()
                if not response.json()["active"]:
                    return
            except (httpx.HTTPError, ValueError, KeyError):
                # Fail closed on a lost control connection; the database expires the lease.
                return

    answer = asyncio.create_task(model.answer(job))
    heartbeat = asyncio.create_task(monitor())
    try:
        done, _ = await asyncio.wait(
            {answer, heartbeat}, timeout=150, return_when=asyncio.FIRST_COMPLETED
        )
        if heartbeat in done:
            log.info("任务 %s 已停止：取消或租约不可用", task_id)
            return
        if answer not in done:
            result = {"status": "FAILED", "errorCode": "MODEL_TIMEOUT"}
        else:
            try:
                result = {"status": "SUCCEEDED", **answer.result()}
            except ModelError as error:
                result = {"status": "FAILED", "errorCode": error.code}
            except Exception as error:
                log.error("任务 %s 执行异常，类型=%s", task_id, type(error).__name__)
                result = {"status": "FAILED", "errorCode": "WORKER_ERROR"}
        result["leaseToken"] = token
        for attempt in range(3):
            try:
                response = await http.post(root + "/complete", json=result)
                if response.status_code == 409:
                    return
                response.raise_for_status()
                log.info("任务 %s %s", task_id, result["status"])
                return
            except httpx.HTTPError:
                if attempt == 2:
                    log.error("任务 %s 结果回传失败；等待服务端租约清理", task_id)
                    return
                await asyncio.sleep(1)
    finally:
        for task in (answer, heartbeat):
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task


async def run(config: ControlConfig, model: Model):
    log.info("Worker 已启动，模型=%s，模式=%s", model.config.name, model.config.mode)
    async with async_client(config) as http:
        while True:
            try:
                response = await http.post("/internal/worker/claim")
                response.raise_for_status()
                job = response.json()
                if job:
                    await execute(http, model, job)
                    continue
            except (httpx.HTTPError, ValueError) as error:
                log.warning("暂无法领取任务，类型=%s", type(error).__name__)
            await asyncio.sleep(1)
