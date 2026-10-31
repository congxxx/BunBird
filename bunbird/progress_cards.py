"""Persist the one progress-card message ID per task across connector restarts."""

import json
import sqlite3
import time
from pathlib import Path
from types import SimpleNamespace

from lark_oapi.api.im.v1 import PatchMessageRequest, PatchMessageRequestBody

from bunbird.session_cards import completion_card


def card(text, finished=False, failed=False, delegations=None, model=""):
    content = {
        "config": {"update_multi": True},
        "header": {
            "template": "red" if failed else "green" if finished else "blue",
            "title": {"tag": "plain_text", "content": "💭 思考与进展"},
        },
        "elements": [{"tag": "div", "text": {"tag": "plain_text", "content": text}}],
    }
    if delegations:
        if finished:
            content["header"]["title"]["content"] = (
                "✅ BunBird · 运行结束" if not failed else "BunBird · 任务未完成"
            )
        if model:
            content["elements"].insert(
                0, {"tag": "div", "text": {"tag": "plain_text", "content": f"主模型：{model}"}}
            )
        completed = sum(row["status"] != "RUNNING" for row in delegations)
        content["elements"].extend(
            [
                {"tag": "hr"},
                {
                    "tag": "div",
                    "text": {
                        "tag": "plain_text",
                        "content": f"后台与协作 {completed}/{len(delegations)}",
                    },
                },
            ]
        )
        rows = [["任务", "状态", "耗时", "模型", "速度", "Token"]]
        for row in delegations:
            elapsed = max(
                0, (row.get("finished_at") or int(time.time() * 1000)) - row["started_at"]
            )
            seconds = elapsed // 1000
            output = row.get("output_tokens")
            speed = (
                f"{output / (elapsed / 1000):.1f} tok/s"
                if row.get("finished_at") and output is not None and elapsed > 0
                else "—"
            )
            rows.append(
                [
                    row["name"],
                    {
                        "RUNNING": "处理中",
                        "SUCCEEDED": "完成",
                        "FAILED": "失败 · 主模型接手",
                        "CANCELLED": "已中断",
                        "LOST": "未确认 · 主模型接手",
                    }.get(row["status"], "未知"),
                    f"{seconds // 60}m{seconds % 60:02}s",
                    row["model"],
                    speed,
                    str(row["total_tokens"]) if row.get("total_tokens") is not None else "未上报",
                ]
            )
        for values in rows:
            content["elements"].append(
                {
                    "tag": "column_set",
                    "flex_mode": "flow",
                    "columns": [
                        {
                            "tag": "column",
                            "width": "weighted",
                            "weight": 1,
                            "elements": [
                                {"tag": "div", "text": {"tag": "plain_text", "content": value}}
                            ],
                        }
                        for value in values
                    ],
                }
            )
        content["elements"].append(
            {
                "tag": "note",
                "elements": [
                    {
                        "tag": "plain_text",
                        "content": "速度为输出 Token / 委派总耗时，包含请求等待；"
                        "未返回的用量不作估算。",
                    }
                ],
            }
        )
    return content


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
        if "summary" not in {row[1] for row in self.db.execute("PRAGMA table_info(progress_card)")}:
            self.db.execute("ALTER TABLE progress_card ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
            self.db.commit()
        self.api = sdk.im.v1.message

    def update(self, delivery, reply_builder):
        task = delivery["taskId"]
        row = self.db.execute(
            "SELECT message_id,closed FROM progress_card WHERE task_id=?", (task,)
        ).fetchone()
        if row and row[1]:
            return success(row[0])
        summary = delivery["content"]
        if delivery.get("taskState") == "DELEGATION":
            previous = self.db.execute(
                "SELECT summary FROM progress_card WHERE task_id=?", (task,)
            ).fetchone()
            summary = previous[0] if previous and previous[0] else "正在处理请求，协作进展如下。"
        content = json.dumps(
            queue_card(task, "优先" in delivery["content"])
            if delivery.get("taskState") == "CAPACITY_QUEUE"
            else card(
                summary,
                delegations=delivery.get("delegations"),
                model=delivery.get("taskModel", ""),
            ),
            ensure_ascii=False,
        )
        if row:
            response = self.patch(row[0], content)
            if response.success():
                with self.db:
                    self.db.execute(
                        "UPDATE progress_card SET summary=? WHERE task_id=?", (summary, task)
                    )
            return response
        request = reply_builder(delivery)
        request.request_body.msg_type = "interactive"
        request.request_body.content = content
        response = self.api.reply(request)
        if response.success():
            with self.db:
                self.db.execute(
                    "INSERT INTO progress_card(task_id,message_id,summary) VALUES(?,?,?)",
                    (task, response.data.message_id, summary),
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
            json.dumps(
                completion_card(delivery["summary"], delivery.get("delegations"))
                if delivery.get("summary")
                else card(
                    text,
                    finished=True,
                    failed=state != "SUCCEEDED",
                    delegations=delivery.get("delegations"),
                    model=delivery.get("taskModel", ""),
                ),
                ensure_ascii=False,
            ),
        )
        if response.success():
            with self.db:
                self.db.execute(
                    "UPDATE progress_card SET closed=1 WHERE task_id=?", (delivery["taskId"],)
                )
        return response

    def close(self):
        self.db.close()
