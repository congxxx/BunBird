"""Deterministic Feishu workspace requests; no model-generated commands are executed."""

import base64
import hashlib
import json
import os
import re
import sys
import uuid
from pathlib import Path
from urllib.parse import urlsplit

from bunbird.workspaces import WorkspaceError, Workspaces, exclusive, git

REQUEST_ERRORS = {"WORKSPACE_INVALID_URL", "WORKSPACE_HOST_NOT_ALLOWED", "WORKSPACE_NOT_REGISTERED"}


class WorkspaceRequestError(WorkspaceError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def target_from_prompt(prompt):
    # Feishu can wrap only the email-like prefix of an SCP-style Git address.
    # Preserve the repository suffix outside the link, and use the link destination.
    prompt = re.sub(
        r"\[[^\]\r\n]*\]\((https://[^\s)]+|mailto:git@[^\s)]+)\)",
        lambda match: match[1].removeprefix("mailto:"),
        prompt,
    )
    if prompt.startswith("/workspace"):
        parts = prompt.split()
        if len(parts) != 2 or parts[0] != "/workspace":
            raise WorkspaceError(
                "用法：/workspace bunbird 或 /workspace https://主机/组织/仓库.git"
            )
        return parts[1]
    match = re.match(
        r"^(?:请)?(?:帮我)?(?:拉取|克隆|clone|创建工作区)(?:一下)?\s*"
        r"(?:这个项目|这个仓库|项目|仓库)?\s*[:：]?\s*(\S+)",
        prompt,
        re.I,
    )
    if match:
        return match[1].rstrip("，。；")
    return None


def remote_url(target):
    ssh = re.fullmatch(r"git@([A-Za-z0-9.-]+):([A-Za-z0-9_./-]+)", target)
    if ssh:
        target = f"https://{ssh[1]}/{ssh[2]}"
    parsed = urlsplit(target)
    hosts = {
        h.strip().lower()
        for h in os.getenv("WORKSPACE_GIT_HOSTS", "github.com,gitee.com,gitcode.com").split(",")
        if h.strip()
    }
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.port not in (None, 443)
        or parsed.query
        or parsed.fragment
        or not re.fullmatch(r"/[A-Za-z0-9_./-]+", parsed.path)
        or any(p in {".", ".."} for p in parsed.path.split("/"))
    ):
        raise WorkspaceRequestError(
            "WORKSPACE_INVALID_URL",
            "远程地址须为允许主机上的 HTTPS 仓库，不能包含令牌；管理员可配置 WORKSPACE_GIT_HOSTS。",
        )
    if parsed.hostname not in hosts:
        raise WorkspaceRequestError(
            "WORKSPACE_HOST_NOT_ALLOWED", "仓库主机未加入 WORKSPACE_GIT_HOSTS"
        )
    return f"https://{parsed.hostname}{parsed.path}", parsed.hostname


def provision(job):
    target = target_from_prompt(job["prompt"])
    if target is None:
        raise WorkspaceError("未识别到工作空间请求")
    manager = Workspaces(
        os.getenv("WORKSPACE_ROOT", str(Path(__file__).resolve().parents[1] / "data/workspaces")),
        job["tenantId"],
    )
    names = {r["name"] for r in manager.repositories()}
    if target in names:
        name = target
    elif ":" in target or target.startswith("git@"):
        url, host = remote_url(target)
        name = "remote-" + hashlib.sha256((job["taskId"] + url).encode()).hexdigest()[:24]
        if name not in names:
            imports = manager._guard(manager.root / "imports")
            imports.mkdir(exist_ok=True)
            with exclusive(manager._guard(imports / (name + ".lock"))):
                if name not in {r["name"] for r in manager.repositories()}:
                    source = manager._guard(imports / uuid.uuid4().hex)
                    key = re.sub(r"[^A-Z0-9]", "_", host.upper())
                    token = os.getenv("WORKSPACE_GIT_TOKEN_" + key)
                    extra = {
                        "GIT_CONFIG_COUNT": "2",
                        "GIT_CONFIG_KEY_0": "http.followRedirects",
                        "GIT_CONFIG_VALUE_0": "false",
                        "GIT_CONFIG_KEY_1": "credential.helper",
                        "GIT_CONFIG_VALUE_1": "",
                    }
                    if token:
                        user = os.getenv("WORKSPACE_GIT_USER_" + key, "oauth2")
                        encoded = base64.b64encode(f"{user}:{token}".encode()).decode()
                        extra.update(
                            GIT_CONFIG_COUNT="3",
                            GIT_CONFIG_KEY_2=f"http.https://{host}/.extraHeader",
                            GIT_CONFIG_VALUE_2="Authorization: Basic " + encoded,
                        )
                    git(imports, "clone", "--no-checkout", "--", url, str(source), extra_env=extra)
                    manager.register(
                        name, source, git(source, "branch", "--show-current") or "HEAD"
                    )
    else:
        raise WorkspaceRequestError(
            "WORKSPACE_NOT_REGISTERED", "未登记该仓库，请先登记或提供完整远程地址。"
        )
    row = manager.create(name, job["taskId"])
    clean = not row["changes"]
    if not clean:
        raise WorkspaceError("工作区已有改动，已保留；请管理员检查")
    return {
        "result": (
            f"项目已准备至隔离工作区：\n{row['worktree_path']}\n\n工作空间：{row['id']}"
            f"\n任务分支：{row['branch']}\n基线：{row['requested_ref']}\n提交：{row['base_sha']}"
            "\n\n验证结果：\n工作区创建：通过\n工作区状态：干净\n提交：未执行\n推送：未执行"
            "\n\n本轮完成仓库准备，尚未执行代码 Agent。"
        ),
        "model": "git-workspace",
        "usageJson": "{}",
    }


if __name__ == "__main__":
    try:
        value = provision(json.loads(sys.stdin.read()))
        print(json.dumps(value, ensure_ascii=True))
    except (WorkspaceError, ValueError) as error:
        # Detailed Git output and input URLs may contain credentials; do not forward them.
        print(json.dumps({"error": getattr(error, "code", "WORKSPACE_FAILED")}))
        sys.exit(2)
