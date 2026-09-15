"""Task-owned Git clones and linked worktrees. This is not a code execution sandbox."""

import contextlib
import hashlib
import json
import os
import re
import sqlite3
import subprocess
import time
import uuid
from pathlib import Path


class WorkspaceError(ValueError):
    pass


def git(directory, *args, timeout=120):
    # Ignore inherited Git directory/config overrides and disable hooks and external filters.
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    env.update(
        GIT_CONFIG_NOSYSTEM="1",
        GIT_CONFIG_GLOBAL=os.devnull,
        GIT_TERMINAL_PROMPT="0",
        GCM_INTERACTIVE="never",
    )
    try:
        result = subprocess.run(
            [
                "git",
                "-c",
                "core.hooksPath=" + os.devnull,
                "-c",
                "core.fsmonitor=false",
                "-c",
                "protocol.ext.allow=never",
                "-C",
                str(directory),
                *args,
            ],
            env=env,
            capture_output=True,
            timeout=timeout,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise WorkspaceError(f"Git 操作失败或超时：{args[0]}") from error
    if result.returncode:
        # Git errors can contain local credentials or remote URLs; expose the operation only.
        raise WorkspaceError(f"Git 操作失败：{args[0]}（退出码 {result.returncode}）")
    return result.stdout.decode("utf-8", errors="replace").strip()


def safe_id(value, label):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", value):
        raise WorkspaceError(f"{label} 只允许字母、数字、点、短横线和下划线，最长 128 字符")
    return value


@contextlib.contextmanager
def exclusive(path):
    """OS lock releases after process death; never guess a stale lock from PID reuse."""
    with path.open("a+b") as lock:
        if lock.tell() == 0:
            lock.write(b"0")
            lock.flush()
        lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise WorkspaceError("该工作空间正在操作中，请稍后重试") from error
        try:
            yield
        finally:
            lock.seek(0)
            if os.name == "nt":
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


class Workspaces:
    def __init__(self, root, tenant):
        if not tenant or len(tenant) > 128:
            raise WorkspaceError("必须指定有效企业标识")
        self.tenant = tenant
        # Separate each tenant's registry and directories. Local OS access is still trusted.
        root = Path(root).expanduser().absolute()
        self._no_links(root)
        root.mkdir(parents=True, exist_ok=True)
        self.root = root.resolve() / hashlib.sha256(tenant.encode()).hexdigest()[:24]
        self._no_links(self.root)
        self.root.mkdir(exist_ok=True)
        self._guard(self.root / "registry.db")
        with self.db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS repository (
                    name TEXT PRIMARY KEY, source TEXT NOT NULL, default_ref TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS workspace (
                    id TEXT PRIMARY KEY, tenant TEXT NOT NULL, task_id TEXT NOT NULL,
                    attempt INTEGER NOT NULL, repository TEXT NOT NULL, source TEXT NOT NULL,
                    requested_ref TEXT NOT NULL, base_sha TEXT, branch TEXT NOT NULL,
                    state TEXT NOT NULL, error TEXT, created_at REAL NOT NULL,
                    UNIQUE(task_id, attempt));
            """)

    @staticmethod
    def _no_links(path):
        for entry in (path, *path.parents):
            if entry.is_symlink() or entry.is_junction():
                raise WorkspaceError("路径包含符号链接或目录联接，已停止操作")

    def _guard(self, path):
        path = Path(path).absolute()
        self._no_links(path)
        if not path.resolve().is_relative_to(self.root.resolve()) or path == self.root:
            raise WorkspaceError("工作空间路径越界，已停止操作")
        return path

    @contextlib.contextmanager
    def db(self):
        self._guard(self.root / "registry.db")
        db = sqlite3.connect(self.root / "registry.db", timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def register(self, name, source, ref="HEAD"):
        safe_id(name, "仓库名称")
        source = Path(source).expanduser().resolve(strict=True)
        if not source.is_dir():
            raise WorkspaceError("请登记已克隆到本机的 Git 仓库目录")
        if Path(git(source, "rev-parse", "--show-toplevel")).resolve() != source:
            raise WorkspaceError("必须登记仓库根目录")
        self._resolve(source, ref)
        with self.db() as db:
            current = db.execute("SELECT * FROM repository WHERE name=?", (name,)).fetchone()
            if current and (current["source"] != str(source) or current["default_ref"] != ref):
                raise WorkspaceError("仓库名称已经绑定其他路径或基线，请使用新名称")
            db.execute("INSERT OR IGNORE INTO repository VALUES(?,?,?)", (name, str(source), ref))
        return {"name": name, "source": str(source), "default_ref": ref}

    def repositories(self):
        with self.db() as db:
            return [dict(r) for r in db.execute("SELECT * FROM repository ORDER BY name")]

    def _resolve(self, source, ref):
        if not ref or ref.startswith("-") or len(ref) > 200 or any(c.isspace() for c in ref):
            raise WorkspaceError("无效的 Git 基线")
        sha = git(source, "rev-parse", "--verify", "--end-of-options", ref + "^{commit}")
        if not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", sha):
            raise WorkspaceError("无法解析固定基线提交")
        return sha

    def _row(self, workspace_id):
        if not re.fullmatch(r"[0-9a-f]{32}", workspace_id):
            raise WorkspaceError("无效的工作空间 ID")
        with self.db() as db:
            row = db.execute(
                "SELECT * FROM workspace WHERE id=? AND tenant=?", (workspace_id, self.tenant)
            ).fetchone()
        if not row:
            raise WorkspaceError("当前企业下未找到工作空间")
        return dict(row)

    def _paths(self, workspace_id):
        folder = self._guard(self.root / workspace_id)
        return folder, self._guard(folder / "repository"), self._guard(folder / "worktree")

    def _state(self, workspace_id, state, error=None, sha=None):
        with self.db() as db:
            db.execute(
                "UPDATE workspace SET state=?,error=?,base_sha=COALESCE(?,base_sha) WHERE id=?",
                (state, error, sha, workspace_id),
            )

    def create(self, repository, task_id, attempt=1):
        safe_id(task_id, "任务 ID")
        if not isinstance(attempt, int) or attempt < 1 or attempt > 9999:
            raise WorkspaceError("attempt 必须在 1 到 9999 之间")
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            repo = db.execute("SELECT * FROM repository WHERE name=?", (repository,)).fetchone()
            if not repo:
                raise WorkspaceError("仓库未登记，不能创建工作空间")
            existing = db.execute(
                "SELECT * FROM workspace WHERE task_id=? AND attempt=?", (task_id, attempt)
            ).fetchone()
            if existing:
                if existing["repository"] != repository:
                    raise WorkspaceError("同一任务和尝试已绑定其他仓库")
                if existing["state"] != "READY":
                    raise WorkspaceError(
                        "此尝试未就绪或已移除；请 inspect/recover，重试时递增 attempt"
                    )
                workspace_id = existing["id"]
            else:
                workspace_id = uuid.uuid4().hex
                db.execute(
                    "INSERT INTO workspace VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        workspace_id,
                        self.tenant,
                        task_id,
                        attempt,
                        repository,
                        repo["source"],
                        repo["default_ref"],
                        None,
                        f"codex/task-{workspace_id}",
                        "PREPARING",
                        None,
                        time.time(),
                    ),
                )
        if existing:
            return self.inspect(workspace_id)
        folder, clone, worktree = self._paths(workspace_id)
        try:
            folder.mkdir(exist_ok=False)
            with exclusive(self._guard(folder / "operation.lock")):
                if self._row(workspace_id)["state"] != "PREPARING":
                    raise WorkspaceError("创建已被恢复操作终止，请递增 attempt 重试")
                source = Path(repo["source"])
                self._no_links(source)
                sha = self._resolve(source, repo["default_ref"])
                self._state(workspace_id, "PREPARING", sha=sha)
                # No hard links, shared object store, reference cache or shared writable .git.
                git(
                    folder,
                    "clone",
                    "--no-local",
                    "--no-hardlinks",
                    "--no-checkout",
                    "--",
                    str(source),
                    str(clone),
                )
                git(clone, "cat-file", "-e", sha + "^{commit}")
                # Reject link/submodule trees before checkout; sandboxed handling is a later stage.
                entries = git(clone, "ls-tree", "-r", sha)
                if any(line.startswith(("120000 ", "160000 ")) for line in entries.splitlines()):
                    raise WorkspaceError("本版工作空间暂不支持符号链接或子模块")
                branch = f"codex/task-{workspace_id}"
                git(clone, "worktree", "add", "-b", branch, str(worktree), sha)
                self._verify(workspace_id, sha)
                self._state(workspace_id, "READY")
        except Exception as error:
            self._state(workspace_id, "FAILED", type(error).__name__)
            raise WorkspaceError(
                f"工作空间 {workspace_id} 创建失败，诊断目录已保留：{error}"
            ) from error
        return self.inspect(workspace_id)

    def _verify(self, workspace_id, expected_head=None):
        row = self._row(workspace_id)
        _, clone, worktree = self._paths(workspace_id)
        self._guard(clone / ".git" / "objects")
        if not (worktree / ".git").is_file():
            raise WorkspaceError("独立 worktree 不存在")
        self._guard(worktree / ".git")
        common = Path(git(worktree, "rev-parse", "--path-format=absolute", "--git-common-dir"))
        if common.resolve() != (clone / ".git").resolve():
            raise WorkspaceError("worktree 的 Git 元数据未指向任务独立仓库")
        if Path(git(worktree, "rev-parse", "--show-toplevel")).resolve() != worktree.resolve():
            raise WorkspaceError("工作区根目录不匹配")
        if git(worktree, "branch", "--show-current") != row["branch"]:
            raise WorkspaceError("工作区不在指定任务分支上")
        if expected_head and git(worktree, "rev-parse", "HEAD") != expected_head:
            raise WorkspaceError("工作区提交与固定基线不一致")
        self._guard(clone / ".git" / "objects")
        if (clone / ".git" / "objects" / "info" / "alternates").exists():
            raise WorkspaceError("任务仓库不能共享外部 Git 对象库")

    def inspect(self, workspace_id):
        row = self._row(workspace_id)
        _, clone, worktree = self._paths(workspace_id)
        row.update(worktree_path=str(worktree), repository_path=str(clone))
        if row["state"] == "READY":
            self._verify(workspace_id)
            row["head_sha"] = git(worktree, "rev-parse", "HEAD")
            row["changes"] = git(worktree, "status", "--porcelain=v1", "--untracked-files=all")
            row["diff_stat"] = git(
                worktree, "diff", "--no-ext-diff", "--no-textconv", "--stat", row["base_sha"], "--"
            )
        return row

    def list(self):
        with self.db() as db:
            return [dict(r) for r in db.execute("SELECT * FROM workspace ORDER BY created_at DESC")]

    def recover(self, workspace_id):
        row = self._row(workspace_id)
        folder, _, _ = self._paths(workspace_id)
        folder.mkdir(exist_ok=True)
        with exclusive(self._guard(folder / "operation.lock")):
            row = self._row(workspace_id)
            if row["state"] == "PREPARING":
                self._state(workspace_id, "FAILED", "PREPARATION_INTERRUPTED")
        return self.inspect(workspace_id)

    def remove(self, workspace_id):
        row = self._row(workspace_id)
        folder, clone, worktree = self._paths(workspace_id)
        if row["state"] == "REMOVED":
            return self.inspect(workspace_id)
        if row["state"] != "READY":
            raise WorkspaceError("只允许移除 READY 工作区；失败诊断目录保留供人工检查")
        with exclusive(self._guard(folder / "operation.lock")):
            row = self._row(workspace_id)
            if row["state"] == "REMOVED":
                return self.inspect(workspace_id)
            self._verify(workspace_id)
            # Validate the resolved target and every junction before Git performs recursive removal.
            for current, dirs, files in os.walk(worktree, followlinks=False):
                for name in dirs + files:
                    self._guard(Path(current) / name)
            if git(worktree, "status", "--porcelain=v1", "--untracked-files=all", "--ignored"):
                raise WorkspaceError("工作区存在改动、未跟踪或忽略文件，拒绝移除")
            if git(worktree, "rev-parse", "HEAD") != row["base_sha"]:
                raise WorkspaceError("工作区存在新提交，拒绝移除；请先交付成果")
            git(clone, "worktree", "remove", str(self._guard(worktree)))
            self._state(workspace_id, "REMOVED")
        return self.inspect(workspace_id)


def output(value):
    print(json.dumps(value, ensure_ascii=False, indent=2))
