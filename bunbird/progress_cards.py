"""Persist the one progress-card message ID per task across connector restarts."""

import json
import sqlite3
from pathlib import Path
from types import SimpleNamespace

from lark_oapi.api.im.v1 import PatchMessageRequest, PatchMessageRequestBody


def card(text, finished=False, failed=False):
    return {
        "config": {"update_multi": True},
        "header": {
            "template": "red" if failed else "green" if finished else "blue",
            "title": {"tag": "plain_text", "content": "💭 思考与进展"},
        },
        "elements": [{"tag": "div", "text": {"tag": "plain_text", "content": text}}],
    }


def success(message_id=None):
    return SimpleNamespace(success=lambda: True, data=SimpleNamespace(message_id=message_id))


def queue_card(task_id, prioritized=False):
    text = (
        "已优先排队，等待执行空位。不会中断正在执行的任务。"
        if prioritized
        else "并发会话已满（最多 3 个），当前任务排队中，空位释放后自动开始。"
    )
    actions = []
    if not prioritized:
        actions.append(
            {
                "tag": "button",
                "type": "primary",
                "text": {"tag": "plain_text", "content": "⏩ 优先处理这条"},
                "value": {"taskId": task_id, "action": "prioritize"},
            }
        )
    actions.append(
        {
            "tag": "button",
            "type": "default",
            "text": {"tag": "plain_text", "content": "↩ 撤回这条"},
            "value": {"taskId": task_id, "action": "withdraw"},
        }
    )
    return {
        "config": {"update_multi": True},
        "header": {
            "template": "grey",
            "title": {"tag": "plain_text", "content": "⏳ 已收到，排队中"},
        },
        "elements": [
            {"tag": "div", "text": {"tag": "plain_text", "content": text}},
            {"tag": "hr"},
            {
                "tag": "div",
                "text": {
                    "tag": "plain_text",
                    "content": "仅发起人可操作。优先处理只调整排队顺序；撤回后不再执行。",
                },
            },
            {"tag": "action", "actions": actions},
        ],
    }


class ProgressCards:
    def __init__(self, path, sdk):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS progress_card(task_id TEXT PRIMARY KEY, "
            "message_id TEXT NOT NULL, closed INTEGER NOT NULL DEFAULT 0)"
        )
        self.db.commit()
        self.api = sdk.im.v1.message

    def update(self, delivery, reply_builder):
        task = delivery["taskId"]
        row = self.db.execute(
            "SELECT message_id,closed FROM progress_card WHERE task_id=?", (task,)
        ).fetchone()
        if row and row[1]:
            return success(row[0])
        content = json.dumps(
            queue_card(task, "优先" in delivery["content"])
            if delivery.get("taskState") == "CAPACITY_QUEUE"
            else card(delivery["content"]),
            ensure_ascii=False,
        )
        if row:
            return self.patch(row[0], content)
        request = reply_builder(delivery)
        request.request_body.msg_type = "interactive"
        request.request_body.content = content
        response = self.api.reply(request)
        if response.success():
            with self.db:
                self.db.execute(
                    "INSERT INTO progress_card(task_id,message_id) VALUES(?,?)",
                    (task, response.data.message_id),
                )
        return response

    def patch(self, message_id, content):
        response = self.api.patch(
            PatchMessageRequest.builder()
            .message_id(message_id)
            .request_body(PatchMessageRequestBody.builder().content(content).build())
            .build()
        )
        return success(message_id) if response.success() else response

    def finish(self, delivery):
        row = self.db.execute(
            "SELECT message_id,closed FROM progress_card WHERE task_id=?",
            (delivery.get("taskId", ""),),
        ).fetchone()
        if not row or row[1]:
            return success()
        state = delivery["taskState"]
        text = (
            "✅ 本回合已完成，最终结论见下方消息。"
            if state == "SUCCEEDED"
            else "已被新消息中断，正在处理最新请求。"
            if state == "SUPERSEDED"
            else "本回合已取消。"
            if state == "CANCELLED"
            else "本回合未完成，原因见下方消息。"
        )
        response = self.patch(
            row[0],
            json.dumps(card(text, finished=True, failed=state == "FAILED"), ensure_ascii=False),
        )
        if response.success():
            with self.db:
                self.db.execute(
                    "UPDATE progress_card SET closed=1 WHERE task_id=?", (delivery["taskId"],)
                )
        return response

    def close(self):
        self.db.close()
