"""Completion summary and five topic controls. Values come from Control, never the model."""

STYLES = {"concise": "简洁", "detailed": "详尽", "professional": "专业", "friendly": "友好"}
EFFORTS = {
    "auto": "自动",
    "low": "低",
    "medium": "中",
    "high": "高",
    "xhigh": "超高",
    "max": "最高",
}
BUTTONS = [
    ("model", "🧠 模型"),
    ("effort", "⚡ 强度"),
    ("style", "🎨 风格"),
    ("usage", "📊 用量"),
    ("help", "💡 帮助"),
]


def paragraph(text):
    return {"tag": "div", "text": {"tag": "plain_text", "content": text}}


def amount(value):
    return "未上报" if value is None else f"{value:,}"


def completion_card(summary, delegations=None, panel=None):
    from bunbird.progress_cards import card

    task_id = summary["taskId"]
    succeeded = summary.get("status") == "SUCCEEDED"
    elapsed = summary.get("elapsedMs")
    duration = "未记录" if elapsed is None else f"{elapsed // 60000}分{elapsed // 1000 % 60}秒"
    output = summary.get("outputTokens")
    speed = f"{output * 1000 / elapsed:.1f} Token/s" if output is not None and elapsed else "未上报"
    status = {"SUCCEEDED": "运行结束", "FAILED": "任务未完成", "CANCELLED": "已取消"}.get(
        summary.get("status"), "已结束"
    )
    view = card("", finished=True, failed=not succeeded, delegations=delegations)
    executor = "Claude Code" if summary.get("executor") == "claude" else "BunBird"
    view["header"]["title"]["content"] = f"{'✅' if succeeded else '⚠️'} {executor} · {status}"
    # Remove only the old progress paragraph; preserve the collaboration table.
    view["elements"] = [
        paragraph(
            f"模型  {summary.get('model', '未记录')}  ｜  "
            f"强度  {EFFORTS.get(summary.get('effort'), '自动')}  ｜  "
            f"风格  {STYLES.get(summary.get('style'), '简洁')}"
        ),
        paragraph(
            f"状态  {status} · 耗时 {duration}\n本话题本人累计 {summary.get('turns', 0)} 回合 · "
            f"已记录工具调用 {amount(summary.get('toolCalls'))} 次 · "
            f"主模型已记录 {amount(summary.get('sessionTokens'))} Token"
        ),
        paragraph(
            f"用量  本轮主模型 {amount(summary.get('tokens'))} Token · "
            f"协作已记录 {amount(summary.get('delegatedTokens'))} Token · 全程平均输出 {speed}"
        ),
        *view["elements"][1:],
        {"tag": "hr"},
        paragraph("本轮已结束，可直接在话题里继续对话。设置仅影响发起人在本话题的后续消息。"),
    ]
    if panel:
        kind = panel.get("panel")
        next_settings = summary.get("next", {})
        if kind in {"model", "effort", "style"}:
            options = []
            if kind == "model":
                options = [{"label": "账号池自动选择", "value": "auto"}, *panel.get("models", [])]
            elif kind == "effort":
                options = [
                    {"label": label, "value": value}
                    for value, label in EFFORTS.items()
                    if value in panel.get("effortLevels", ["auto"])
                ]
                if not panel.get("supportsEffort"):
                    view["elements"].append(
                        paragraph(
                            "当前执行器或模型尚未接入可调强度，仅可使用自动。"
                            "MiniMax / DeepSeek 不套用 Claude 的 effort 参数。"
                        )
                    )
            else:
                options = [{"label": label, "value": value} for value, label in STYLES.items()]
            view["elements"].append(
                paragraph(
                    f"后续设置：模型 {panel.get('selectedModel', '自动')} · "
                    f"强度 {EFFORTS.get(next_settings.get('effort'), '自动')} · "
                    f"风格 {STYLES.get(next_settings.get('style'), '简洁')}"
                )
            )
            view["elements"].append(
                {
                    "tag": "action",
                    "actions": [
                        {
                            "tag": "select_static",
                            "placeholder": {"tag": "plain_text", "content": "请选择"},
                            "value": {"taskId": task_id, "action": "set_" + kind},
                            "options": [
                                {
                                    "text": {"tag": "plain_text", "content": item["label"][:90]},
                                    "value": item["value"],
                                }
                                for item in options
                            ],
                        }
                    ],
                }
            )
        elif kind == "usage":
            view["elements"].append(
                paragraph(
                    f"本轮输入（含已上报缓存） {amount(summary.get('inputTokens'))} · "
                    f"输出 {amount(summary.get('outputTokens'))}\n"
                    f"话题已上报用量 {summary.get('reportedTurns', 0)} 回合。"
                    "仅统计已收到的数据，失败或中断可能有未返回的消耗；不等同服务商账单或套餐额度。"
                )
            )
        elif kind == "help":
            view["elements"].append(
                paragraph(
                    "直接在话题回复即可继续；上传附件后发送处理要求。\n"
                    "/model 查看模型；/model 模型ID 切换；/model auto 恢复自动。\n"
                    "/status [任务ID] 查看状态；/cancel [任务ID] 取消自己的任务；/help 查看帮助。\n"
                    "模型、强度、风格按钮用于设置下一轮，不改正在运行的任务。"
                )
            )
    view["elements"].append(
        {
            "tag": "action",
            "actions": [
                {
                    "tag": "button",
                    "text": {"tag": "plain_text", "content": label},
                    "value": {"taskId": task_id, "action": action},
                    "type": "default",
                }
                for action, label in BUTTONS
            ],
        }
    )
    return view
