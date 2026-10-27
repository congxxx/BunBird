"""Task-scoped analysis helper. JSON on stdin; only the answer goes to stdout."""

import asyncio
import json
import os
import sys
from pathlib import Path

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import httpx

from bunbird.claude_agent import redact
from bunbird.model import Model, ModelConfig


def usage_metrics(raw):
    try:
        data = json.loads(raw or "{}")
    except (ValueError, TypeError):
        data = {}
    if not isinstance(data, dict):
        data = {}
    anthropic = "input_tokens" in data or "output_tokens" in data
    fields = (
        ("input_tokens", "output_tokens") if anthropic else ("prompt_tokens", "completion_tokens")
    )
    if not all(type(data.get(key)) is int and data[key] >= 0 for key in fields):
        return {"inputTokens": None, "outputTokens": None, "totalTokens": None}

    def count(key):
        value = data.get(key, 0)
        return value if type(value) is int and value >= 0 else 0

    incoming, outgoing = count(fields[0]), count(fields[1])
    if anthropic:
        incoming += count("cache_read_input_tokens") + count("cache_creation_input_tokens")
    return {"inputTokens": incoming, "outputTokens": outgoing, "totalTokens": incoming + outgoing}


async def delegate(request, http, model_factory=Model):
    name, prompt = request.get("name"), request.get("prompt")
    if not isinstance(name, str) or not 1 <= len(name) <= 160:
        return {"fallback": True, "message": "请主模型自行完成：委派名称不合法。"}
    if not isinstance(prompt, str) or not 1 <= len(prompt) <= 12000:
        return {"fallback": True, "message": "请主模型自行完成：委派正文须在 12000 字以内。"}
    name = redact(name, os.getenv("ANTHROPIC_API_KEY", ""))
    prompt = redact(prompt, os.getenv("ANTHROPIC_API_KEY", ""))
    started = await http.post("begin", json={"name": name}, timeout=180)
    started.raise_for_status()
    profile = started.json()
    if profile.get("fallback"):
        return profile
    report = {
        "id": profile["delegationId"],
        "status": "FAILED",
        "inputTokens": None,
        "outputTokens": None,
        "totalTokens": None,
    }
    answer = {"fallback": True, "message": "委派未完成，请主模型自行完成该部分，不要重试委派。"}
    try:
        config = ModelConfig(
            profile["mode"], profile["model"], profile["baseUrl"], profile["apiKey"]
        )
        result = await asyncio.wait_for(
            model_factory(config).answer({"prompt": prompt, "history": []}), 120
        )
        report.update(status="SUCCEEDED", **usage_metrics(result.get("usageJson")))
        # Do not return the selected account profile or credentials to the main agent.
        answer = {
            "model": profile["model"],
            "result": redact(result["result"], config.key),
        }
    except Exception:
        pass
    finally:
        for _ in range(2):
            try:
                response = await http.post("finish", json=report, timeout=5)
                response.raise_for_status()
                break
            except httpx.HTTPError:
                continue
    return answer


async def main():
    try:
        request = json.loads(sys.stdin.buffer.read(64000))
        if not isinstance(request, dict):
            raise ValueError()
        base = os.environ["BUNBIRD_DELEGATE_URL"]
        token = os.environ["BUNBIRD_DELEGATE_LEASE"]
        async with httpx.AsyncClient(
            base_url=base, headers={"Authorization": f"Bearer {token}"}, trust_env=False
        ) as http:
            answer = await delegate(request, http)
    except Exception:
        answer = {"fallback": True, "message": "委派不可用，请主模型自行完成，不要重复调用。"}
    print(json.dumps(answer, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
