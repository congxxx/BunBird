# 任务 Git 工作空间

本版提供本机工作空间管理基础。每个任务尝试先建立独立 Git clone，再从固定提交创建真正的 linked worktree。不同任务不共享可写 `.git`、对象库或文件硬链接，原仓库中的未提交改动和未跟踪文件不会带入任务。任务分支为 `codex/task-<工作空间ID>`。

需要 Python 3.12+ 和本机 Git。在项目根目录运行以下命令，CLI 自动读取 `.env` 中的 `FEISHU_TENANT_KEY`，默认将登记信息和文件保存在 `data/workspaces/<企业标识哈希>/`。可通过 `WORKSPACE_ROOT` 指定其他目录，建议使用绝对路径；目录不能包含符号链接或 Windows 目录联接。

```powershell
# 登记允许使用的本地仓库，只使用已提交的 main 基线
uv run bunbird repo add bunbird --path F:/Projects/BunBird --ref main
uv run bunbird repo list

# 创建任务空间，返回 ID、分支、固定提交 SHA 和目录
uv run bunbird workspace create --repo bunbird --task-id example-task
uv run bunbird workspace list
uv run bunbird workspace inspect <工作空间ID>

# 仅移除干净且没有新增提交的工作目录
uv run bunbird workspace remove <工作空间ID>
```

同一企业下相同任务 ID 和 attempt 重复创建会返回已有 READY 工作空间；任务尝试不能改绑其他仓库。不同企业使用独立登记表和目录，但本机 CLI 的企业配置并不是操作系统权限边界。远程仓库需要先克隆到本机，再登记；本版不会获取远程更新，使用创建当时本地分支解析出的提交。

创建状态依次为 `PREPARING → READY`，失败记为 `FAILED` 并保留诊断目录，不退回原仓库执行，也不自动覆盖旧目录。进程意外终止后可运行 `uv run bunbird workspace recover <工作空间ID>`，它会在获取独占锁后将遗留 PREPARING 标为 FAILED。需要重新创建时使用相同 task-id，并增加 `--attempt 2`。正在创建或移除的空间不能被恢复操作抢占。

`inspect` 返回当前提交、文件状态和相对基线的差异统计。移除前会验证任务分支、Git 元数据归属、目录边界，以及所有文件是否含链接。存在已修改文件、未跟踪文件、被忽略文件或新增提交时均拒绝移除；成功后记录为 REMOVED，独立 clone 和登记记录仍保留。失败目录暂时保留供人工检查，本版不提供强制清空命令。

当前不支持含符号链接或子模块的基线，会在检出前拒绝。该模块不执行模型生成代码，也不是容器沙箱；代码 Agent、PR 交付及组织权限校验仍待实现。CLI 的 task-id 可以是本地标识，飞书入口则使用管理服务的真实 Task ID 建立对应记录。

## 飞书入口

以下固定命令和主机列表适用于 `AGENT_EXECUTOR=model`。启用 [Claude Code 执行器](claude-agent.md) 后，消息直接交给 Agent，使用自然语言即可，不需要预登记仓库或维护该主机列表。

在已接入的飞书群聊或私聊发送 `/workspace bunbird` 或 `帮我克隆 bunbird`，会为该任务创建已登记仓库的独立工作空间。远程仓库可以发送 `/workspace https://github.com/组织/仓库.git`，也可以发送“拉取一下这个项目”并在下一行放仓库地址。`git@主机:组织/仓库.git` 会转换为 HTTPS 地址，不会执行 SSH。请求由固定规则识别，普通聊天仍由配置的模型回答；工作空间创建不消耗模型调用，也不会提交或推送代码。

远程主机由 `WORKSPACE_GIT_HOSTS` 控制，默认允许 GitHub、Gitee 和 GitCode；企业自建 Git 主机需管理员加入该配置并重启 Worker。地址不能包含用户名、密码、令牌或查询参数。私有仓库令牌请放在 `.env` 的 `WORKSPACE_GIT_TOKEN_<主机名大写且非字母数字改下划线>` 中，可配套设置 `WORKSPACE_GIT_USER_<同样后缀>`，默认用户名为 oauth2。不要将令牌发送到群聊。认证头仅通过 Git 子进程环境传递，不写入 URL 或仓库配置，也不会跟随 HTTP 重定向。

远程导入会获取远端默认分支，再创建任务 clone 和 worktree。导入副本保存在企业目录下的 `imports` 中，失败副本不会自动删除。取消或租约失效会终止工作空间子进程及 Git 子进程，已生成文件保留供检查；不会发送成功结果。总任务时限仍沿用当前平台限制，大仓库可能超时。

飞书收到任务后只添加表情，不发送受理文字。处理时更新同一张进度卡片，完成后将它改为完成提示，再单独发送结果卡片；失败和取消有对应状态。进度是公开执行状态，不包含模型内部思维。原消息上的表情依次为“思考”“敲键盘”“完成”，成功表情在结果发送后设置。进度使用数据库 outbox 和本地卡片消息 ID 记录，重试不会重复新增同一阶段，群聊消息继续回复到话题中。

本地运行修改 `.env` 后重启 Worker。Compose 已提供 Git 和持久化工作空间卷；私有仓库的凭据变量需显式加入 Worker 的 environment，且本机登记路径不能直接用于容器，需挂载并在容器内登记。Compose 镜像本次未进行实际构建验证。
