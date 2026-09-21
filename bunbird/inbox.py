import json
import sqlite3
import threading
from pathlib import Path


class Inbox:
    """Commit locally before the SDK acknowledges an event; relay after the backend recovers."""

    def __init__(self, path: str):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.db = sqlite3.connect(path, check_same_thread=False, timeout=2)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("""CREATE TABLE IF NOT EXISTS inbox (
            id TEXT PRIMARY KEY, payload TEXT NOT NULL,
            created_at INTEGER NOT NULL DEFAULT (unixepoch()))""")
        self.db.commit()

    def put(self, event: dict):
        key = json.dumps([event["tenantId"], event["appId"], event["eventId"]])
        with self.lock, self.db:
            self.db.execute(
                "INSERT OR IGNORE INTO inbox(id,payload) VALUES(?,?)",
                (key, json.dumps(event, ensure_ascii=False)),
            )

    def peek(self):
        with self.lock:
            row = self.db.execute(
                "SELECT id,payload FROM inbox ORDER BY created_at,rowid LIMIT 1"
            ).fetchone()
        return (row[0], json.loads(row[1])) if row else None

    def remove(self, key: str):
        with self.lock, self.db:
            self.db.execute("DELETE FROM inbox WHERE id=?", (key,))

    def replace(self, key: str, event: dict):
        with self.lock, self.db:
            self.db.execute(
                "UPDATE inbox SET payload=? WHERE id=?",
                (json.dumps(event, ensure_ascii=False), key),
            )

    def close(self):
        self.db.close()
