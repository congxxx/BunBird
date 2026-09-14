"""Create local configuration without overwriting existing credentials."""

import secrets
from pathlib import Path

root = Path(__file__).resolve().parents[1]
target = root / ".env"
template = (root / ".env.example").read_text(encoding="utf-8")
for key in (
    "BUNBIRD_BRIDGE_TOKEN",
    "BUNBIRD_WORKER_TOKEN",
    "BUNBIRD_ADMIN_TOKEN",
    "DATABASE_PASSWORD",
):
    template = template.replace(f"{key}=\n", f"{key}={secrets.token_urlsafe(32)}\n")
try:
    with target.open("x", encoding="utf-8") as file:
        file.write(template)
except FileExistsError:
    raise SystemExit(".env 已存在，未覆盖。请直接编辑现有文件。") from None
print("已创建 .env 并生成内部密钥。请填写飞书应用信息；不要提交或分享 .env。")
