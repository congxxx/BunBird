import copy
import json
from types import SimpleNamespace

import httpx
import lark_oapi as lark
import pytest
from lark_oapi.event.callback.model.p2_card_action_trigger import P2CardActionTrigger

from bunbird.config import FeishuConfig
from bunbird.feishu import Bridge, build_reply, normalize
from bunbird.inbox import Inbox


def test_queue_callback_uses_verified_operator_and_context(config):
    calls = []

    def handle(request):
        calls.append(json.loads(request.content))
        return httpx.Response(200, json={"status": "QUEUED", "message": "已优先排队"})

    bridge = Bridge.__new__(Bridge)
    bridge.config = config
    bridge.http = httpx.Client(base_url="http://control", transport=httpx.MockTransport(handle))
    payload = {
        "header": {"app_id": config.app_id},
        "event": {
            "operator": {"tenant_key": config.tenant, "open_id": "owner"},
            "context": {"open_chat_id": "chat", "open_message_id": "card"},
            "action": {"value": {"taskId": "task", "action": "prioritize", "senderId": "forged"}},
        },
    }
    answer = bridge.on_card_action(P2CardActionTrigger(payload))
    assert answer.toast.content == "已优先排队"
    assert calls == [
        {
            "taskId": "task",
            "action": "prioritize",
            "senderId": "owner",
            "chatId": "chat",
            "messageId": "card",
        }
    ]
    payload["event"]["operator"]["tenant_key"] = "other-tenant"
    assert bridge.on_card_action(P2CardActionTrigger(payload)).toast.type == "error"
    assert len(calls) == 1
    bridge.http.close()


def test_session_callback_forwards_selected_option_and_returns_replacement_card(config):
    calls = []

    def handle(request):
        assert request.url.path == "/internal/bridge/session-action"
        calls.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "message": "已设置",
                "panel": "style",
                "summary": {"taskId": "task", "status": "SUCCEEDED", "model": "test"},
            },
        )

    bridge = Bridge.__new__(Bridge)
    bridge.config = config
    bridge.http = httpx.Client(base_url="http://control", transport=httpx.MockTransport(handle))
    answer = bridge.on_card_action(
        P2CardActionTrigger(
            {
                "header": {"app_id": config.app_id},
                "event": {
                    "operator": {"tenant_key": config.tenant, "open_id": "owner"},
                    "context": {"open_chat_id": "chat", "open_message_id": "card"},
                    "action": {
                        "value": {"taskId": "task", "action": "set_style"},
                        "option": "professional",
                    },
                },
            }
        )
    )
    assert calls[0]["option"] == "professional" and calls[0]["senderId"] == "owner"
    result = json.loads(lark.JSON.marshal(answer))
    assert result["card"]["type"] == "raw"
    assert len(result["card"]["data"]["elements"][-1]["actions"]) == 5
    bridge.http.close()


@pytest.fixture
def config(tmp_path):
    return FeishuConfig(
        "cli-test",
        "secret",
        "tenant-test",
        "https://open.feishu.cn",
        frozenset(),
        str(tmp_path / "inbox.db"),
    )


@pytest.fixture
def payload():
    return {
        "schema": "2.0",
        "header": {
            "app_id": "cli-test",
            "tenant_key": "tenant-test",
            "event_id": "e1",
            "event_type": "im.message.receive_v1",
            "create_time": "1700000000000",
        },
        "event": {
            "sender": {
                "sender_type": "user",
                "tenant_key": "tenant-test",
                "sender_id": {"open_id": "ou-user"},
            },
            "message": {
                "message_id": "om-test",
                "chat_id": "oc-chat",
                "chat_type": "p2p",
                "message_type": "text",
                "content": '{"text":"你好"}',
            },
        },
    }


def test_sdk_event_dispatch_contract(config, payload):
    received = []
    handler = (
        lark.EventDispatcherHandler.builder("", "")
        .register_p2_im_message_receive_v1(
            lambda data: received.append(
                normalize(json.loads(lark.JSON.marshal(data)), config, "ou-bot")
            )
        )
        .build()
    )
    handler._do_without_validation(json.dumps(payload).encode())
    assert received[0]["text"] == "你好"
    assert received[0]["topicKey"] == "dm:ou-user"


def test_group_accepts_messages_without_bot_mentions(config, payload):
    message = payload["event"]["message"]
    message["chat_type"] = "group"
    event = normalize(payload, config, "ou-bot")
    assert event["text"] == "你好"
    assert event["topicKey"] == "om-test"
    message["mentions"] = [{"key": "@_user_1", "id": {"open_id": "ou-other"}, "name": "同事"}]
    message["content"] = json.dumps({"text": "请介绍 @_user_1"})
    assert normalize(payload, config, "ou-bot")["text"] == "请介绍 @同事"
    message["mentions"].append({"key": "@_user_2", "id": {"open_id": "ou-bot"}})
    message["content"] = json.dumps({"text": "@_user_2 请介绍 @_user_1"})
    message["root_id"] = "om-root"
    event = normalize(payload, config, "ou-bot")
    assert event["text"] == "请介绍 @同事"
    assert event["topicKey"] == "om-root"


def test_group_messages_start_topics_and_replies_continue_them(config, payload):
    message = payload["event"]["message"]
    message["chat_type"] = "group"
    first = normalize(payload, config, "ou-bot")
    message["message_id"] = "om-next"
    assert normalize(payload, config, "ou-bot")["topicKey"] != first["topicKey"]
    message["root_id"] = first["topicKey"]
    assert normalize(payload, config, "ou-bot")["topicKey"] == first["topicKey"]


def test_group_bot_messages_and_disallowed_chats_still_ignored(config, payload):
    payload["event"]["message"]["chat_type"] = "group"
    payload["event"]["sender"]["sender_type"] = "app"
    assert normalize(payload, config, "ou-bot") is None
    payload["event"]["sender"]["sender_type"] = "user"
    restricted = FeishuConfig(
        config.app_id,
        config.app_secret,
        config.tenant,
        config.domain,
        frozenset({"different-chat"}),
        config.spool_path,
    )
    assert normalize(payload, restricted, "ou-bot") is None


@pytest.mark.parametrize(
    "field,value",
    [
        ("tenant_key", "other-tenant"),
        ("app_id", "other-app"),
        ("event_type", "im.message.message_read_v1"),
        ("event_id", None),
    ],
)
def test_reject_wrong_event_identity(config, payload, field, value):
    payload["header"][field] = value
    assert normalize(payload, config, "ou-bot") is None


def test_bot_and_external_sender_ignored(config, payload):
    payload["event"]["sender"]["sender_type"] = "app"
    assert normalize(payload, config, "ou-bot") is None
    payload["event"]["sender"].update(sender_type="user", tenant_key="external")
    assert normalize(payload, config, "ou-bot") is None


def test_allowlist_and_unsupported_content(config, payload):
    restricted = FeishuConfig(
        config.app_id,
        config.app_secret,
        config.tenant,
        config.domain,
        frozenset({"another-chat"}),
        config.spool_path,
    )
    assert normalize(payload, restricted, "ou-bot") is None
    payload["event"]["message"]["message_type"] = "image"
    assert normalize(payload, config, "ou-bot")["text"] == "/unsupported"
    payload["event"]["message"].update(message_type="text", content="not json")
    assert normalize(payload, config, "ou-bot")["text"] == "/unsupported"


def test_inbox_survives_restart_and_deduplicates(config, payload):
    event = normalize(payload, config, "ou-bot")
    inbox = Inbox(config.spool_path)
    inbox.put(event)
    inbox.put(event)
    inbox.close()
    inbox = Inbox(config.spool_path)
    key, recovered = inbox.peek()
    assert recovered == event
    inbox.remove(key)
    assert inbox.peek() is None
    inbox.close()


def test_reply_uses_original_message_and_stable_uuid():
    delivery = {"replyTo": "om-original", "deliveryId": "fixed-uuid", "content": '你好\n"test"'}
    request = build_reply(delivery)
    assert request.message_id == "om-original"
    assert request.request_body.uuid == "fixed-uuid"
    assert json.loads(request.request_body.content) == {"text": delivery["content"]}
    assert request.request_body.reply_in_thread is False


def test_group_reply_explicitly_creates_visible_feishu_thread():
    delivery = {
        "replyTo": "om-root",
        "deliveryId": "fixed-uuid",
        "content": "已受理",
        "replyInThread": True,
    }
    request = build_reply(delivery)
    assert request.message_id == "om-root"
    assert request.request_body.reply_in_thread is True
    assert request.request_body.uuid == "fixed-uuid"


@pytest.mark.parametrize(
    "state,color",
    [("STARTED", "blue"), ("WORKSPACE", "blue"), ("SUCCEEDED", "green"), ("FAILED", "red")],
)
def test_progress_cards_preserve_thread_and_escape_content(state, color):
    request = build_reply(
        {
            "replyTo": "om-root",
            "deliveryId": "uuid",
            "content": "<at id=all>测试</at>",
            "taskState": state,
            "replyInThread": True,
        }
    )
    body = request.request_body
    card = json.loads(body.content)
    if state == "SUCCEEDED":
        assert body.msg_type == "post"
        assert "header" not in card
        assert "<at " not in card["zh_cn"]["content"][0][0]["text"]
    else:
        assert body.msg_type == "interactive"
        assert card["header"]["template"] == color
        assert card["elements"][0]["text"]["tag"] == "plain_text"
    assert body.reply_in_thread is True


def test_result_markdown_preserves_formatting_and_code():
    markdown = "**仓库信息**\n- 分支：`main`\n\n```python\nprint('ok')\n```"
    request = build_reply(
        {"replyTo": "om-root", "deliveryId": "uuid", "content": markdown, "taskState": "SUCCEEDED"}
    )
    card = json.loads(request.request_body.content)
    assert request.request_body.msg_type == "post"
    assert card["zh_cn"]["content"][0][0] == {"tag": "md", "text": markdown}


def test_control_failure_retains_event_without_sending(config, payload):
    bridge = Bridge.__new__(Bridge)
    bridge.inbox = Inbox(config.spool_path)
    event = normalize(payload, config, "ou-bot")
    bridge.inbox.put(event)
    bridge.http = httpx.Client(
        base_url="http://control", transport=httpx.MockTransport(lambda _: httpx.Response(503))
    )
    with pytest.raises(httpx.HTTPStatusError):
        bridge.pump_once()
    assert bridge.inbox.peek()[1] == event
    bridge.inbox.close()


def test_delivery_failure_only_acknowledges_failure(config):
    requests = []
    delivery = {"deliveryId": "id", "leaseToken": "lease", "replyTo": "om-1", "content": "回复"}

    def control(request):
        requests.append(request)
        return httpx.Response(200, json=delivery if request.url.path.endswith("claim") else {})

    bridge = Bridge.__new__(Bridge)
    bridge.inbox = Inbox(config.spool_path)
    bridge.http = httpx.Client(base_url="http://control", transport=httpx.MockTransport(control))
    failure = SimpleNamespace(success=lambda: False, code=999, data=None)
    bridge.sdk = SimpleNamespace(
        im=SimpleNamespace(v1=SimpleNamespace(message=SimpleNamespace(reply=lambda _: failure)))
    )
    bridge.pump_once()
    assert [r.url.path for r in requests] == [
        "/internal/bridge/deliveries/claim",
        "/internal/bridge/deliveries/id/ack",
    ]
    assert json.loads(requests[-1].content)["errorCode"] == "FEISHU_999"
    bridge.inbox.close()


def test_oversized_text_becomes_explanation_command(config, payload):
    original = copy.deepcopy(payload)
    payload["event"]["message"]["content"] = json.dumps({"text": "字" * 12001})
    assert normalize(payload, config, "ou-bot")["text"] == "/too-long"
    assert normalize(original, config, "ou-bot")["text"] == "你好"


def test_file_normalization_keeps_resource_metadata(config, payload):
    message = payload["event"]["message"]
    message.update(
        message_type="file",
        content=json.dumps({"file_key": "file_v2_test", "file_name": "example.txt"}),
    )
    event = normalize(payload, config, "ou-bot")
    assert event["text"] == "/attachment"
    assert event["resource"] == {"key": "file_v2_test", "name": "example.txt"}
    assert event["topicKey"] == "dm:ou-user"


def test_attachment_download_is_durable_before_backend_retry(
    config, payload, monkeypatch, tmp_path
):
    import io

    monkeypatch.setenv("ATTACHMENT_ROOT", str(tmp_path / "files"))
    message = payload["event"]["message"]
    message.update(
        message_type="file",
        content=json.dumps({"file_key": "file_v2_test", "file_name": "example.txt"}),
    )
    downloads, posted = [], []

    def get(request):
        downloads.append(request)
        return SimpleNamespace(success=lambda: True, file=io.BytesIO(b"document"))

    def handler(request):
        if request.url.path.endswith("/events"):
            posted.append(json.loads(request.content))
            return httpx.Response(503 if len(posted) == 1 else 200, json={"accepted": True})
        return httpx.Response(200, json={})

    bridge = Bridge.__new__(Bridge)
    bridge.sdk = SimpleNamespace(
        im=SimpleNamespace(v1=SimpleNamespace(message_resource=SimpleNamespace(get=get)))
    )
    bridge.inbox = Inbox(config.spool_path)
    bridge.inbox.put(normalize(payload, config, "ou-bot"))
    bridge.http = httpx.Client(base_url="http://control", transport=httpx.MockTransport(handler))
    with pytest.raises(httpx.HTTPStatusError):
        bridge.pump_once()
    bridge.inbox.close()
    bridge.inbox = Inbox(config.spool_path)
    bridge.pump_once()
    assert len(downloads) == 1
    assert posted[0] == posted[1] and "resource" not in posted[0]
    assert posted[0]["attachment"]["size"] == 8
    assert bridge.inbox.peek() is None
    bridge.http.close()
    bridge.inbox.close()
