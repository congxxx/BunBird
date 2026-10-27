import asyncio
import json

import httpx
import pytest

from bunbird.delegate import delegate, usage_metrics
from bunbird.progress_cards import card


@pytest.mark.parametrize("fail", [False, True])
def test_delegate_reports_actual_usage_or_falls_back(fail):
    reports = []

    def handler(request):
        if request.url.path.endswith("begin"):
            return httpx.Response(
                200,
                json={
                    "delegationId": "d",
                    "model": "other-model",
                    "mode": "anthropic",
                    "baseUrl": "https://example.test",
                    "apiKey": "secret",
                },
            )
        reports.append(json.loads(request.content))
        return httpx.Response(200, json={"accepted": True})

    class FakeModel:
        def __init__(self, config):
            assert config.name == "other-model"

        async def answer(self, job):
            if fail:
                raise RuntimeError("private provider error")
            return {"result": "建议 secret", "usageJson": '{"input_tokens":100,"output_tokens":20}'}

    async def run():
        async with httpx.AsyncClient(
            base_url="http://control/", transport=httpx.MockTransport(handler)
        ) as http:
            return await delegate({"name": "审查", "prompt": "审查这段代码"}, http, FakeModel)

    result = asyncio.run(run())
    assert "secret" not in str(result)
    assert reports[0]["status"] == ("FAILED" if fail else "SUCCEEDED")
    assert reports[0]["totalTokens"] == (None if fail else 120)
    assert bool(result.get("fallback")) == fail


def test_no_other_model_does_not_run_or_report_a_delegation():
    requests = []

    def handler(request):
        requests.append(request.url.path)
        return httpx.Response(200, json={"fallback": True, "message": "自行完成"})

    async def run():
        async with httpx.AsyncClient(
            base_url="http://control/", transport=httpx.MockTransport(handler)
        ) as http:
            return await delegate(
                {"name": "审查", "prompt": "正文"},
                http,
                lambda _: pytest.fail("must not call a model"),
            )

    assert asyncio.run(run())["fallback"]
    assert requests == ["/begin"]


def test_delegation_card_keeps_unknown_usage_unknown():
    rows = [
        {
            "name": "审查",
            "status": "FAILED",
            "model": "other-model",
            "started_at": 1000,
            "finished_at": 4000,
            "total_tokens": None,
            "output_tokens": None,
        }
    ]
    view = card("完成", finished=True, delegations=rows, model="main-model")
    text = json.dumps(view, ensure_ascii=False)
    assert "后台与协作 1/1" in text and "未上报" in text
    assert "主模型接手" in text and "other-model" in text
    assert "后台与协作" not in json.dumps(card("完成", finished=True), ensure_ascii=False)
    assert usage_metrics("{}")["totalTokens"] is None
    assert usage_metrics("null")["totalTokens"] is None
    assert usage_metrics('{"prompt_tokens":12,"completion_tokens":3}')["totalTokens"] == 15
