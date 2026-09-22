import asyncio
import json

import httpx
import pytest

from bunbird.model import Model, ModelConfig, ModelError, messages
from bunbird.worker import execute


def job():
    return {"taskId": "task", "leaseToken": "lease", "prompt": "问题", "history": []}


def test_model_request_and_usage_contract():
    def handler(request):
        assert str(request.url) == "https://model.test/v1/chat/completions"
        assert request.headers["Authorization"] == "Bearer test-key"
        body = json.loads(request.content)
        assert body["messages"][-1] == {"role": "user", "content": "问题"}
        assert body["stream"] is False
        return httpx.Response(
            200, json={"choices": [{"message": {"content": "回答"}}], "usage": {"total_tokens": 20}}
        )

    model = Model(
        ModelConfig("compatible", "test-model", "https://model.test/v1", "test-key"),
        httpx.MockTransport(handler),
    )
    answer = asyncio.run(model.answer(job()))
    assert answer["result"] == "回答"
    assert json.loads(answer["usageJson"])["total_tokens"] == 20


def test_anthropic_request_and_text_only_response():
    def handler(request):
        assert str(request.url) == "https://model.test/anthropic/v1/messages"
        assert request.headers["x-api-key"] == "test-key"
        body = json.loads(request.content)
        assert isinstance(body["system"], str)
        assert body["messages"] == [{"role": "user", "content": "问题"}]
        return httpx.Response(
            200,
            json={
                "type": "message",
                "stop_reason": "end_turn",
                "content": [
                    {"type": "thinking", "thinking": "private reasoning"},
                    {"type": "text", "text": "回答"},
                ],
                "usage": {"input_tokens": 20, "output_tokens": 10},
            },
        )

    model = Model(
        ModelConfig("anthropic", "MiniMax-M3", "https://model.test/anthropic", "test-key"),
        httpx.MockTransport(handler),
    )
    answer = asyncio.run(model.answer(job()))
    assert answer["result"] == "回答"
    assert "private reasoning" not in json.dumps(answer)


@pytest.mark.parametrize(
    "body",
    [
        {"content": [{"type": "thinking", "thinking": "internal"}], "stop_reason": "end_turn"},
        {"content": [{"type": "text", "text": "partial"}], "stop_reason": "max_tokens"},
        {"content": [{"type": "tool_use", "name": "shell"}]},
        {"type": "error", "error": {"message": "private provider detail"}},
    ],
)
def test_anthropic_incomplete_or_unsupported_result_fails(body):
    model = Model(
        ModelConfig("anthropic", "test", "https://model.test/anthropic", "key"),
        httpx.MockTransport(lambda _: httpx.Response(200, json=body)),
    )
    with pytest.raises(ModelError):
        asyncio.run(model.answer(job()))


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"choices": []},
        {"choices": [{"message": {"content": ""}}]},
        {"choices": [{"message": {"content": None, "tool_calls": [{}]}}]},
        {"choices": [{"message": {"content": "too long"}, "finish_reason": "length"}]},
    ],
)
def test_invalid_or_truncated_model_response_fails(body):
    model = Model(
        ModelConfig("compatible", "test", "https://model.test/v1", "key"),
        httpx.MockTransport(lambda _: httpx.Response(200, json=body)),
    )
    with pytest.raises(ModelError):
        asyncio.run(model.answer(job()))


def test_auth_failure_does_not_retry_or_expose_body():
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(401, text="sensitive provider error")

    model = Model(
        ModelConfig("compatible", "test", "https://model.test/v1", "key"),
        httpx.MockTransport(handler),
    )
    with pytest.raises(ModelError, match="^ACCOUNT_AUTH$"):
        asyncio.run(model.answer(job()))
    assert len(calls) == 1


def test_transient_model_failure_retries_bounded(monkeypatch):
    calls = []

    async def no_wait(_):
        pass

    monkeypatch.setattr("bunbird.model.asyncio.sleep", no_wait)

    def handler(request):
        calls.append(request)
        return httpx.Response(503)

    model = Model(
        ModelConfig("compatible", "test", "https://model.test/v1", "key"),
        httpx.MockTransport(handler),
    )
    with pytest.raises(ModelError, match="MODEL_TEMPORARY"):
        asyncio.run(model.answer(job()))
    assert len(calls) == 3


def test_history_keeps_complete_turns_and_budget():
    task = job()
    task["history"] = [
        {"prompt": "x" * 17000, "result": "old"},
        {"prompt": "recent", "result": "recent answer"},
    ]
    result = messages(task)
    assert [m["role"] for m in result] == ["system", "user", "assistant", "user"]
    assert result[1]["content"] == "recent"


def test_mock_mode_is_explicitly_labelled():
    answer = asyncio.run(Model(ModelConfig("mock", "mock-demo", "", "")).answer(job()))
    assert "演示模式，未调用真实模型" in answer["result"]


def test_worker_completion_and_retry_does_not_repeat_model():
    completed = []

    def handler(request):
        if not request.url.path.endswith("/complete"):
            return httpx.Response(200, json={"active": True})
        completed.append(json.loads(request.content))
        return httpx.Response(503 if len(completed) == 1 else 200, json={"accepted": True})

    class CountingModel:
        calls = 0

        async def answer(self, _):
            self.calls += 1
            return {"result": "回答", "model": "test"}

    model = CountingModel()

    async def scenario():
        async with httpx.AsyncClient(
            base_url="http://control", transport=httpx.MockTransport(handler)
        ) as http:
            await execute(http, model, job())

    asyncio.run(scenario())
    assert model.calls == 1
    assert len(completed) == 2
    assert completed[0] == completed[1]
    assert completed[0]["status"] == "SUCCEEDED"


@pytest.mark.parametrize("control_status", [200, 503])
def test_cancel_or_control_loss_aborts_model_without_completion(control_status):
    paths, cancelled = [], []

    def handler(request):
        if request.url.path.endswith("/stopped"):
            assert cancelled == [True]
        paths.append(request.url.path)
        return httpx.Response(control_status, json={"active": False})

    class SlowModel:
        async def answer(self, _):
            try:
                await asyncio.sleep(60)
            finally:
                cancelled.append(True)

    async def scenario():
        async with httpx.AsyncClient(
            base_url="http://control", transport=httpx.MockTransport(handler)
        ) as http:
            await asyncio.wait_for(execute(http, SlowModel(), job()), timeout=2)

    asyncio.run(scenario())
    assert cancelled == [True]
    assert paths == ["/internal/worker/tasks/task/heartbeat", "/internal/worker/tasks/task/stopped"]


def test_worker_runs_three_sessions_and_drains_queue_without_exceeding_cap(monkeypatch):
    from contextlib import asynccontextmanager
    from types import SimpleNamespace

    from bunbird.config import ControlConfig
    from bunbird.worker import run

    async def scenario():
        started = [asyncio.Event() for _ in range(4)]
        release = [asyncio.Event() for _ in range(4)]
        active, peaks, claims = set(), [], []

        def handler(request):
            if len(claims) == 4:
                return httpx.Response(200, json={})
            index = len(claims)
            claims.append(index)
            return httpx.Response(200, json={"taskId": index})

        @asynccontextmanager
        async def connect(_):
            async with httpx.AsyncClient(
                base_url="http://control", transport=httpx.MockTransport(handler)
            ) as http:
                yield http

        async def execute_fake(http, model, job):
            index = job["taskId"]
            active.add(index)
            peaks.append(len(active))
            started[index].set()
            try:
                await release[index].wait()
            finally:
                active.remove(index)

        monkeypatch.setattr("bunbird.worker.async_client", connect)
        monkeypatch.setattr("bunbird.worker.execute", execute_fake)
        task = asyncio.create_task(
            run(
                ControlConfig("http://control", "test"),
                SimpleNamespace(config=SimpleNamespace(name="test", mode="mock")),
            )
        )
        try:
            await asyncio.wait_for(asyncio.gather(*(e.wait() for e in started[:3])), 2)
            assert len(claims) == 3 and not started[3].is_set()
            release[0].set()
            await asyncio.wait_for(started[3].wait(), 2)
            assert max(peaks) == 3
        finally:
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        assert not active

    asyncio.run(scenario())


def test_timeout_cleans_execution_before_releasing_server_slot():
    cleaned, completions = [], []

    class SlowModel:
        execution_timeout = 0.01

        async def answer(self, _):
            try:
                await asyncio.sleep(60)
            finally:
                cleaned.append(True)

    def handler(request):
        if request.url.path.endswith("/complete"):
            assert cleaned == [True]
            completions.append(json.loads(request.content))
        return httpx.Response(200, json={"accepted": True})

    async def scenario():
        async with httpx.AsyncClient(
            base_url="http://control", transport=httpx.MockTransport(handler)
        ) as http:
            await execute(http, SlowModel(), job())

    asyncio.run(scenario())
    assert completions[0]["errorCode"] == "MODEL_TIMEOUT"
