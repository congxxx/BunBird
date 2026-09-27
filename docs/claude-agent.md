# Claude Code 接入飞书

## 看板中的模型选择与映射

在「账号与渠道 → 编辑 / 添加账号」填写接口地址和密钥，点击「获取可用模型」。编辑已保存的账号会自动查询，密钥留空时由后端使用加密保存的原密钥。修改接口地址后必须重新填写密钥，查询不跟随重定向；模型列表不会写入配置，渠道不支持查询时仍可手动填写模型 ID。

选择「默认会话模型」后，可分别指定 Haiku、Sonnet、Opus、Fable 的模型 ID。映射通过 `ANTHROPIC_DEFAULT_*_MODEL` 传给每次启动的 Claude Code，留空项沿用默认会话模型；`--model` 与 `ANTHROPIC_MODEL` 使用默认会话模型。映射保存在账号中，下一次执行或账号切换时生效，不改变正在执行的进程，也不修改本机全局 Claude Code 配置。映射用于 Claude Code 的模型别名，并不等于按任务类型自动选择模型。

DeepSeek 的 Anthropic 接口地址为 `https://api.deepseek.com/anthropic`，模型列表使用同域的 `/models`。通用渠道会尝试 `/v1/models`、`/models`，对于 Anthropic 兼容路径会尝试去掉已知后缀后的同域列表接口，仅在 404/405 时继续尝试。返回列表仅说明模型存在，不保证所有模型都支持代码工具调用或图片输入。

实现参考：[cc-switch 模型发现](https://github.com/farion1231/cc-switch/blob/main/src-tauri/src/services/model_fetch.rs)、[DeepSeek 模型列表](https://api-docs.deepseek.com/api/list-models/)、[Claude Code 模型映射](https://code.claude.com/docs/en/model-config)。

设置 `AGENT_EXECUTOR=claude` 后，飞书的普通文字任务直接交给 Claude Code。它可以读取文件、编辑代码、运行命令、拉取仓库和执行检查；不再通过旧工作区流程的固定语句匹配、仓库登记或 `WORKSPACE_GIT_HOSTS` 校验。`/help`、`/status`、`/cancel` 仍由平台处理。执行器使用官方 [Claude Code 非交互 CLI](https://code.claude.com/docs/en/headless)。

Worker 主机需要安装 Claude Code，并在 `.env` 中设置 `MODEL_MODE=anthropic`、`MODEL_BASE_URL`、`MODEL_NAME`、`MODEL_API_KEY`。当前本机沿用 MiniMax 的 Anthropic 兼容接口和 MiniMax-M3。模型凭据由启动器传给子进程，不写入代码仓库。需要指定 CLI 路径时配置 `CLAUDE_CODE_EXECUTABLE`。修改配置后重启 Worker。

每个企业、飞书话题和发送者组合对应一个持久 Claude Code 会话。首次运行创建独立 Git worktree；后续在同一话题中由同一人发送消息，使用 `--resume` 续接并保留文件。新话题或另一位发送者使用新会话；群聊请在机器人的话题内继续回复。旧版本的模型聊天记录不会自动迁移到新 Agent 会话。

可以直接发送“拉取这个项目 https://主机/组织/仓库.git，先分析结构，不要提交或推送”，随后继续说“修改登录校验，并运行相关测试”。私有仓库可以在同一会话提供必要认证信息，Agent 根据实际提示处理；不要求将每个仓库的令牌登记到 `.env`，也不要求把令牌写入项目。消息和 Agent 会话记录保存在本机数据目录，不能将这些目录作为公开交付物。

收到任务后只通过表情确认，不发送“已受理，任务……”文字。飞书仅在会话首次初始化时展示 Claude Code Session ID、模型和工作目录，续接时不重复发送启动卡片。每轮使用同一张“思考与进展”卡片，逐步显示 Agent 面向用户撰写的阶段摘要：已确认的事实、下一步行动及待确认事项。摘要通过公开 text 中的 progress 标记识别，并按流式文本增量更新；新阶段替换旧摘要，不再堆叠固定工具调用和耗时提示。内部 thinking、工具参数和工具原始输出不会转发到飞书。

完成后先把进度卡片更新为绿色“本回合已完成，最终结论见下方消息”，再单独发送普通 Markdown 富文本结果消息，支持加粗、列表和代码块；不使用结果卡片，也不附加任务编号。连接器在独立 SQLite 文件中保存卡片消息 ID，重启后继续更新原消息；关闭的卡片不会被旧进度覆盖。卡片使用共享更新设置，需要机器人具有更新自己发送的卡片消息的权限。更新失败按原有发送队列规则重试。

工具权限拒绝、CLI 失败或缺少结果时，任务会记录为失败，进度卡片显示未完成；取消时显示取消。`/cancel 任务ID` 或执行租约失效会终止 Agent 进程树，已经写入的文件保留。单次任务最长约 15 分钟，超过时限后可在原话题继续。

执行使用 `--bare`，不自动加载宿主的插件、MCP、hooks 或项目配置；明确开放 Read、Write、Edit、Glob、Grep、Bash 工具，并采用 `dontAsk`，未获允许的工具会拒绝。没有使用 `--dangerously-skip-permissions`。默认提示要求不提交或推送，只有用户明确要求才执行。

这仍是可信单租户本机执行：Git worktree 隔离文件和 Git 元数据，不是操作系统沙箱，Bash 具有运行账号的权限。生产环境应使用专用受限账号或容器，并限制飞书接入人员。当前 Compose 镜像不包含 Claude Code，不能仅切换环境变量就获得该执行器；本次验证针对 Windows 本机服务。

`AGENT_SESSION_ROOT` 默认是项目的 `data/agent-sessions`。该目录保存工作区和会话状态，已由 `data/` 的 Git 忽略规则覆盖。切回 `AGENT_EXECUTOR=model` 可恢复旧文字模型与固定工作区流程。
