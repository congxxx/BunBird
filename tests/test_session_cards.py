import json

from bunbird.session_cards import completion_card


def summary():
    return {
        "taskId": "task",
        "executor": "claude",
        "status": "SUCCEEDED",
        "model": "MiniMax-M3",
        "effort": "auto",
        "style": "concise",
        "elapsedMs": 41000,
        "tokens": None,
        "turns": 2,
        "sessionTokens": 100,
        "delegatedTokens": 0,
    }


def test_completion_has_exactly_five_working_controls_and_no_fake_quota():
    view = completion_card(summary())
    assert view["header"]["title"]["content"] == "✅ Claude Code · 运行结束"
    buttons = view["elements"][-1]["actions"]
    assert [b["value"]["action"] for b in buttons] == ["model", "effort", "style", "usage", "help"]
    text = json.dumps(view, ensure_ascii=False)
    assert "0分41秒" in text and "未上报" in text
    assert "计费" not in text and "本周额度" not in text


def test_effort_dropdown_only_uses_server_confirmed_levels():
    view = completion_card(
        summary(), panel={"panel": "effort", "supportsEffort": False, "effortLevels": ["auto"]}
    )
    controls = [element for element in view["elements"] if element.get("tag") == "action"]
    select = controls[0]["actions"][0]
    assert select["value"] == {"taskId": "task", "action": "set_effort"}
    assert [opt["value"] for opt in select["options"]] == ["auto"]
    assert "不套用" in json.dumps(view, ensure_ascii=False)


def test_style_usage_help_panels_are_real_and_keep_all_buttons():
    style = completion_card(summary(), panel={"panel": "style"})
    assert "professional" in json.dumps(style)
    usage = json.dumps(completion_card(summary(), panel={"panel": "usage"}), ensure_ascii=False)
    assert "本轮输入" in usage and "不等同服务商账单" in usage
    help_card = completion_card(summary(), panel={"panel": "help"})
    assert "/cancel" in json.dumps(help_card)
    assert len(help_card["elements"][-1]["actions"]) == 5


def test_plain_api_applies_style_and_uses_its_own_title():
    from bunbird.model import messages

    assert "正式专业" in messages({"prompt": "hello", "replyStyle": "professional"})[0]["content"]
    view = completion_card({**summary(), "executor": "model"})
    assert "BunBird · 运行结束" in view["header"]["title"]["content"]
