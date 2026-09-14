import os
from dataclasses import dataclass

from dotenv import load_dotenv


def required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ValueError(f"请配置 {name}")
    return value


@dataclass(frozen=True)
class ControlConfig:
    url: str
    token: str

    @classmethod
    def load(cls, role: str):
        load_dotenv()
        return cls(
            os.getenv("BUNBIRD_CONTROL_URL", "http://127.0.0.1:8080").rstrip("/"),
            required(f"BUNBIRD_{role.upper()}_TOKEN"),
        )


@dataclass(frozen=True)
class FeishuConfig:
    app_id: str
    app_secret: str
    tenant: str
    domain: str
    allowed_chats: frozenset[str]
    spool_path: str

    @classmethod
    def load(cls):
        load_dotenv()
        domain = os.getenv("FEISHU_DOMAIN", "https://open.feishu.cn").rstrip("/")
        if domain not in {"https://open.feishu.cn", "https://open.larksuite.com"}:
            raise ValueError("FEISHU_DOMAIN 必须是飞书或 Lark 官方域名")
        return cls(
            required("FEISHU_APP_ID"),
            required("FEISHU_APP_SECRET"),
            required("FEISHU_TENANT_KEY"),
            domain,
            frozenset(
                x.strip() for x in os.getenv("FEISHU_ALLOWED_CHAT_IDS", "").split(",") if x.strip()
            ),
            os.getenv("FEISHU_SPOOL_PATH", "data/feishu-inbox.db"),
        )
