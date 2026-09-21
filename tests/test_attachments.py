import hashlib
import io
import uuid
from types import SimpleNamespace as NS

import pytest

from bunbird.attachments import MAX_SIZE, download, prepare
from bunbird.model import ModelError


def sdk(data):
    return NS(
        im=NS(
            v1=NS(
                message_resource=NS(
                    get=lambda request: NS(success=lambda: True, file=io.BytesIO(data))
                )
            )
        )
    )


def event():
    return {
        "tenantId": "tenant",
        "appId": "app",
        "messageId": "message",
        "resource": {"key": "file_key", "name": "../CON.txt"},
    }


def test_download_is_idempotent_and_attachment_is_copied_only_into_session(tmp_path, monkeypatch):
    monkeypatch.setenv("ATTACHMENT_ROOT", str(tmp_path / "store"))
    data = b"the uploaded document"
    file = download(sdk(data), event())
    assert file == download(None, event())
    assert "/" not in file["name"] and "\\" not in file["name"]
    job = {
        "taskId": str(uuid.uuid4()),
        "tenantId": "tenant",
        "attachments": [file],
        "prompt": "summarize",
    }
    prompt = prepare(job, tmp_path / "session")
    assert "summarize" in prompt and file["name"] in prompt
    copied = list((tmp_path / "session" / "attachments").rglob("*.txt"))
    assert len(copied) == 1 and copied[0].read_bytes() == data
    assert hashlib.sha256(data).hexdigest() == file["sha256"]
    with pytest.raises(ModelError, match="ATTACHMENT_INVALID"):
        prepare({**job, "attachments": [{**file, "sha256": "0" * 64}]}, tmp_path / "session")
    with pytest.raises(ModelError, match="ATTACHMENT_MISSING"):
        prepare({**job, "tenantId": "another-tenant"}, tmp_path / "other-session")
    with pytest.raises(ModelError, match="ATTACHMENT_INVALID"):
        prepare({**job, "taskId": "../../escape"}, tmp_path / "session")


def test_oversize_resource_is_not_saved(tmp_path, monkeypatch):
    monkeypatch.setenv("ATTACHMENT_ROOT", str(tmp_path))
    with pytest.raises(ValueError, match="ATTACHMENT_TOO_LARGE"):
        download(sdk(b"a" * (MAX_SIZE + 1)), event())
    assert not list(tmp_path.rglob("*.bin"))
