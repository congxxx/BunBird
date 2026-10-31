"""Per-turn presentation preferences shared by both executors."""


def reply_style(job):
    return {
        "concise": "回复简洁，先给结论，再给必要的说明；避免冗长铺垫。",
        "detailed": "详细说明结论、依据和必要的步骤，使用清晰分段和列表。",
        "professional": "使用正式专业语气，结构清晰，客观陈述结论和证据，避免口语及表情。",
        "friendly": "使用自然友好的语气，易于理解，适度使用表情，保持准确。",
    }.get(job.get("replyStyle", "concise"), "")
