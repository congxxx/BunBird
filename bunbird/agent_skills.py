"""Mount the bundled reader and explicitly load it for bare Claude sessions."""

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
