"""Lease-bound account selection. Credentials stay in the Worker's environment."""

import os

from bunbird.claude_agent import ClaudeAgent
from bunbird.control import async_client
from bunbird.model import Model, ModelConfig, ModelError

ACCOUNT_ERRORS = {"ACCOUNT_AUTH", "ACCOUNT_QUOTA", "ACCOUNT_RATE_LIMIT"}


class PoolWaiting(Exception):
    """The control service has retained this task for a later quota check."""


class AccountExecutor:
    execution_timeout = 870

    def __init__(self, control, config, factory=None):
        self.control = control
        self.config = config
        self.factory = factory or self.build

    @staticmethod
    def build(profile):
        key = os.getenv(profile["keyEnv"], "")
        if not key and profile["mode"] != "mock":
            raise ModelError("ACCOUNT_AUTH")
        config = ModelConfig(profile["mode"], profile["model"], profile["baseUrl"].rstrip("/"), key)
        return ClaudeAgent(config) if profile["executor"] == "claude" else Model(config)

    async def answer_with_progress(self, job, progress):
        attempted = []
        failed = reason = None
        revision = 0

        async def forward(phase, detail):
            nonlocal revision
            # Each CLI attempt starts its own counter; delivery IDs must not collide.
            if phase.startswith("UPDATE_"):
                if revision >= 999:
                    return
                revision += 1
                phase = f"UPDATE_{revision}"
            await progress(phase, detail)

        async with async_client(self.control) as http:
            for _ in range(21):
                response = await http.post(
                    f"/internal/worker/tasks/{job['taskId']}/account",
                    json={
                        "leaseToken": job["leaseToken"],
                        "excluded": attempted,
                        "failedAccount": failed,
                        "errorCode": reason,
                    },
                    timeout=200,
                )
                response.raise_for_status()
                profile = response.json()
                if profile.get("waiting"):
                    raise PoolWaiting()
                ident = profile["id"]
                if ident in attempted:
                    raise ModelError("ACCOUNT_SELECTION_INVALID")
                attempted.append(ident)
                revision = max(revision, profile.get("progressOffset", 0))
                try:
                    executor = self.factory(profile)
                    if hasattr(executor, "answer_with_progress"):
                        current = {
                            **job,
                            "accountRetry": bool(failed) or job.get("accountRetry", False),
                        }
                        return await executor.answer_with_progress(current, forward)
                    from bunbird.worker import plain_answer

                    return await plain_answer(executor, job, forward)
                except ModelError as error:
                    if error.code not in ACCOUNT_ERRORS:
                        raise
                    failed, reason = ident, error.code
                    await forward(
                        "UPDATE_0", "当前账号暂不可用，正在检查备用账号并保留当前会话进度。"
                    )
        raise ModelError("ACCOUNT_SELECTION_INVALID")
