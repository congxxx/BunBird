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

当前不支持含符号链接或子模块的基线，会在检出前拒绝。该模块没有执行 Shell 或模型生成代码，也不是容器沙箱；尚未接入飞书任务的自动工作空间分配、代码 Agent、PR 交付及组织权限校验。这里的 task-id 是本地登记标识，不会自动创建或绑定管理服务中的 Task。下一步可将工作空间 ID 和固定基线接入任务执行记录，再接代码执行与交付流程。
