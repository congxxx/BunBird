import asyncio
import json
import sys

import httpx
import pytest

from bunbird.claude_agent import ClaudeAgent, child_env, session_key
from bunbird.model import ModelConfig, ModelError
from bunbird.worker import execute


def job(**changes):
    return {
        "taskId": "task",
        "leaseToken": "lease",
        "tenantId": "tenant",
        "topicId": "topic",
        "senderId": "user",
        "prompt": "帮我拉取项目",
        **changes,
    }


def test_session_identity_is_stable_and_scoped():
    assert session_key(job()) == session_key(job(taskId="next", prompt="继续修改"))
    assert (
        len(
            {
                session_key(job()),
                session_key(job(tenantId="other")),
                session_key(job(topicId="other")),
                session_key(job(senderId="other")),
            }
        )
        == 4
    )


def test_agent_environment_omits_platform_credentials(monkeypatch, tmp_path):
    monkeypatch.setenv("FEISHU_APP_SECRET", "feishu-secret")
    monkeypatch.setenv("BUNBIRD_WORKER_TOKEN", "worker-secret")
    monkeypatch.setenv("ANTHROPIC_AUTH_TOKEN", "other-key")
    env = child_env(
        ModelConfig("anthropic", "test-model", "https://test", "selected-key"), tmp_path
    )
    assert not any(
        k in env for k in ("FEISHU_APP_SECRET", "BUNBIRD_WORKER_TOKEN", "ANTHROPIC_AUTH_TOKEN")
    )
    assert env["ANTHROPIC_API_KEY"] == "selected-key"


@pytest.mark.parametrize("denied", [False, True])
def test_print_stream_resume_and_permission_failure(tmp_path, monkeypatch, denied):
    monkeypatch.setenv("AGENT_SESSION_ROOT", str(tmp_path / "sessions"))
    monkeypatch.setattr("bunbird.claude_agent.shutil.which", lambda _: sys.executable)
    agent = ClaudeAgent(ModelConfig("anthropic", "test-model", "https://test", "test-key"))
    monkeypatch.setattr(agent, "workspace", lambda folder, *_: folder)
    script = tmp_path / "fake.py"
    events = [
        {"type": "system", "subtype": "init"},
        {
            "type": "assistant",
            "message": {
                "content": [
                    {"type": "thinking", "thinking": "PRIVATE"},
                    {"type": "tool_use", "name": "Write", "input": {"secret": "do-not-forward"}},
                ]
            },
        },
        {
            "type": "result",
            "is_error": False,
            "result": "finished",
            "permission_denials": ["Write"] if denied else [],
            "usage": {"input_tokens": 1},
        },
    ]
    script.write_text(
        "import sys\nsys.stdin.read()\n"
        + "\n".join(f"print({json.dumps(json.dumps(e))})" for e in events),
        encoding="utf-8",
    )
    original = asyncio.create_subprocess_exec
    commands, notices = [], []

    async def spawn(*args, **kwargs):
        commands.append(args)
        return await original(sys.executable, str(script), **kwargs)

    monkeypatch.setattr(asyncio, "create_subprocess_exec", spawn)

    async def progress(phase, detail):
        notices.append((phase, detail))

    async def scenario():
        for _ in range(2):
            if denied:
                with pytest.raises(ModelError, match="CLAUDE_PERMISSION_DENIED"):
                    await agent.answer_with_progress(job(), progress)
            else:
                assert (await agent.answer_with_progress(job(), progress))["result"] == "finished"

    asyncio.run(scenario())
    assert "--session-id" in commands[0] and "--resume" in commands[1]
    assert job()["prompt"] not in commands[0]
    assert "PRIVATE" not in str(notices) and "do-not-forward" not in str(notices)
    assert [phase for phase, _ in notices] == [
        "STARTED",
        "UPDATE_0",
        "UPDATE_1",
        "UPDATE_0",
        "UPDATE_1",
    ]


def test_worker_sends_natural_language_directly_to_agent():
    received, completed = [], []

    class Agent:
        async def answer_with_progress(self, incoming, progress):
            received.append(incoming["prompt"])
            return {"result": "done", "model": "test"}

    def handler(request):
        completed.append(json.loads(request.content))
        return httpx.Response(200, json={"accepted": True})

    async def scenario():
        async with httpx.AsyncClient(
            base_url="http://control", transport=httpx.MockTransport(handler)
        ) as http:
            await execute(http, Agent(), job(prompt="/workspace anything without fixed syntax"))

    asyncio.run(scenario())
    assert received == ["/workspace anything without fixed syntax"]
    assert completed[0]["status"] == "SUCCEEDED"
