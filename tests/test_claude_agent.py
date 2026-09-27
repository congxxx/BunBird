import asyncio
import json
import sys

import httpx
import pytest

from bunbird.claude_agent import ClaudeAgent, child_env, public_progress, session_key
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


@pytest.mark.parametrize(
    "failure,expected",
    [
        ("rate_limit", "ACCOUNT_RATE_LIMIT"),
        ("billing_error", "ACCOUNT_QUOTA"),
        ("authentication_failed", "ACCOUNT_AUTH"),
        ("unknown", "ACCOUNT_QUOTA"),
    ],
)
def test_account_errors_allow_resume_with_new_credentials(tmp_path, monkeypatch, failure, expected):
    monkeypatch.setenv("AGENT_SESSION_ROOT", str(tmp_path / "sessions"))
    monkeypatch.setattr("bunbird.claude_agent.shutil.which", lambda _: sys.executable)
    script = tmp_path / "fake.py"
    events = [
        {"type": "system", "subtype": "init"},
        {
            "type": "assistant",
            "error": failure,
            "message": {
                "content": [
                    {
                        "type": "text",
                        "text": 'API Error: {"base_resp":{"status_code":2056}}'
                        if failure == "unknown"
                        else "private provider error",
                    }
                ]
            },
        },
        {"type": "result", "is_error": True},
    ]
    script.write_text(
        "import sys\nsys.stdin.read()\n"
        + "\n".join(f"print({json.dumps(json.dumps(e))})" for e in events),
        encoding="utf-8",
    )
    original = asyncio.create_subprocess_exec
    processes = []
    commands = []
    environments = []

    async def spawn(*args, **kwargs):
        commands.append(args)
        environments.append(kwargs["env"])
        proc = await original(sys.executable, str(script), **kwargs)
        processes.append(proc)
        return proc

    monkeypatch.setattr(asyncio, "create_subprocess_exec", spawn)

    async def progress(*_):
        pass

    async def scenario():
        for key in ("first-key", "backup-key"):
            agent = ClaudeAgent(
                ModelConfig(
                    "anthropic",
                    "first-model" if key == "first-key" else "backup-model",
                    "https://test",
                    key,
                )
            )
            monkeypatch.setattr(agent, "workspace", lambda folder, *_: folder)
            with pytest.raises(ModelError, match=expected):
                await agent.answer_with_progress(job(accountRetry=key == "backup-key"), progress)
            assert processes[-1].returncode is not None

    asyncio.run(scenario())
    assert "--session-id" in commands[0] and "--resume" in commands[1]
    assert commands[0][-1] == commands[1][-1]
    assert commands[0][commands[0].index("--model") + 1] == "first-model"
    assert commands[1][commands[1].index("--model") + 1] == "backup-model"
    assert environments[0]["ANTHROPIC_API_KEY"] == "first-key"
    assert environments[1]["ANTHROPIC_API_KEY"] == "backup-key"
    assert environments[0]["CLAUDE_CONFIG_DIR"] == environments[1]["CLAUDE_CONFIG_DIR"]


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
    from bunbird.agent_skills import mount

    _, skill_folder = agent.prepare(job())
    mount(skill_folder)
    monkeypatch.setattr(agent, "workspace", lambda folder, *_: folder)
    script = tmp_path / "fake.py"
    events = [
        {"type": "system", "subtype": "init"},
        {
            "type": "stream_event",
            "event": {
                "type": "content_block_delta",
                "delta": {"type": "thinking_delta", "thinking": "<progress>PRIVATE</progress>"},
            },
        },
        {
            "type": "assistant",
            "message": {
                "content": [
                    {"type": "thinking", "thinking": "<progress>PRIVATE</progress>"},
                    {
                        "type": "text",
                        "text": "<progress>已确认需要创建项目文件，"
                        "接下来写入并检查内容。</progress>",
                    },
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
    for command in commands:
        assert "read-attachments" in command[command.index("--append-system-prompt") + 1]
    assert job()["prompt"] not in commands[0]
    assert "PRIVATE" not in str(notices) and "do-not-forward" not in str(notices)
    assert notices[2][1] == "已确认需要创建项目文件，接下来写入并检查内容。"
    assert "正在创建文件" not in str(notices)
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


def test_only_explicit_public_summary_is_extracted():
    assert public_progress("普通最终回答") == ""
    assert public_progress("<progress>准备核对配置。</pro") == "准备核对配置。"
    assert public_progress("<progress>已核对配置。</progress>最终结论") == "已核对配置。"


def test_preemption_kills_process_tree_before_resuming_session(tmp_path, monkeypatch):
    monkeypatch.setenv("AGENT_SESSION_ROOT", str(tmp_path / "sessions"))
    monkeypatch.setattr("bunbird.claude_agent.shutil.which", lambda _: sys.executable)
    agent = ClaudeAgent(ModelConfig("anthropic", "test", "https://test", "test-key"))
    monkeypatch.setattr(agent, "workspace", lambda folder, *_: folder)
    ready = tmp_path / "ready"
    leaked = tmp_path / "leaked"
    script = tmp_path / "fake_agent.py"
    script.write_text(
        "import sys, json, subprocess, time\n"
        "from pathlib import Path\n"
        "prompt = sys.stdin.read()\n"
        "print(json.dumps({'type':'system','subtype':'init'}), flush=True)\n"
        "if prompt == 'old':\n"
        "    subprocess.Popen([sys.executable, '-c', "
        + repr(
            "from pathlib import Path; import time; Path("
            + repr(str(ready))
            + ").touch(); time.sleep(2); Path("
            + repr(str(leaked))
            + ").touch()"
        )
        + "])\n"
        "    time.sleep(60)\n"
        "else:\n"
        "    print(json.dumps({'type':'result','result':'new answer',"
        "'is_error':False}), flush=True)\n",
        encoding="utf-8",
    )
    original = asyncio.create_subprocess_exec
    processes, commands, completed = [], [], []

    async def spawn(*args, **kwargs):
        if "--output-format" not in args:
            return await original(*args, **kwargs)
        commands.append(args)
        process = await original(sys.executable, str(script), **kwargs)
        processes.append(process)
        return process

    monkeypatch.setattr(asyncio, "create_subprocess_exec", spawn)

    def handler(request):
        if request.url.path.endswith("/heartbeat"):
            return httpx.Response(200, json={"active": not ready.exists()})
        if request.url.path.endswith("/stopped"):
            assert all(p.returncode is not None for p in processes)
        if request.url.path.endswith("/complete"):
            completed.append(json.loads(request.content))
        return httpx.Response(200, json={"accepted": True})

    async def scenario():
        async with httpx.AsyncClient(
            base_url="http://control", transport=httpx.MockTransport(handler)
        ) as http:
            await asyncio.wait_for(execute(http, agent, job(prompt="old")), timeout=8)
            assert ready.exists() and completed == []
            await asyncio.wait_for(execute(http, agent, job(taskId="new", prompt="new")), timeout=8)
            await asyncio.sleep(2.2)

    asyncio.run(scenario())
    assert not leaked.exists(), "old agent child must not keep executing after preemption"
    assert "--resume" in commands[1] and commands[0][-1] == commands[1][-1]
    assert [result["result"] for result in completed] == ["new answer"]
