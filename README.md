# BunBird 最小版

根据《BunBird-智能体平台建设方案-v0.1.md》落地的飞书文字对话闭环。保持 **Java 21 / Spring Boot 管理服务 + Python Worker** 的边界，使用飞书官方 `lark-oapi` SDK 长连接接收事件，不需要公网地址、域名或回调服务器。当前是单企业、单应用试点版本。

支持私聊、群内无需 @即可触发、按会话/话题保存上下文、回复原消息、`/help`、`/status [任务ID]`、`/cancel [任务ID]`。任务创建和回复队列持久化，重复事件不会重复生成任务；Worker 失联和执行失败有明确状态，飞书发送失败单独重试。使用阶段卡片展示会话、处理进度和结果，不展示模型内部思维。

已接入真实 Claude Code 执行器，设置 `AGENT_EXECUTOR=claude` 后可在飞书中直接用自然语言要求拉取项目、修改代码和运行检查，同一话题由同一人继续发送消息可恢复 Agent 会话。沿用 MiniMax Anthropic 接口配置，详见 [Claude Code 接入说明](docs/claude-agent.md)。

## 快速启动

需要 Python 3.12+、uv，以及 JDK 21+ / Maven，或可用的 Docker Compose。Windows 如默认仍为 Java 8，请先将当前终端的 `JAVA_HOME`、`PATH` 指向 JDK 21 或更高版本。

```powershell
uv sync --frozen
uv run python scripts/init_env.py
```

打开根目录 `.env`，填写 `FEISHU_APP_ID`、`FEISHU_APP_SECRET`、`FEISHU_TENANT_KEY`。内部三个不同密钥及数据库密码已由初始化脚本随机生成。脚本不会覆盖已有 `.env`，该文件已加入忽略规则。飞书应用的权限、事件订阅和发布步骤见 [飞书接入说明](docs/feishu-setup.md)。

初次联通可以保持 `MODEL_MODE=mock`，所有回答都会标注“演示模式，未调用真实模型”。真实回答需要改为 `MODEL_MODE=compatible`，并填写 `MODEL_BASE_URL`（以 `/v1` 等服务端要求的 API 前缀结尾）、`MODEL_NAME`、`MODEL_API_KEY`。Worker 向该地址下的 `/chat/completions` 发送非流式聊天请求，适配支持此协议的模型服务；不会自动替你选择服务商或上传资料。

### 本地启动：三个终端

在项目根目录构建并运行管理服务：

```powershell
mvn -f backend/pom.xml package
uv run python scripts/start_control.py
```

第二个终端启动 Worker，第三个终端启动飞书连接器：

```powershell
uv run bunbird worker
```

```powershell
uv run bunbird bridge
```

本地管理服务绑定 `127.0.0.1:8080`，使用 `data/` 中的 H2 文件数据库；飞书连接器也在 `data/` 中保存尚未交给管理服务的事件。`scripts/start_control.py` 自动读取 `.env`，直接运行 Java 时则需自行传入环境变量。访问 `http://127.0.0.1:8080/health` 可检查服务是否启动，其他接口都要求相应密钥。

### Compose 启动

```powershell
docker compose up --build -d
docker compose logs -f control worker bridge
```

Compose 使用 PostgreSQL、Java 管理服务、Python Worker 和飞书连接器四个服务，数据库和收件队列使用独立持久卷。宿主仅开放本地管理端口；Worker 只得到 Worker 密钥和模型凭据，飞书凭据仅交给连接器。停止使用 `docker compose down`；`down -v` 会删除持久卷，不要用于普通重启。

## 飞书中使用

发布应用并将机器人加入群后，私聊直接发送文字，群内也直接发送文字。例如“@BunBird 帮我写一个项目周报提纲”。机器人会在群消息下创建飞书可见话题，先在话题内回复任务 ID，完成后把答案发送到同一话题。私聊仍是普通回复。群里回复同一根消息可以继续该话题；私聊默认延续当前用户与机器人的会话，最近六个成功轮次在字符预算内进入上下文。

`/status` 查询当前话题中自己的最新任务；`/status 完整任务ID` 可查询自己在当前聊天中发起的指定任务。取消同理。群中使用命令也无需 @机器人。无权访问、ID 不存在或跨聊天时统一提示未找到任务。管理员可用本地 CLI 查看全部试点任务：

```powershell
uv run bunbird tasks
uv run bunbird inspect <完整任务ID>
uv run bunbird cancel <完整任务ID>
```

## MiniMax / Anthropic 协议

MiniMax 的 Anthropic 兼容接口使用 `MODEL_MODE=anthropic`、`MODEL_BASE_URL=https://api.minimaxi.com/anthropic`、`MODEL_NAME=MiniMax-M3`，密钥只填写在本地 `.env` 的 `MODEL_API_KEY`。程序调用 `/v1/messages`，只提取正文文本，不发送模型返回的思考块。其他支持聊天补全的服务仍使用 `MODEL_MODE=compatible`。修改后重启 Worker。

CC Switch 中的模型别名、`[1M]` 后缀和 Claude Code 专属开关不直接传给模型接口；本版固定使用配置的一个模型，并保留原任务时限和上下文预算。

## 消息状态表情

机器人会在你发出的原消息上依次显示“思考”（等待模型生成）、“敲键盘”（准备发送答案）、“完成”（成功答案已发送）。失败和取消只清除处理中表情，不显示完成；不会读取或展示模型私有推理。模拟模式执行较快，中间表情可能很短暂。

需要应用身份权限 `im:message.reactions:read` 和 `im:message.reactions:write_only`。表情请求使用独立持久队列，最多尝试五次；旧阶段重试会被新阶段替代，只移除本机器人添加的状态表情，不影响其他成员的表情，也不会因为表情失败重跑任务。表情队列文件位于飞书收件队列文件旁，备份时一并保留。

## 网页用量看板

启动 Java 服务后访问 `http://127.0.0.1:8080/dashboard.html`，使用本地 `.env` 中的 `BUNBIRD_ADMIN_TOKEN` 登录。凭据只保存在当前页面内存中，刷新页面后需重新输入，不写入浏览器存储。页面每 15 秒刷新，提供最近 7／30／90 天的已记录 Token、输入输出、缓存读取写入、每日趋势、模型分布和最近 100 条任务。后端接口为管理员专用的 `GET /api/dashboard?days=30`，不会返回任务正文、答案或密钥。

用量来自任务完成时收到的服务商计数，未返回计数的任务标为“未上报”；中断或失败任务仍可能已经产生费用。本页不能代替完整账单。Anthropic 输入计数与缓存分开累计，OpenAI 兼容格式的缓存读取计数包含在输入中，不重复加入总量。账号区域目前仅展示环境中的模型与执行器配置，不代表已验证账号可用性，已为 MiniMax 中国站和国际站接入官方 Token Plan 额度查询，显示短周期、周窗口剩余百分比和重置时间（窗口长度按接口时间计算，不固定假定 5 小时）。服务商接口每分钟最多请求一次，失败时上次数据明确标为过期；套餐档位、Token 总额与费用未返回则不推算。其他渠道和 Claude／ChatGPT 订阅额度尚未接入。未实现订阅额度耗尽后的自动换号。

浏览器回归测试为 `node scripts/dashboard_smoke.cjs`，需要可解析的 Playwright 包及本机 Edge；测试使用模拟数据，不调用收费模型或飞书。

## 验证命令

```powershell
mvn -f backend/pom.xml test
uv run pytest -q
uv run ruff check bunbird tests scripts
uv run python scripts/smoke_test.py
```

最后一项需要先打包 Java 服务。它会在临时目录和随机本地端口启动真正的 Java/Python 进程，检查事件补交、十次重放去重、数据库重启恢复、模型 HTTP 请求和飞书发送请求结构；外部模型和飞书发送端使用本地测试替身，不发送真实消息、不消耗模型额度。当前验证证据与限制见 [验证记录](docs/validation.md)。

## 最小版与原方案的对应关系

这次实现的是阶段 0 中的消息接入与文字执行闭环，不代表原方案的整个阶段 1 已完成。为减少依赖，任务直接以数据库表作为持久队列，回复使用同事务 outbox，暂不引入 Redis。Task 与单次 Run 在本版合并，每个任务只允许一次执行租约；失败重新发消息创建新任务，不提供伪装成断点恢复的自动重跑。

管理入口先提供鉴权 API 和 CLI，暂不建设 React 后台。`model` 模式提供文字对话和固定工作区流程，详见 [工作空间使用说明](docs/workspaces.md)；`claude` 模式提供本机 Claude Code 的文件和 Bash 工具执行。当前未建设操作系统沙箱、PR 审批流程、附件、流式卡片、知识库、技能市场、RBAC 管理页或办公写入。Agent 子进程不继承飞书和平台内部令牌，但仍以 Worker 的本机账号运行，适用于可信单租户试点。

内部接口与状态语义见 [接口说明](docs/api.md)。此版本固定一个企业和应用，包含租户字段和来源校验，但不是已完成的多租户平台。管理员密钥拥有试点全局读取/取消权限，普通飞书用户只通过平台事件操作自己的任务。

## 目录与依赖

`backend/` 为 Java 管理服务与数据库迁移，`bunbird/` 为 Python 连接器和 Worker，`tests/` 为 Python 测试，`scripts/` 为配置、启动与跨进程验证脚本，`docs/` 为接入和验证材料。Python 依赖通过 `uv.lock` 固定，Java 依赖由固定 Spring Boot 3.5.16 BOM 和 Maven 配置管理。

飞书适配复用官方 [larksuite/oapi-sdk-python](https://github.com/larksuite/oapi-sdk-python)，固定 `lark-oapi==1.7.3`（MIT）；Spring Boot、HTTPX、H2、PostgreSQL 等保持各自许可证。任务状态、权限边界、持久收件队列、通知 outbox 与 Worker 协议由本项目实现。本版未复制 cc-connect、OpenHands 或 SkillHub 源码，也未声称已经接入这些项目。


## 新消息中断当前任务

同一用户在同一群话题或私聊内发送新的任务消息，会中断正在执行的旧任务，并替换尚未执行的旧消息；连续发送时以最后一条为准。群内另起话题仍是独立会话，其他用户的任务不受影响，`/status`、`/help` 等命令不会触发替换。工作区与 Claude 会话保留，已经发生的文件修改不会自动回滚。

控制服务接收新消息后立即拒绝旧任务的进展和结果，Worker 每 250 毫秒检查取消状态，终止旧 Agent 进程树后才释放会话并领取新任务。若 Worker 失联，新任务等待原租约到期后可领取。旧进展卡片标记为被新消息中断，不额外发送取消消息；新任务继续使用状态表情、进展摘要和普通 Markdown 结果消息。

MiniMax 额度数据源：[官方 Token Plan 查询说明](https://platform.minimaxi.com/subscribe/token-plan)。后台仅访问与当前模型 API 域名对应的官方额度地址，使用现有 `MODEL_API_KEY`，无需浏览器 Cookie。管理员接口为 `GET /api/quota`，密钥与原始服务商错误信息不会返回页面。


## 额度不足时等待与恢复

MiniMax 新任务领取前会检查官方套餐额度（复用最长 60 秒缓存）。通用模型的短周期或周窗口剩余为 0 时，任务进入 `WAITING_QUOTA`，尚不启动 Agent。飞书只发送一次等待说明，暂停原因和预计重置时间保存在数据库，看板中同步展示。多个窗口都耗尽时，以最晚的已知重置时间作为参考；缺少重置时间时显示待确认。只有查询确认额度已恢复才执行，不以本机到点作为恢复依据。

等待状态可跨服务重启保留，不受执行租约超时影响。同一用户在同一会话发送新消息会替换等待中的旧任务，`/cancel` 也可取消。运行中的任务不因额度监测而中断、回滚或自动重跑，运行中遇到服务商拒绝仍明确报告失败。额度查询失败不是额度耗尽：尚未暂停的新任务可尝试正常执行；已经因耗尽暂停的任务继续等待，原因改为“暂时无法确认额度”。未接入额度查询的其他渠道沿用正常执行流程。恢复依赖控制服务与 Worker 持续运行，飞书通知另需 Bridge 在线。


## 会话并发与排队

默认最多同时执行 3 个会话。Python Worker 使用三个独立执行循环，管理服务通过数据库调度锁在多个 Worker 之间统一限制并发；同一话题仍串行执行。并发占满时，等待任务会在飞书收到一次“⏳ 会话排队中…（最多 3 个并发）”，无需重新发送消息，有空位后自动领取。排队通知未发出前任务已启动时，丢弃这条过期提示。

同一用户在同一话题发送新消息仍会替换旧任务，包括排队中的任务。正在停止的旧任务在进程清理确认或租约到期前仍占据名额。额度耗尽的任务进入独立的等待额度状态，未开始执行时不占用并发名额。
