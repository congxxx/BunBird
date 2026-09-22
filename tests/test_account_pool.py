import asyncio
import json
from types import SimpleNamespace

import httpx
import pytest

from bunbird.account_pool import AccountExecutor, PoolWaiting
from bunbird.model import Model, ModelConfig, ModelError


@pytest.mark.parametrize(
    "code,expected", [(401, "ACCOUNT_AUTH"), (402, "ACCOUNT_QUOTA"), (429, "ACCOUNT_RATE_LIMIT")]
)
def test_http_account_failures_are_not_retried_on_same_key(code, expected):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(code, text="provider secret")

    model = Model(
        ModelConfig("compatible", "model", "https://test", "key"), httpx.MockTransport(handler)
    )
    with pytest.raises(ModelError, match=expected):
        asyncio.run(model.answer({"prompt": "hello"}))
    assert len(calls) == 1


@pytest.mark.parametrize(
    "code,expected",
    [
        (1002, "ACCOUNT_RATE_LIMIT"),
        (1004, "ACCOUNT_AUTH"),
        (1008, "ACCOUNT_QUOTA"),
        (2056, "ACCOUNT_QUOTA"),
    ],
)
def test_minimax_business_errors(code, expected):
    model = Model(
        ModelConfig("anthropic", "model", "https://test", "key"),
        httpx.MockTransport(
            lambda _: httpx.Response(
                200, json={"base_resp": {"status_code": code, "status_msg": "secret"}}
            )
        ),
    )
    with pytest.raises(ModelError, match=expected):
        asyncio.run(model.answer({"prompt": "hello"}))


@pytest.mark.parametrize("waiting", [False, True])
def test_switch_preserves_job_and_uses_unique_progress(monkeypatch, waiting):
    requests = []
    jobs = []
    notices = []
    stopped = []

    def handler(request):
        body = json.loads(request.content)
        requests.append(body)
        if len(requests) == 2:
            assert stopped == ["first"]
            assert body["failedAccount"] == "first" and body["excluded"] == ["first"]
        if len(requests) == 2 and waiting:
            return httpx.Response(200, json={"waiting": True})
        return httpx.Response(
            200, json={"id": "first" if len(requests) == 1 else "second", "progressOffset": 8}
        )

    monkeypatch.setattr(
        "bunbird.account_pool.async_client",
        lambda _: httpx.AsyncClient(
            base_url="http://control", transport=httpx.MockTransport(handler)
        ),
    )

    class Agent:
        def __init__(self, profile):
            self.profile = profile

        async def answer_with_progress(self, job, progress):
            jobs.append(job)
            try:
                await progress("UPDATE_0", "public summary")
                if self.profile["id"] == "first":
                    raise ModelError("ACCOUNT_QUOTA")
                return {"result": "done", "model": "test"}
            finally:
                stopped.append(self.profile["id"])

    async def progress(phase, detail):
        notices.append(phase)

    job = {
        "taskId": "task",
        "leaseToken": "lease",
        "tenantId": "tenant",
        "topicId": "topic",
        "senderId": "sender",
        "prompt": "work",
        "attachments": [],
    }
    executor = AccountExecutor(None, SimpleNamespace(name="test"), Agent)
    if waiting:
        with pytest.raises(PoolWaiting):
            asyncio.run(executor.answer_with_progress(job, progress))
    else:
        assert asyncio.run(executor.answer_with_progress(job, progress))["result"] == "done"
        assert jobs[1] == {**job, "accountRetry": True}
    assert len(notices) == len(set(notices)) and notices[0] == "UPDATE_9"


def test_normal_failure_does_not_switch(monkeypatch):
    count = []

    def handler(request):
        count.append(1)
        return httpx.Response(200, json={"id": "one"})

    monkeypatch.setattr(
        "bunbird.account_pool.async_client",
        lambda _: httpx.AsyncClient(
            base_url="http://control", transport=httpx.MockTransport(handler)
        ),
    )

    def factory(_):
        raise ModelError("CLAUDE_PERMISSION_DENIED")

    async def progress(*_):
        pass

    with pytest.raises(ModelError, match="CLAUDE_PERMISSION_DENIED"):
        asyncio.run(
            AccountExecutor(None, None, factory).answer_with_progress(
                {"taskId": "t", "leaseToken": "l"}, progress
            )
        )
    assert len(count) == 1
