"""Durable, independently retried status reactions on the originating Feishu message."""

import logging
import sqlite3
import time
from pathlib import Path

from lark_oapi.api.im.v1 import (
    CreateMessageReactionRequest,
    CreateMessageReactionRequestBody,
    DeleteMessageReactionRequest,
    Emoji,
    ListMessageReactionRequest,
)

log = logging.getLogger(__name__)
STATES = {
    "QUEUED": "THINKING",
    "RUNNING": "THINKING",
    "STARTED": "THINKING",
    "WORKSPACE": "Typing",
    "SUCCEEDED": "DONE",
    "FAILED": "CLEAR",
    "CANCELLED": "CLEAR",
}


class ReactionError(Exception):
    pass


class Reactions:
    def __init__(self, path, sdk, app_id, bot_id):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("""CREATE TABLE IF NOT EXISTS reaction_job (
            id TEXT PRIMARY KEY, message_id TEXT NOT NULL, target TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0,
            available_at REAL NOT NULL DEFAULT 0, error TEXT)""")
        self.db.commit()
        self.api = sdk.im.v1.message_reaction
        self.owners = {app_id, bot_id}

    def enqueue(self, delivery, preparing=False):
        target = (
            ("Typing" if delivery.get("taskState") == "SUCCEEDED" else "CLEAR")
            if preparing
            else STATES.get(delivery.get("taskState"))
        )
        if preparing and delivery.get("taskState") not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            return
        if target:
            key = delivery["deliveryId"] + (":preparing" if preparing else ":sent")
            with self.db:
                inserted = self.db.execute(
                    "INSERT OR IGNORE INTO reaction_job(id,message_id,target) VALUES(?,?,?)",
                    (key, delivery["replyTo"], target),
                )
                if inserted.rowcount:
                    # A newer stage supersedes retries of an older stage, never the reverse.
                    self.db.execute(
                        """UPDATE reaction_job SET status='SUPERSEDED'
                        WHERE message_id=? AND id<>? AND status='PENDING'""",
                        (delivery["replyTo"], key),
                    )

    def own(self, message_id, emoji):
        token = None
        found = []
        for _ in range(20):
            builder = (
                ListMessageReactionRequest.builder()
                .message_id(message_id)
                .reaction_type(emoji)
                .page_size(50)
            )
            if token:
                builder.page_token(token)
            result = self.api.list(builder.build())
            if not result.success():
                raise ReactionError(f"FEISHU_{result.code}")
            for item in result.data.items or []:
                actor = item.operator
                if actor and actor.operator_type == "app" and actor.operator_id in self.owners:
                    found.append(item.reaction_id)
            if not result.data.has_more:
                return found
            token = result.data.page_token
        raise ReactionError("REACTION_PAGINATION_LIMIT")

    def apply(self, message_id, target):
        # Read before creating: a lost API response or restarted connector must not duplicate state.
        if target != "CLEAR" and not self.own(message_id, target):
            result = self.api.create(
                CreateMessageReactionRequest.builder()
                .message_id(message_id)
                .request_body(
                    CreateMessageReactionRequestBody.builder()
                    .reaction_type(Emoji.builder().emoji_type(target).build())
                    .build()
                )
                .build()
            )
            if not result.success():
                raise ReactionError(f"FEISHU_{result.code}")
        for emoji in ("THINKING", "Typing"):
            if emoji == target:
                continue
            for reaction_id in self.own(message_id, emoji):
                result = self.api.delete(
                    DeleteMessageReactionRequest.builder()
                    .message_id(message_id)
                    .reaction_id(reaction_id)
                    .build()
                )
                if not result.success():
                    raise ReactionError(f"FEISHU_{result.code}")

    def pump_once(self, only_message=None):
        with self.db:
            self.db.execute(
                "UPDATE reaction_job SET status='FAILED' WHERE status='PENDING' AND attempts>=5"
            )
        row = self.db.execute(
            """SELECT j.id,j.message_id,j.target,j.attempts
            FROM reaction_job j WHERE j.status='PENDING' AND j.available_at<=?
            AND (? IS NULL OR j.message_id=?)
            AND NOT EXISTS (SELECT 1 FROM reaction_job p WHERE p.message_id=j.message_id
                AND p.rowid<j.rowid AND p.status='PENDING')
            ORDER BY j.rowid LIMIT 1""",
            (time.time(), only_message, only_message),
        ).fetchone()
        if not row:
            return
        key, message_id, target, attempts = row
        attempts += 1
        with self.db:
            self.db.execute("UPDATE reaction_job SET attempts=? WHERE id=?", (attempts, key))
        try:
            self.apply(message_id, target)
        except Exception as error:
            code = str(error) if isinstance(error, ReactionError) else type(error).__name__
            with self.db:
                self.db.execute(
                    """UPDATE reaction_job SET attempts=?,status=?,available_at=?,error=?
                    WHERE id=?""",
                    (
                        attempts,
                        "FAILED" if attempts >= 5 else "PENDING",
                        time.time() + min(30, 2**attempts),
                        code,
                        key,
                    ),
                )
            log.warning("状态表情更新失败：%s；不影响任务与文字回复", code)
        else:
            with self.db:
                self.db.execute(
                    "UPDATE reaction_job SET status='SENT',error=NULL WHERE id=?", (key,)
                )

    def close(self):
        self.db.close()
