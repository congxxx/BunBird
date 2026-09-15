import asyncio
import json

import httpx
import pytest

from bunbird.worker import execute
from bunbird.workspace_task import provision, remote_url, target_from_prompt
from bunbird.workspaces import WorkspaceError, Workspaces, git


@pytest.mark.parametrize(
    "prompt,target",
    [
        ("/workspace bunbird", "bunbird"),
        ("拉取一下这个项目\nhttps://github.com/org/repo.git", "https://github.com/org/repo.git"),
        ("帮我克隆 bunbird", "bunbird"),
        ("不要克隆 bunbird", None),
        ("解释 git worktree", None),
    ],
)
def test_routing(prompt, target):
    assert target_from_prompt(prompt) == target


@pytest.mark.parametrize(
    "url",
    [
        "https://x:secret@github.com/o/r",
        "http://github.com/o/r",
        "https://localhost/o/r",
        "file:///repo",
        "https://github.com/o/r?token=secret",
        "https://github.com/../r",
    ],
)
def test_rejects_unsafe_remote(url):
    with pytest.raises(WorkspaceError):
        remote_url(url)


def test_ssh_address_is_normalized_without_ssh_execution():
    assert remote_url("git@github.com:org/repo.git")[0] == "https://github.com/org/repo.git"


def test_feishu_mailto_link_preserves_ssh_repository_suffix():
    prompt = "拉取一下这个项目\n[git@code.example.com:](mailto:git@code.example.com:)org/repo.git"
    assert target_from_prompt(prompt) == "git@code.example.com:org/repo.git"


def test_feishu_https_link_uses_destination():
    assert (
        target_from_prompt("克隆 [项目](https://github.com/org/repo.git)")
        == "https://github.com/org/repo.git"
    )


@pytest.fixture
def registered(tmp_path, monkeypatch):
    source = tmp_path / "source"
    source.mkdir()
    git(source, "init", "-b", "main")
    (source / "README.md").write_text("test")
    git(source, "add", ".")
    git(
        source,
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.invalid",
        "commit",
        "-m",
        "initial",
    )
    root = tmp_path / "spaces"
    monkeypatch.setenv("WORKSPACE_ROOT", str(root))
    Workspaces(root, "tenant-test").register("bunbird", source, "main")
    return root, source


def test_worker_routes_real_workspace_without_model(registered):
    calls = []

    def handler(request):
        calls.append((request.url.path, json.loads(request.content)))
        return httpx.Response(200, json={"accepted": True, "active": True})

    class NoModel:
        async def answer(self, _):
            pytest.fail("workspace request must not call model")

    async def scenario():
        async with httpx.AsyncClient(
            base_url="http://control", transport=httpx.MockTransport(handler)
        ) as http:
            await execute(
                http,
                NoModel(),
                {
                    "taskId": "test-task",
                    "tenantId": "tenant-test",
                    "leaseToken": "lease",
                    "prompt": "/workspace bunbird",
                },
            )

    asyncio.run(scenario())
    progress = [body["phase"] for path, body in calls if path.endswith("/progress")]
    assert progress == ["STARTED", "WORKSPACE"]
    result = calls[-1][1]
    assert result["status"] == "SUCCEEDED"
    assert "提交：未执行" in result["result"]
    assert Workspaces(registered[0], "tenant-test").list()[0]["state"] == "READY"


def test_remote_auth_is_ephemeral_and_never_in_git_config(registered, monkeypatch):
    import bunbird.workspace_task as module

    root, source = registered
    real_git = module.git
    auth = []
    monkeypatch.setenv("WORKSPACE_GIT_TOKEN_GITHUB_COM", "test-secret-value")

    def fake_remote(directory, *args, **kwargs):
        if args[0] == "clone":
            auth.append(kwargs["extra_env"])
            assert args[-2] == "https://github.com/org/repo.git"
            return real_git(
                directory, "clone", "--no-local", "--no-checkout", str(source), args[-1]
            )
        return real_git(directory, *args, **kwargs)

    monkeypatch.setattr(module, "git", fake_remote)
    result = provision(
        {
            "taskId": "remote-test",
            "tenantId": "tenant-test",
            "prompt": "/workspace https://github.com/org/repo.git",
        }
    )
    assert "test-secret-value" not in json.dumps(result)
    assert auth[0]["GIT_CONFIG_VALUE_2"].startswith("Authorization: Basic ")
    for config in root.rglob("config"):
        assert "Authorization" not in config.read_text()
        assert "test-secret-value" not in config.read_text()


def test_cancel_terminates_workspace_process(monkeypatch):
    import sys

    spawned = []
    original = asyncio.create_subprocess_exec

    async def spawn(*args, **kwargs):
        if "bunbird.workspace_task" in args:
            args = (sys.executable, "-c", "import time; time.sleep(60)")
            child = await original(*args, **kwargs)
            spawned.append(child)
            return child
        return await original(*args, **kwargs)

    monkeypatch.setattr(asyncio, "create_subprocess_exec", spawn)
    paths = []

    def handler(request):
        paths.append(request.url.path)
        return httpx.Response(200, json={"active": False})

    async def scenario():
        async with httpx.AsyncClient(
            base_url="http://control", transport=httpx.MockTransport(handler)
        ) as http:
            await asyncio.wait_for(
                execute(
                    http,
                    None,
                    {
                        "taskId": "cancel-test",
                        "leaseToken": "lease",
                        "tenantId": "test",
                        "prompt": "/workspace bunbird",
                    },
                ),
                timeout=10,
            )

    asyncio.run(scenario())
    assert spawned and spawned[0].returncode is not None
    assert not any(path.endswith("/complete") for path in paths)
