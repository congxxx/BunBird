import html
import json
import logging
import re
import threading

import httpx
import lark_oapi as lark
from lark_oapi.api.im.v1 import ReplyMessageRequest, ReplyMessageRequestBody

from bunbird.config import ControlConfig, FeishuConfig
from bunbird.control import client
from bunbird.inbox import Inbox
from bunbird.reactions import Reactions

log = logging.getLogger(__name__)


class SdkConnectionLogFilter(logging.Filter):
    """Expose connection status without logging credential-bearing WebSocket URLs."""

    def filter(self, record):
        message = record.getMessage()
        for marker, status in (
            ("disconnected to ", "飞书长连接已断开"),
            ("connected to ", "飞书长连接已建立"),
            ("connect failed", "飞书长连接建立失败"),
        ):
            if marker in message:
                record.msg, record.args = status, ()
                return True
        return False


def normalize(payload: dict, config: FeishuConfig, bot_open_id: str) -> dict | None:
    """Consume user messages in allowed chats, including groups without a bot mention."""
    header, event = payload.get("header", {}), payload.get("event", {})
    if (
        header.get("event_type") != "im.message.receive_v1"
        or header.get("app_id") != config.app_id
        or header.get("tenant_key") != config.tenant
    ):
        return None
    sender, message = event.get("sender", {}), event.get("message", {})
    if sender.get("sender_type") != "user" or sender.get("tenant_key") != config.tenant:
        return None
    chat_id, sender_id = message.get("chat_id"), sender.get("sender_id", {}).get("open_id")
    if config.allowed_chats and chat_id not in config.allowed_chats:
        return None
    if message.get("chat_type") not in {"p2p", "group"}:
        return None
    mentions = message.get("mentions") or []
    bot_mentions = [m for m in mentions if m.get("id", {}).get("open_id") == bot_open_id]
    if not all([header.get("event_id"), message.get("message_id"), chat_id, sender_id]):
        return None
    if message.get("message_type") != "text":
        text = "/unsupported"
    else:
        try:
            text = json.loads(message.get("content", "{}")).get("text", "")
        except (ValueError, AttributeError):
            text = "/unsupported"
        if not isinstance(text, str):
            text = "/unsupported"
        for mention in bot_mentions:
            if mention.get("key"):
                text = text.replace(mention["key"], "")
        # Preserve other mentions as human-readable data, never as recipients/actions.
        for mention in mentions:
            if mention not in bot_mentions and mention.get("key"):
                text = text.replace(mention["key"], "@" + mention.get("name", "用户"))
        text = text.strip() or "/help"
        if len(text) > 12000:
            text = "/too-long"
    topic_key = (
        f"dm:{sender_id}"
        if message["chat_type"] == "p2p"
        else message.get("root_id") or message["message_id"]
    )
    return {
        "tenantId": config.tenant,
        "appId": config.app_id,
        "eventId": header["event_id"],
        "messageId": message["message_id"],
        "chatId": chat_id,
        "chatType": message["chat_type"],
        "senderId": sender_id,
        "topicKey": topic_key,
        "text": text,
    }


def build_reply(delivery: dict) -> ReplyMessageRequest:
    styles = {
        "STARTED": ("🤖 BunBird 会话已启动", "blue"),
        "WORKSPACE": ("⌨️ 处理进度", "blue"),
        "SUCCEEDED": ("✅ 完成", "green"),
        "FAILED": ("任务未完成", "red"),
        "CANCELLED": ("任务已取消", "grey"),
    }
    style = styles.get(delivery.get("taskState"))
    content = {"text": delivery["content"]}
    if style:
        content = {
            "header": {"template": style[1], "title": {"tag": "plain_text", "content": style[0]}},
            "elements": [
                {"tag": "div", "text": {"tag": "plain_text", "content": delivery["content"]}}
            ],
        }
        if delivery.get("taskState") == "SUCCEEDED":
            # Render model Markdown, but never turn generated mention tags into notifications.
            markdown = re.sub(
                r"</?at\b[^>]*>",
                lambda match: html.escape(match[0]),
                delivery["content"],
                flags=re.IGNORECASE,
            )
            content = {
                "schema": "2.0",
                "header": content["header"],
                "body": {"elements": [{"tag": "markdown", "content": markdown}]},
            }
    return (
        ReplyMessageRequest.builder()
        .message_id(delivery["replyTo"])
        .request_body(
            ReplyMessageRequestBody.builder()
            .msg_type("interactive" if style else "text")
            .content(json.dumps(content, ensure_ascii=False))
            .uuid(delivery["deliveryId"])
            .reply_in_thread(delivery.get("replyInThread", False))
            .build()
        )
        .build()
    )


class Bridge:
    def __init__(self, config: FeishuConfig, control: ControlConfig):
        self.config = config
        self.http = client(control)
        self.inbox = Inbox(config.spool_path)
        self.sdk = (
            lark.Client.builder()
            .app_id(config.app_id)
            .app_secret(config.app_secret)
            .domain(config.domain)
            .timeout(10)
            .log_level(lark.LogLevel.CRITICAL)
            .build()
        )
        self.stop = threading.Event()

    def bot_id(self) -> str:
        response = self.sdk.request(
            lark.BaseRequest.builder()
            .http_method(lark.HttpMethod.GET)
            .uri("/open-apis/bot/v3/info")
            .token_types({lark.AccessTokenType.TENANT})
            .build()
        )
        if not response.success():
            raise RuntimeError(f"获取机器人身份失败，飞书错误码 {response.code}")
        return json.loads(response.raw.content)["bot"]["open_id"]

    def pump_once(self):
        item = self.inbox.peek()
        if item:
            key, event = item
            # Older on-disk events predate the explicit chat type field.
            event.setdefault("chatType", "p2p" if event["topicKey"].startswith("dm:") else "group")
            response = self.http.post("/internal/bridge/events", json=event)
            response.raise_for_status()
            self.inbox.remove(key)
        response = self.http.post("/internal/bridge/deliveries/claim")
        response.raise_for_status()
        delivery = response.json()
        if not delivery:
            return
        if hasattr(self, "reactions"):
            self.reactions.enqueue(delivery, preparing=True)
            self.reactions.pump_once(delivery["replyTo"])
        try:
            sent = self.sdk.im.v1.message.reply(build_reply(delivery))
            success = sent.success()
            result = {
                "leaseToken": delivery["leaseToken"],
                "success": success,
                "messageId": sent.data.message_id if success and sent.data else None,
                "errorCode": None if success else f"FEISHU_{sent.code}",
            }
        except Exception as error:
            # Do not log raw SDK/HTTP errors: URLs and payloads can include credentials/content.
            log.warning("飞书发送异常，类型=%s", type(error).__name__)
            result = {
                "leaseToken": delivery["leaseToken"],
                "success": False,
                "errorCode": "FEISHU_TRANSPORT",
            }
        if result["success"] and hasattr(self, "reactions"):
            # Persist before acknowledging the delivery; replays reuse its stable ID.
            self.reactions.enqueue(delivery)
        ack = self.http.post(
            f"/internal/bridge/deliveries/{delivery['deliveryId']}/ack", json=result
        )
        ack.raise_for_status()

    def pump(self):
        self.reactions = Reactions(
            self.config.spool_path + ".reactions.db", self.sdk, self.config.app_id, self.bot_open_id
        )
        while not self.stop.is_set():
            try:
                self.pump_once()
            except httpx.HTTPStatusError as error:
                log.warning("管理服务返回 HTTP %s；事件保留在本地队列", error.response.status_code)
            except Exception as error:
                log.warning("飞书转发暂不可用，类型=%s；将自动重试", type(error).__name__)
            self.reactions.pump_once()
            self.stop.wait(0.5)
        self.reactions.close()

    def run(self):
        bot_id = self.bot_id()
        self.bot_open_id = bot_id

        def on_message(data):
            normalized = normalize(json.loads(lark.JSON.marshal(data)), self.config, bot_id)
            if normalized:
                # A failed commit raises, so the SDK returns failure and can retry the event.
                self.inbox.put(normalized)

        handler = (
            lark.EventDispatcherHandler.builder("", "")
            .register_p2_im_message_receive_v1(on_message)
            .build()
        )
        thread = threading.Thread(target=self.pump, name="feishu-relay", daemon=True)
        thread.start()
        log.info("正在连接飞书长连接；机器人身份已验证")
        sdk_logger = logging.getLogger("Lark")
        connection_filter = SdkConnectionLogFilter()
        sdk_logger.addFilter(connection_filter)
        try:
            lark.ws.Client(
                self.config.app_id,
                self.config.app_secret,
                domain=self.config.domain,
                event_handler=handler,
                log_level=lark.LogLevel.INFO,
            ).start()
        finally:
            sdk_logger.setLevel(logging.CRITICAL)
            sdk_logger.removeFilter(connection_filter)
            self.stop.set()
            thread.join(timeout=30)
            if not thread.is_alive():
                self.http.close()
                self.inbox.close()
