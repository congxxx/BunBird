from types import SimpleNamespace as NS

import httpx
import pytest

from bunbird.feishu import Bridge
from bunbird.inbox import Inbox
from bunbird.reactions import Reactions


class FakeReactions:
    def __init__(self):
        self.items = []
        self.calls = []
        self.fail = False

    def response(self, data=None):
        return NS(success=lambda: not self.fail, code=999 if self.fail else 0, data=data)

    def list(self, request):
        assert 1 <= request.page_size <= 50
        return self.response(
            NS(
                items=[
                    r for r in self.items if r.reaction_type.emoji_type == request.reaction_type
                ],
                has_more=False,
            )
        )

    def create(self, request):
        emoji = request.request_body.reaction_type.emoji_type
        self.calls.append(("create", emoji))
        if not self.fail:
            self.items.append(
                NS(
                    reaction_id="r-" + str(len(self.calls)),
                    operator=NS(operator_type="app", operator_id="cli-test"),
                    reaction_type=NS(emoji_type=emoji),
                )
            )
        return self.response()

    def delete(self, request):
        self.calls.append(("delete", request.reaction_id))
        if not self.fail:
            self.items = [r for r in self.items if r.reaction_id != request.reaction_id]
        return self.response()


def setup(tmp_path):
    api = FakeReactions()
    sdk = NS(im=NS(v1=NS(message_reaction=api)))
    queue = Reactions(str(tmp_path / "reactions.db"), sdk, "cli-test", "ou-bot")
    return queue, api, sdk


def delivery(key, state):
    return {"deliveryId": key, "replyTo": "om-user", "taskState": state}


def emojis(api):
    return [item.reaction_type.emoji_type for item in api.items]


def test_thinking_typing_done_replace_each_other(tmp_path):
    queue, api, _ = setup(tmp_path)
    queue.enqueue(delivery("accepted", "QUEUED"))
    queue.pump_once()
    assert emojis(api) == ["THINKING"]
    queue.enqueue(delivery("answer", "SUCCEEDED"), preparing=True)
    queue.pump_once()
    assert emojis(api) == ["Typing"]
    queue.enqueue(delivery("answer", "SUCCEEDED"))
    queue.pump_once()
    assert emojis(api) == ["DONE"]
    queue.close()


def test_failed_and_cancelled_tasks_never_get_done(tmp_path):
    queue, api, _ = setup(tmp_path)
    for state in ("FAILED", "CANCELLED"):
        queue.apply("om-user", "THINKING")
        queue.enqueue(delivery(state, state))
        queue.pump_once()
        assert emojis(api) == []
    assert ("create", "DONE") not in api.calls
    queue.close()


def test_reaction_retries_do_not_regress_newer_state(tmp_path):
    queue, api, _ = setup(tmp_path)
    api.fail = True
    queue.enqueue(delivery("accepted", "QUEUED"))
    queue.pump_once()
    assert queue.db.execute("SELECT attempts FROM reaction_job").fetchone()[0] == 1
    api.fail = False
    queue.enqueue(delivery("answer", "SUCCEEDED"))
    queue.pump_once()
    queue.enqueue(delivery("accepted", "QUEUED"))
    queue.pump_once()
    assert emojis(api) == ["DONE"]
    assert (
        queue.db.execute("SELECT status FROM reaction_job WHERE id='accepted:sent'").fetchone()[0]
        == "SUPERSEDED"
    )
    queue.close()


def test_remote_side_effect_is_reconciled_after_restart(tmp_path):
    queue, api, sdk = setup(tmp_path)
    queue.enqueue(delivery("accepted", "QUEUED"))
    queue.apply("om-user", "THINKING")  # Simulate crash after API success, before local commit.
    queue.close()
    queue = Reactions(str(tmp_path / "reactions.db"), sdk, "cli-test", "ou-bot")
    queue.pump_once()
    assert api.calls.count(("create", "THINKING")) == 1
    queue.close()


def test_reactions_from_other_users_and_bots_are_preserved(tmp_path):
    queue, api, _ = setup(tmp_path)
    api.items = [
        NS(
            reaction_id="other-user",
            operator=NS(operator_type="user", operator_id="u"),
            reaction_type=NS(emoji_type="THINKING"),
        ),
        NS(
            reaction_id="other-bot",
            operator=NS(operator_type="app", operator_id="other"),
            reaction_type=NS(emoji_type="Typing"),
        ),
    ]
    queue.apply("om-user", "DONE")
    assert len(api.items) == 3
    assert not any(call[0] == "delete" for call in api.calls)
    queue.close()


def test_permission_failure_stops_after_five_attempts(tmp_path):
    queue, api, _ = setup(tmp_path)
    api.fail = True
    queue.enqueue(delivery("accepted", "QUEUED"))
    for _ in range(8):
        queue.db.execute("UPDATE reaction_job SET available_at=0")
        queue.db.commit()
        queue.pump_once()
    assert queue.db.execute("SELECT status,attempts FROM reaction_job").fetchone() == ("FAILED", 5)
    queue.close()


@pytest.mark.parametrize("reaction_failure", [False, True])
def test_bridge_types_before_answer_and_completes_only_after_delivery(tmp_path, reaction_failure):
    queue, api, _ = setup(tmp_path)
    api.fail = reaction_failure
    replies = []
    final = {**delivery("answer", "SUCCEEDED"), "content": "答案", "leaseToken": "lease"}
    requests = []

    def control(request):
        requests.append(request)
        return httpx.Response(200, json=final if request.url.path.endswith("claim") else {})

    def reply(_):
        if not reaction_failure:
            assert emojis(api) == ["Typing"]
        replies.append(True)
        return NS(success=lambda: True, data=NS(message_id="om-answer"))

    bridge = Bridge.__new__(Bridge)
    bridge.reactions = queue
    bridge.inbox = Inbox(str(tmp_path / "inbox.db"))
    bridge.http = httpx.Client(base_url="http://control", transport=httpx.MockTransport(control))
    bridge.sdk = NS(im=NS(v1=NS(message=NS(reply=reply))))
    bridge.pump_once()
    assert replies == [True]
    assert requests[-1].url.path.endswith("/ack")
    queue.pump_once()
    if not reaction_failure:
        assert emojis(api) == ["DONE"]
    bridge.http.close()
    bridge.inbox.close()
    queue.close()
