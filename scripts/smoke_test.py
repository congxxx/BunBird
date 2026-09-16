"""Exercise real Java + Python processes against a local model and captured Feishu sender.

No enterprise credentials or live outbound messages are used. Requires the packaged Java jar.
"""

import json
import os
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace

import httpx

from bunbird.config import ControlConfig, FeishuConfig
from bunbird.control import client
from bunbird.feishu import Bridge, normalize
from bunbird.inbox import Inbox
from bunbird.progress_cards import ProgressCards, success
from bunbird.workspaces import Workspaces, git

ROOT = Path(__file__).resolve().parents[1]


def wait_for(probe, seconds=40):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = probe()
            if result:
                return result
        except httpx.HTTPError:
            pass
        time.sleep(0.2)
    raise AssertionError("Timed out waiting for smoke-test condition")


def stop(process):
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    jar = ROOT / "backend/target/bunbird-control-0.1.0.jar"
    if not jar.exists():
        raise SystemExit("Build first: mvn -f backend/pom.xml package")
    model_calls = []

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            assert self.path == "/v1/chat/completions"
            model_calls.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
            body = json.dumps(
                {
                    "choices": [
                        {"message": {"content": "跨进程测试回答"}, "finish_reason": "stop"}
                    ],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 5},
                }
            ).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    model_server = ThreadingHTTPServer(("127.0.0.1", 0), Provider)
    threading.Thread(target=model_server.serve_forever, daemon=True).start()
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        port = reserved.getsockname()[1]
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.getenv("JAVA_HOME") else "java"
    process = worker = None
    try:
        with tempfile.TemporaryDirectory(prefix="bunbird-smoke-") as directory:
            folder = Path(directory)
            env = {
                **os.environ,
                "PORT": str(port),
                "BIND_ADDRESS": "127.0.0.1",
                "FEISHU_TENANT_KEY": "tenant-test",
                "FEISHU_APP_ID": "cli-test",
                "DATABASE_URL": (
                    # Windows terminate() kills Java abruptly; commit synchronously before restart.
                    f"jdbc:h2:file:{folder.as_posix()}/db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0"
                ),
                "DATABASE_USER": "sa",
                "DATABASE_PASSWORD": "",
                "BUNBIRD_CONTROL_URL": f"http://127.0.0.1:{port}",
                "BUNBIRD_BRIDGE_TOKEN": secrets.token_hex(24),
                "BUNBIRD_WORKER_TOKEN": secrets.token_hex(24),
                "BUNBIRD_ADMIN_TOKEN": secrets.token_hex(24),
                "MODEL_MODE": "compatible",
                "AGENT_EXECUTOR": "model",
                "MODEL_NAME": "local-contract-model",
                "MODEL_BASE_URL": f"http://127.0.0.1:{model_server.server_port}/v1",
                "MODEL_API_KEY": "local-test-key",
                "PYTHONIOENCODING": "utf-8",
                "NO_PROXY": "127.0.0.1,localhost",
                "WORKSPACE_ROOT": str(folder / "workspaces"),
            }
            source = folder / "source"
            source.mkdir()
            git(source, "init", "-b", "main")
            (source / "README.md").write_text("smoke test")
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
            Workspaces(env["WORKSPACE_ROOT"], "tenant-test").register("demo", source, "main")
            flags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
            with (folder / "process.log").open("w", encoding="utf-8") as logs:
                bridge = Bridge.__new__(Bridge)
                bridge.inbox = Inbox(str(folder / "inbox.db"))
                bridge.http = client(
                    ControlConfig(env["BUNBIRD_CONTROL_URL"], env["BUNBIRD_BRIDGE_TOKEN"])
                )
                sent = []
                patched = []

                def reply(request):
                    sent.append(request)
                    return SimpleNamespace(
                        success=lambda: True, data=SimpleNamespace(message_id="om-reply")
                    )

                bridge.sdk = SimpleNamespace(
                    im=SimpleNamespace(
                        v1=SimpleNamespace(
                            message=SimpleNamespace(
                                reply=reply,
                                patch=lambda request: (patched.append(request), success())[1],
                            )
                        )
                    )
                )
                bridge.cards = ProgressCards(folder / "cards.db", bridge.sdk)
                admin = client(
                    ControlConfig(env["BUNBIRD_CONTROL_URL"], env["BUNBIRD_ADMIN_TOKEN"])
                )

                def start_control():
                    return subprocess.Popen(
                        [java, "-jar", str(jar)],
                        cwd=ROOT,
                        env=env,
                        stdout=logs,
                        stderr=logs,
                        creationflags=flags,
                    )

                def ready():
                    return admin.get("/health").status_code == 200

                config = FeishuConfig(
                    "cli-test",
                    "unused",
                    "tenant-test",
                    "https://open.feishu.cn",
                    frozenset(),
                    "unused",
                )
                payload = {
                    "header": {
                        "event_type": "im.message.receive_v1",
                        "event_id": "event",
                        "app_id": "cli-test",
                        "tenant_key": "tenant-test",
                    },
                    "event": {
                        "sender": {
                            "sender_type": "user",
                            "tenant_key": "tenant-test",
                            "sender_id": {"open_id": "ou-user"},
                        },
                        "message": {
                            "message_id": "om-question",
                            "chat_id": "oc-chat",
                            "chat_type": "group",
                            "message_type": "text",
                            "content": '{"text":"测试问题"}',
                        },
                    },
                }
                incoming = normalize(payload, config, "ou-bot")
                assert incoming is not None
                bridge.inbox.put(incoming)
                try:
                    bridge.pump_once()
                    raise AssertionError("Control should be offline")
                except httpx.ConnectError:
                    assert bridge.inbox.peek() is not None
                try:
                    process = start_control()
                    wait_for(ready)
                    bridge.pump_once()
                    assert bridge.inbox.peek() is None
                    for _ in range(10):
                        r = bridge.http.post("/internal/bridge/events", json=incoming)
                        r.raise_for_status()
                        assert r.json()["duplicate"]
                    tasks = admin.get("/api/tasks").json()
                    assert len(tasks) == 1
                    task_id = tasks[0]["id"]
                    stop(process)
                    process = start_control()
                    wait_for(ready)
                    restored = admin.get(f"/api/tasks/{task_id}").json()
                    assert restored["status"] == "QUEUED", restored
                    worker = subprocess.Popen(
                        [sys.executable, "-m", "bunbird.cli", "worker"],
                        cwd=ROOT,
                        env=env,
                        stdout=logs,
                        stderr=logs,
                        creationflags=flags,
                    )

                    def succeeded():
                        current = admin.get(f"/api/tasks/{task_id}").json()
                        assert current["status"] != "FAILED", current.get("error_code")
                        return current["status"] == "SUCCEEDED"

                    wait_for(succeeded)
                    wait_for(lambda: (bridge.pump_once(), len(sent) == 2)[1])
                    result = admin.get(f"/api/tasks/{task_id}").json()
                    assert result["result"] == "跨进程测试回答"
                    assert result["model"] == "local-contract-model"
                    assert len(model_calls) == 1
                    deliveries = admin.get(f"/api/tasks/{task_id}/deliveries").json()
                    assert all(d["status"] == "SENT" for d in deliveries)
                    assert len({r.request_body.uuid for r in sent}) == 2
                    assert all(r.message_id == "om-question" for r in sent)
                    assert all(r.request_body.reply_in_thread is True for r in sent)
                    events = admin.get(f"/api/tasks/{task_id}/events").json()
                    assert [e["type"] for e in events] == [
                        "QUEUED",
                        "RUNNING",
                        "PROGRESS_STARTED",
                        "SUCCEEDED",
                    ]
                    workspace_incoming = {
                        **incoming,
                        "eventId": "workspace-event",
                        "messageId": "om-workspace",
                        "topicKey": "om-workspace",
                        "text": "/workspace demo",
                    }
                    response = bridge.http.post("/internal/bridge/events", json=workspace_incoming)
                    response.raise_for_status()
                    task_id = response.json()["taskId"]
                    wait_for(succeeded)
                    wait_for(lambda: (bridge.pump_once(), len(sent) == 5)[1])
                    current = admin.get(f"/api/tasks/{task_id}").json()
                    assert current["model"] == "git-workspace"
                    assert "提交：未执行" in current["result"]
                    assert len(model_calls) == 1
                    assert (
                        Workspaces(env["WORKSPACE_ROOT"], "tenant-test").list()[0]["task_id"]
                        == task_id
                    )
                    assert all(r.request_body.reply_in_thread for r in sent[2:])
                    assert [r.request_body.msg_type for r in sent[2:]] == [
                        "interactive",
                        "interactive",
                        "post",
                    ]
                    final_post = json.loads(sent[-1].request_body.content)
                    assert final_post["zh_cn"]["content"][0][0]["text"] == current["result"]
                    assert len(patched) == 1
                    assert "最终结论见下方消息" in patched[0].request_body.content
                    print(
                        "PASS: inbox recovery, 10 duplicate replays, database restart, "
                        "Java -> Python -> HTTP model and real Git workspace -> "
                        "captured Feishu cards; "
                        "one model call"
                    )
                except Exception:
                    # Synthetic data only; real credentials are never passed to these processes.
                    logs.flush()
                    print(
                        (folder / "process.log").read_text(encoding="utf-8", errors="replace")[
                            -8000:
                        ]
                    )
                    raise
                finally:
                    stop(worker)
                    stop(process)
                    admin.close()
                    bridge.http.close()
                    bridge.inbox.close()
                    bridge.cards.close()
    finally:
        stop(worker)
        stop(process)
        model_server.shutdown()
        model_server.server_close()


if __name__ == "__main__":
    main()
