from pathlib import Path

import pytest

from bunbird.workspaces import WorkspaceError, Workspaces, exclusive, git


@pytest.fixture
def setup(tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    git(source, "init", "-b", "main")
    (source / "file.txt").write_text("baseline")
    (source / ".gitignore").write_text("ignored.txt\n")
    git(source, "add", ".")
    commit(source)
    manager = Workspaces(tmp_path / "spaces", "tenant-a")
    manager.register("repo", source, "main")
    return manager, source


def commit(path):
    git(
        path,
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.invalid",
        "commit",
        "-m",
        "test",
    )


def test_actual_worktrees_are_independent_and_idempotent(setup):
    manager, source = setup
    (source / "file.txt").write_text("uncommitted source")
    (source / "secret.txt").write_text("local only")
    first = manager.create("repo", "task-1")
    second = manager.create("repo", "task-2")
    a, b = Path(first["worktree_path"]), Path(second["worktree_path"])
    assert (a / ".git").is_file()
    assert (a / "file.txt").read_text() == "baseline"
    assert not (a / "secret.txt").exists()
    assert first["base_sha"] == git(source, "rev-parse", "main")
    assert first["repository_path"] != second["repository_path"]
    assert manager.create("repo", "task-1")["id"] == first["id"]
    (a / "file.txt").write_text("task change")
    assert (b / "file.txt").read_text() == "baseline"
    assert (source / "file.txt").read_text() == "uncommitted source"
    assert "file.txt" in manager.inspect(first["id"])["changes"]
    assert not (Path(first["repository_path"]) / ".git/objects/info/alternates").exists()


@pytest.mark.parametrize("change", ["tracked", "untracked", "ignored", "commit"])
def test_remove_preserves_work(setup, change):
    manager, source = setup
    row = manager.create("repo", "task")
    path = Path(row["worktree_path"])
    name = {
        "tracked": "file.txt",
        "untracked": "new.txt",
        "ignored": "ignored.txt",
        "commit": "file.txt",
    }[change]
    (path / name).write_text("valuable work")
    if change == "commit":
        git(path, "add", ".")
        commit(path)
    with pytest.raises(WorkspaceError):
        manager.remove(row["id"])
    assert (path / name).exists()
    assert git(source, "rev-parse", "HEAD") == row["base_sha"]


def test_clean_remove_retains_repository(setup):
    manager, _ = setup
    row = manager.create("repo", "task")
    assert manager.remove(row["id"])["state"] == "REMOVED"
    assert not Path(row["worktree_path"]).exists()
    assert Path(row["repository_path"]).exists()
    assert manager.remove(row["id"])["state"] == "REMOVED"


def test_failed_creation_has_no_fallback_and_can_retry(setup, monkeypatch):
    manager, source = setup
    import bunbird.workspaces as module

    original = module.git

    def fail(directory, *args, **kwargs):
        if args[:2] == ("worktree", "add"):
            raise WorkspaceError("injected failure")
        return original(directory, *args, **kwargs)

    monkeypatch.setattr(module, "git", fail)
    with pytest.raises(WorkspaceError):
        manager.create("repo", "task")
    row = manager.list()[0]
    assert row["state"] == "FAILED"
    assert (source / "file.txt").read_text() == "baseline"
    with pytest.raises(WorkspaceError):
        manager.create("repo", "task")
    monkeypatch.setattr(module, "git", original)
    assert manager.create("repo", "task", 2)["state"] == "READY"


def test_allowlist_tenant_and_path_validation(setup):
    manager, _ = setup
    with pytest.raises(WorkspaceError):
        manager.create("unknown", "task")
    with pytest.raises(WorkspaceError):
        manager.create("repo", "../escape")
    row = manager.create("repo", "task")
    other = Workspaces(manager.root.parent, "tenant-b")
    with pytest.raises(WorkspaceError):
        other.inspect(row["id"])


def test_tampered_git_pointer_cannot_remove_source(setup):
    manager, source = setup
    row = manager.create("repo", "task")
    # Windows marks .git hidden, so replace its contents without CREATE_ALWAYS.
    with (Path(row["worktree_path"]) / ".git").open("r+") as pointer:
        pointer.write(f"gitdir: {(source / '.git').as_posix()}\n")
        pointer.truncate()
    with pytest.raises(WorkspaceError):
        manager.remove(row["id"])
    assert (source / "file.txt").exists()


def test_recovery_requires_exclusive_lock(setup):
    manager, _ = setup
    row = manager.create("repo", "task")
    manager._state(row["id"], "PREPARING")
    folder, _, _ = manager._paths(row["id"])
    with exclusive(folder / "operation.lock"):
        with pytest.raises(WorkspaceError):
            manager.recover(row["id"])
    assert manager.recover(row["id"])["state"] == "FAILED"


def test_submodule_tree_is_rejected_before_checkout(setup):
    manager, source = setup
    sha = git(source, "rev-parse", "HEAD")
    git(source, "update-index", "--add", "--cacheinfo", f"160000,{sha},nested")
    commit(source)
    with pytest.raises(WorkspaceError, match="子模块"):
        manager.create("repo", "task")
    row = manager.inspect(manager.list()[0]["id"])
    assert row["state"] == "FAILED"
    assert not Path(row["worktree_path"]).exists()


def test_duplicate_create_during_preparation_is_not_provisioned_twice(setup, monkeypatch):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Event

    import bunbird.workspaces as module

    manager, _ = setup
    reached, release = Event(), Event()
    original = module.git

    def pause(directory, *args, **kwargs):
        if args[0] == "clone":
            reached.set()
            assert release.wait(10)
        return original(directory, *args, **kwargs)

    monkeypatch.setattr(module, "git", pause)
    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(manager.create, "repo", "task")
        try:
            assert reached.wait(10)
            with pytest.raises(WorkspaceError):
                manager.create("repo", "task")
            with pytest.raises(WorkspaceError):
                manager.recover(manager.list()[0]["id"])
        finally:
            release.set()
        row = future.result()
    assert len(manager.list()) == 1
    assert manager.create("repo", "task")["id"] == row["id"]
