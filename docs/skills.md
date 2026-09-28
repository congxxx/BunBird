# SkillHub 技能管理

打开监控看板的「技能管理」，选择本地目录或 Git 仓库导入。这里的本地目录指 Control 服务所在机器上的绝对路径；Git 导入支持公开 HTTPS 仓库，可指定仓库内包含 `SKILL.md` 的子目录。显示名称可自行填写，导入时可选择是否立即启用。列表支持查看完整说明及启用、停用，内置 `read-attachments` 仍按附件需要独立加载。

导入保存一份不可变副本，包含说明、脚本和资源，不运行脚本、不安装依赖。Git 来源记录提交 SHA，本地来源记录内容摘要；修改原文件不会自动同步，需要重新导入。目录最多 100 个文件、合计 1 MB，`SKILL.md` 使用 UTF-8 编码且最多 64 KB；隐藏文件和隐藏目录忽略，符号链接及越界路径拒绝。当前最多保存 20 个导入技能。

技能正文和资源保存在 Control 数据库。Worker 在通过任务租约校验的账号选择响应中取得已启用的技能快照，挂载到该会话独立的 `managed-skills/<技能ID>/<版本>` 目录。Claude Code 的系统说明包含本轮技能清单与 `SKILL.md` 路径，要求先读取说明再按任务需要使用，兼容 `--bare`。账号切换沿用同一轮的技能快照，不影响内置附件解析。普通模型聊天执行器不执行技能。

启停影响后续执行，不中断正在运行的任务。停用表示从后续任务清单中移除，不删除历史会话中的技能缓存，也不是操作系统级访问隔离；Agent 仍按现有 Worker 本机权限执行。只导入可信技能。此版本是 BunBird 自身的技能管理模块，不依赖第三方 SkillHub 服务，不提供在线市场搜索、自动更新、依赖安装或卸载功能。

管理员接口：`GET /api/skills` 列表，`GET /api/skills/{id}` 查看说明，`POST /api/skills/import` 导入，`POST /api/skills/{id}/state` 启停。导入请求为 `{type: "local"|"git", source, directory?, name?, enabled}`，启停请求为 `{enabled: true|false}`。列表和详情不返回资源文件，只有持有有效任务租约的 Worker 能从账号选择响应的 `skills` 获取启用技能文件；管理接口不接受 Worker 或 Bridge 凭据。V11 新增 `managed_skill` 表。
