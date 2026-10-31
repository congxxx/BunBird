import json
from types import SimpleNamespace

import httpx
import pytest

from bunbird.feishu import Bridge, build_reply
from bunbird.inbox import Inbox
from bunbird.progress_cards import ProgressCards, success


def test_queue_card_is_updated_in_place_and_removes_actions_when_started(tmp_path):
    replies, patches = [], []

    def reply(request):
        replies.append(json.loads(request.request_body.content))
        return success("queue-card")

    def patch(request):
        patches.append(json.loads(request.request_body.content))
        return success()

    sdk = SimpleNamespace(
        im=SimpleNamespace(v1=SimpleNamespace(message=SimpleNamespace(reply=reply, patch=patch)))
    )
    cards = ProgressCards(tmp_path / "queue.db", sdk)
    delivery = {
        "taskId": "task",
        "replyTo": "message",
        "deliveryId": "delivery",
        "taskState": "CAPACITY_QUEUE",
        "content": "排队中",
    }
    cards.update(delivery, build_reply)
    assert len(replies[0]["elements"][-1]["actions"]) == 2
    cards.update({**delivery, "content": "已优先排队"}, build_reply)
    assert len(patches[-1]["elements"][-1]["actions"]) == 1
    cards.update({**delivery, "taskState": "QUEUE_STARTED", "content": "开始处理"}, build_reply)
    assert all(element["tag"] != "action" for element in patches[-1]["elements"])
    cards.finish({**delivery, "taskState": "CANCELLED"})
    cards.update(delivery, build_reply)
    assert len(replies) == 1 and len(patches) == 3
    cards.close()


def test_progress_reuses_message_after_restart_and_closes_once(tmp_path):
    replies, patches = [], []

    def reply(request):
        replies.append(request)
        return success("om-progress")

    def patch(request):
        patches.append(request)
        return success()

    sdk = SimpleNamespace(
        im=SimpleNamespace(v1=SimpleNamespace(message=SimpleNamespace(reply=reply, patch=patch)))
    )
    path = tmp_path / "cards.db"
    cards = ProgressCards(path, sdk)
    delivery = {
        "taskId": "task",
        "replyTo": "om-user",
        "deliveryId": "first",
        "content": "正在分析",
        "replyInThread": True,
        "taskState": "UPDATE_0",
    }
    cards.update(delivery, build_reply)
    cards.close()
    cards = ProgressCards(path, sdk)
    cards.update(
        {**delivery, "deliveryId": "next", "content": "正在分析\n正在读取文件"}, build_reply
    )
    cards.finish(
        {
            **delivery,
            "taskState": "SUCCEEDED",
            "summary": {
                "taskId": "task",
                "status": "SUCCEEDED",
                "model": "test",
                "tokens": 123,
                "executor": "claude",
            },
        }
    )
    cards.finish({**delivery, "taskState": "SUCCEEDED"})
    cards.update(delivery, build_reply)
    assert len(replies) == 1 and len(patches) == 2
    assert all(p.message_id == "om-progress" for p in patches)
    final = json.loads(patches[-1].request_body.content)
    assert final["header"]["template"] == "green"
    assert final["header"]["title"]["content"] == "✅ Claude Code · 运行结束"
    assert len(final["elements"][-1]["actions"]) == 5
    assert json.loads(replies[0].request_body.content)["config"]["update_multi"]
    assert replies[0].request_body.reply_in_thread
    cards.close()


@pytest.mark.parametrize("state", ["QUEUED", "SUPERSEDED"])
def test_queued_delivery_only_enqueues_reaction(tmp_path, state):
    delivery = {
        "taskState": state,
        "deliveryId": "queue",
        "leaseToken": "lease",
        "replyTo": "om-user",
        "content": "旧版已受理文本",
    }
    acks, reactions = [], []

    def handler(request):
        if request.url.path.endswith("claim"):
            return httpx.Response(200, json=delivery)
        acks.append(json.loads(request.content))
        return httpx.Response(200, json={})

    bridge = Bridge.__new__(Bridge)
    bridge.http = httpx.Client(base_url="http://control", transport=httpx.MockTransport(handler))
    bridge.inbox = Inbox(str(tmp_path / "inbox.db"))
    bridge.reactions = SimpleNamespace(
        enqueue=lambda d, **kw: reactions.append((d, kw)), pump_once=lambda *_: None
    )
    bridge.pump_once()
    assert acks[0]["success"] and acks[0]["messageId"] is None
    assert reactions[-1][0]["taskState"] == state
    bridge.inbox.close()
    bridge.http.close()


def test_superseded_card_closes_without_final_result(tmp_path):
    patches = []
    sdk = SimpleNamespace(
        im=SimpleNamespace(
            v1=SimpleNamespace(
                message=SimpleNamespace(
                    reply=lambda _: success("card"),
                    patch=lambda request: patches.append(request) or success(),
                )
            )
        )
    )
    cards = ProgressCards(tmp_path / "cards.db", sdk)
    delivery = {"taskId": "old", "replyTo": "user", "deliveryId": "d", "content": "progress"}
    cards.update(delivery, build_reply)
    cards.finish({**delivery, "taskState": "SUPERSEDED"})
    cards.update(delivery, build_reply)
    assert len(patches) == 1
    assert (
        "新消息中断"
        in json.loads(patches[0].request_body.content)["elements"][0]["text"]["content"]
    )
    cards.close()
