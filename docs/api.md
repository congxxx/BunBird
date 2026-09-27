# 最小版内部协议

V10 增加 `session_model` 会话偏好表，以及任务 `requested_account_id`、`requested_model` 字段。Bridge 的普通 Incoming 事件支持 `/model`、`/model 模型ID`、`/model 账号标识:模型ID`、`/model auto`。命令回复由事务发件箱发送，不创建任务；账号选择接口优先采用任务接收时保存的偏好，降级通知包含实际渠道和模型。租约、取消、附件暂存及工作区身份规则保持一致。

管理员 `POST /api/accounts/models` 接收 `{accountId?, baseUrl, mode, apiKey?}` 并返回 `{models: ["model-id"]}`。编辑账号时可不传密钥，仅当地址与已保存地址相同才会复用原密钥；错误返回经过处理的 `message`，响应禁止缓存。此接口只查询，不保存账号。账号新增/编辑及 Worker 账号选择支持 `modelMapping: {haiku?, sonnet?, opus?, fable?}`；值为渠道模型 ID。省略映射时保留原配置，传空对象时清除，空白项沿用账号的默认 `model`。V9 持久化此映射，已有账号迁移为空映射。

除 `GET /health` 外，接口均要求 `Authorization: Bearer <对应密钥>`。连接器、Worker 和管理员三种密钥必须不同且至少 32 字符。来源事件仅接收配置的一个 tenant/app；内部连接器被视为可信适配器，Worker 没有来源事件写入权限。接口不提供浏览器登录，管理员密钥不得嵌入前端页面。

| 身份 | 接口 | 用途 |
| --- | --- | --- |
| 连接器 | `POST /internal/bridge/events` | 提交标准化且持久化的来源消息 |
| 连接器 | `POST /internal/bridge/deliveries/claim` | 领取一个到期发送项 |
| 连接器 | `POST /internal/bridge/deliveries/{id}/ack` | 回报实际发送成功或失败 |
| Worker | `POST /internal/worker/claim` | 领取一个任务和服务端上下文 |
| Worker | `POST /internal/worker/tasks/{id}/heartbeat` | 检查有效租约、取消和总时限 |
| Worker | `POST /internal/worker/tasks/{id}/stopped` | 进程清理后释放已取消任务的会话租约 |
| Worker | `POST /internal/worker/tasks/{id}/complete` | 使用有效租约提交成功或失败 |
| 管理员 | `GET /api/quota` | 官方套餐窗口、剩余百分比与重置时间；60 秒缓存，失败标记过期 |
| 管理员 | `GET /api/tasks` | 最近 100 个任务摘要 |
| 管理员 | `GET /api/dashboard?days=30` | 7／30／90 天已记录用量、模型统计和脱敏任务摘要 |
| 管理员 | `GET /api/tasks/{id}` | 任务详情、答案、模型及用量 |
| 管理员 | `GET /api/tasks/{id}/events?after=0` | 大于指定序号的最多 200 条事件 |
| 管理员 | `GET /api/tasks/{id}/deliveries` | 发送状态与飞书消息 ID |
| 管理员 | `POST /api/tasks/{id}/cancel` | 取消排队或运行任务 |

`chatType` 为 `p2p` 或 `group`。发送队列持久保存 `replyInThread`，群聊为 true，SDK 使用 `reply_in_thread=true` 在原消息下创建或继续可见话题；私聊为 false。

来源消息 JSON 使用 `tenantId, appId, eventId, messageId, chatId, senderId, topicKey, text, chatType`。`eventId` 和 `messageId` 均在企业/应用范围内唯一，重复提交返回 `duplicate: true`。事件、任务、初始回复在同一事务创建。相同群话题串行领取，私聊按聊天和发送者组成 Topic；群话题属于群内共享上下文。

Worker claim 无任务时返回 `{}`，有任务时返回 `taskId, leaseToken, prompt, history`，历史只包含同一 Topic 最近六个成功轮次的 `prompt/result`。Heartbeat 提交 `{"leaseToken":"..."}`，返回 `{"active":true|false}`。Complete 提交 `leaseToken, status, result, errorCode, model, usageJson`，其中 status 只允许 `SUCCEEDED` 或 `FAILED`，成功必须有非空答案。数据库输出沿用列名 snake_case，内部 Worker/连接器写入协议使用 camelCase。

任务状态为 `QUEUED → RUNNING → SUCCEEDED / FAILED`，排队和运行均可进入 `CANCELLED`。租约 30 秒，Worker 每 250 毫秒检查并续约，模型客户端整体执行等待最多 150 秒，Claude Code 执行最多 870 秒，管理侧总时限 900 秒。后台每 5 秒清理过期任务；租约过期明确失败，不自动重新执行。有效租约完成请求可重复提交而不增加通知，取消或过期后的旧结果返回 409。旧进展和结果受租约约束；进程终止不会回滚已完成的文件修改或外部操作。

发送项与任务状态分开，使用 `PENDING / SENDING / SENT / FAILED`。领取后 60 秒租约，每次领取消耗一次尝试，最多五次；失败退避 2、4、8、16 秒。发送失败不改变已成功任务。固定 delivery UUID 传入官方回复接口的 `uuid` 字段作为幂等键，发送项租约变化也复用同一 UUID。受飞书幂等窗口约束，本版不宣称无限期 exactly-once；最后尝试遇到响应丢失时，记录失败需要人工核对平台实际送达情况。

事件序号是数据库全局单调序号，用于同一任务增量查询，允许跨任务间隔，不承诺连续。没有 SSE，本版使用查询 API。待执行任务、话题、原消息和最终回复保存在数据库；只保留成功轮次作为模型上下文，不提供摘要压缩、来源 ACL 传播或长期记忆。部署备份需要同时覆盖 PostgreSQL/H2 和飞书收件队列，保留清理与恢复演练属于后续工作。

发送项还包含 `taskState`，在事务创建时固定，不随任务后续变化覆盖。同一任务的发送项按顺序处理；受理回复送达后安排 THINKING，成功答案发送前安排 Typing，成功送达后安排 DONE。失败/取消安排清理状态。连接器将表情更新单独持久化，新阶段替代尚未成功的旧阶段，查询并确认自己的 reaction 后再添加或移除。V3 之前的历史发送项保持空阶段，不会因升级自动批量补发状态表情。

同一用户在同一 Topic 的新任务消息会将旧 QUEUED/RUNNING 任务置为 CANCELLED，错误码 SUPERSEDED。已排队的旧进展丢弃，SUPERSEDED 发送项只关闭旧进展卡片并清除表情，不额外回复取消通知。运行中旧任务保留租约，Worker 终止子进程后提交 stopped（请求体同 heartbeat），新任务才能领取；Worker 失联则等待旧租约到期。重复来源消息和查询命令不触发替换，其他用户或 Topic 不受影响。

V4 增加任务 `pause_reason` 和 `resume_at`（毫秒）。`WAITING_QUOTA` 仅用于尚未开始的任务；Worker claim 在数据库事务外先取得额度决策，在话题锁内暂停或领取。恢复前产生 `QUOTA_RESUMED` 事件并清空暂停字段，仍使用原任务 ID 与会话身份，不重放已执行任务。

V5 增加调度锁表。全租户最多 3 个 RUNNING 或仍持有有效租约的 CANCELLED 任务；接收和领取采用相同的调度锁→话题锁顺序。`CAPACITY_QUEUE` 事件和发送阶段表示一次并发排队提示，任务本身仍为 QUEUED；领取后跳过尚未发送的排队提示。

V6 增加附件表。Incoming 可选 `attachment: {id,name,size,sha256}`，仅供可信 Bridge 在下载完成后提交；文件事件只写入暂存元数据和确认通知，不创建任务。普通任务 claim 返回 `attachments` 清单，实际文件从 Bridge/Worker 共享的 ATTACHMENT_ROOT 读取并校验大小和 SHA-256。文件名不用于决定存储路径。
