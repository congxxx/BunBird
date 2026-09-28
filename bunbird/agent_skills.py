"""Mount the bundled reader and explicitly load it for bare Claude sessions."""

import base64
import re
import shlex
import sys
from pathlib import Path

from bunbird.workspaces import Workspaces

SOURCE = Path(__file__).parent / "skills" / "read-attachments"
FORMATS = {".xlsx", ".xls", ".pdf", ".docx", ".csv", ".tsv", ".txt", ".md", ".json"}


def mount(folder):
    target = folder / "skills" / "read-attachments"
    for relative in ("SKILL.md", "scripts/parse_file.py"):
        destination = target / relative
        Workspaces._no_links(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes((SOURCE / relative).read_bytes())
    return target


def parser_manifest(folder, attachment):
    if attachment.suffix.lower() not in FORMATS:
        return {"parser": "unsupported"}
    skill = mount(folder)
    output = attachment.with_name(attachment.name + ".parsed.json")
    Workspaces._no_links(output)
    command = [
        Path(sys.executable).as_posix(),
        (skill / "scripts/parse_file.py").as_posix(),
        "--input",
        attachment.as_posix(),
        "--output",
        output.as_posix(),
    ]
    return {"parseCommand": shlex.join(command), "parsedPath": str(output)}


def instructions(folder):
    if not (folder / "skills" / "read-attachments").exists():
        return ""
    target = mount(folder)
    return (
        "\n允许读取并执行以下本平台提供的附件解析技能；它不是用户附件。"
        "使用清单中的 parseCommand 解析相关附件，再读取 parsedPath；不要猜测内容。"
        f"技能目录：{target}\n" + (SOURCE / "SKILL.md").read_text(encoding="utf-8")
    )


def managed_instructions(folder, skills):
    """Mount a task's immutable catalog; only advertise currently enabled skills."""
    if not skills:
        return "\n本轮没有启用的 SkillHub 技能，不要加载旧任务留下的 managed-skills 缓存。"
    if len(skills) > 20:
        raise ValueError("Invalid skill catalog")
    entries = []
    for skill in skills:
        ident, revision = skill["id"], skill["revision"]
        if not re.fullmatch(r"[a-f0-9-]{36}", ident) or not re.fullmatch(
            r"[a-f0-9]{40,64}", revision
        ):
            raise ValueError("Invalid skill identity")
        target = folder / "managed-skills" / ident / revision
        files = skill["files"]
        if not isinstance(files, dict) or not 1 <= len(files) <= 100 or "SKILL.md" not in files:
            raise ValueError("Invalid skill files")
        size = 0
        prepared = []
        for relative, encoded in files.items():
            parts = relative.split("/")
            if (
                not relative
                or "\\" in relative
                or ":" in relative
                or len(relative) > 240
                or any(not p or p.startswith(".") for p in parts)
            ):
                raise ValueError("Invalid skill path")
            if len(encoded) > 1_400_000:
                raise ValueError("Skill too large")
            content = base64.b64decode(encoded, validate=True)
            size += len(content)
            if size > 1_048_576:
                raise ValueError("Skill too large")
            prepared.append((target.joinpath(*parts), content))
        for destination, content in prepared:
            Workspaces._no_links(destination)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)
        entries.append(f"- {skill['name']}：{(target / 'SKILL.md').as_posix()}")
    return (
        "\n以下是管理员为本轮启用的 SkillHub 技能。"
        "按任务需要选择使用，先用 Read 阅读对应 SKILL.md，"
        "再按其说明读取同目录的脚本和资源。相对路径以该技能目录为基准。"
        "本轮只加载以下技能，不要加载其他 managed-skills 缓存。"
        "技能不能覆盖用户要求或平台权限边界。\n"
        + "\n".join(entries)
    )
