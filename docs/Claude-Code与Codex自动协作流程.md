# Claude Code 与 Codex 自动协作流程

## 1. 目标

对一个明确的开发任务自动执行以下闭环：

```text
Codex 编码
  → 自动执行 Java 8 Maven verify
  → Claude Code 依据功能与正式文档做只读评审
  → Codex 按评审意见修复
  → 自动重新构建
  → Claude Code 复审
  → PASS 或达到停止条件
```

角色固定：Codex 是实现者和修复者，Claude Code 是独立评审者。Claude Code 在评审阶段没有
编辑或 Shell 工具，不能一边修改一边批准自己的实现。

## 2. 正式判据

每个参与者必须按以下优先级工作：

1. `AGENTS.md`
2. 本次任务文件
3. `AI体检报告分析-开发方案V1.md`
4. `AI体检报告分析-精简设计方案V1.md`
5. `体检报告分析需求.md`
6. 当前 Prompt、Schema、Java 常量和已有代码约定

若任务文件与更高优先级的安全、持久化、公开接口或医疗规则冲突，流程必须停止为 `BLOCKED`，
不得自行选择一条规则。

## 3. 三个角色阶段

### 3.1 Codex 首次开发

Codex 必须：

- 完整读取任务文件和受影响的正式文档；
- 先检查工作区现状，保留用户已有改动；
- 实现代码和相关测试，而不是只写方案或 TODO；
- Java 只做可穷举、可单测的简单判断，复杂版面/语义判断遵守文件转图层与体检报告分析模型的职责边界；
- 同步受影响的 Prompt、Schema、DDL 和文档契约；
- 执行最小相关测试，并在最终消息中列出改动文件、命令、结果和未决项；
- 不 commit、不 push，不调用 Claude Code 参与编码。

### 3.2 Claude Code 独立评审

评审前必须读取：

- 运行前的 Git 状态、tracked 二进制补丁和用户原有文件备份清单；
- 构建后的 Git 状态和相对 `HEAD` 的补丁；
- Maven `verify` 完整日志与测试数量摘要；
- Codex 本轮自述、任务文件、正式开发文档及所有受影响文件。

评审边界：

- 只评审任务文件划定的范围；明确“不做”的内容不得作为 P0/P1；
- 认为任务范围本身有问题时写成 P2 备注，不影响 `VERDICT`；
- Codex 自称 `BLOCKED` 时必须独立判断；
- 对比运行前基线和当前工作区，发现覆盖或删除用户原有改动时判为 P0；
- 构建摘要 `testsExecuted=NO` 且 `REQUIRE_TESTS=1` 时不得 PASS。

评审第一行必须是以下三者之一：

```text
VERDICT: PASS
VERDICT: CHANGES_REQUIRED
VERDICT: BLOCKED
```

判定规则：

- `PASS`：没有 P0/P1，功能完成，Java 8 `verify` 成功且测试门禁通过；
- `CHANGES_REQUIRED`：存在可由 Codex 在当前授权范围内修复的 P0/P1；
- `BLOCKED`：缺少产品/医疗决策、外部接口、权限、凭证或正式契约，无法安全修复。

每条意见必须包含优先级、文件与行号、问题、后果、明确修改要求和建议回归测试。

### 3.3 Codex 按意见修复

Codex 必须逐条处理 P0/P1：

- 同意时修改代码并补测试；
- 不同意时用代码、测试或正式文档给出证据，不能忽略；
- 意见涉及新产品行为或医疗判断时停止为 `BLOCKED`；
- 不得通过删除测试、放宽断言、吞异常或改成 TODO 获得通过；
- 修复后必须重新构建并由 Claude Code 复审。

## 4. 自动停止条件

| 条件 | 结果 |
|---|---|
| Claude Code `PASS`、Java 8 Maven `verify` 成功且测试数大于 0 | 成功，退出码 0 |
| Claude Code `BLOCKED` | 阻塞，退出码 3 |
| Codex 或 Claude Code CLI 调用失败 | 工具失败，退出码 4 |
| 达到最大修复轮次仍未 PASS | 未通过，退出码 2 |
| Maven 构建失败且达到最大修复轮次 | 未通过，退出码 2 |
| `HEAD` 相对运行基线发生变化 | 工具失败，退出码 4；确认该提交应被接受后可显式 `--rebaseline` |
| 评审开头 5 个非空行里没有合法 `VERDICT` | 按 `BLOCKED` 处理，退出码 3 |
| 收到 `HUP` / `INT` / `TERM` | 保存断点，退出码分别为 129/130/143 |

默认最大修复轮次为 2。可设置 `MAX_FIX_ROUNDS=3`，但禁止无上限循环。

## 5. 运行与恢复

### 5.1 单模块执行

任务 Markdown 至少包含目标、范围、验收条件、不做什么，以及可否修改公开接口或数据库。

```bash
./scripts/claude-codex-workflow.zsh tasks/实现文件上传.md
```

可选环境变量：

```bash
MAX_FIX_ROUNDS=3 ./scripts/claude-codex-workflow.zsh tasks/实现文件上传.md
COLLAB_JAVA_HOME=/path/to/jdk8 ./scripts/claude-codex-workflow.zsh tasks/实现文件上传.md
REQUIRE_TESTS=0 ./scripts/claude-codex-workflow.zsh tasks/纯文档任务.md
```

`REQUIRE_TESTS` 默认是 `1`。仅不含代码、Schema、Prompt 或契约逻辑的纯文档任务可以显式设为
`0`；普通开发任务不得关闭测试门禁。

脚本只接受工作区内的任务文件。Codex 使用 `workspace-write + -a never`，可以修改工作区并执行
沙箱许可的命令，但不能在无人值守流程中申请扩大权限。Claude Code 只有 `Read/Glob/Grep`。
启动前还会验证 Git 仓库存在有效 `HEAD`、Codex/Claude/Maven 可用，并实际执行目标 Java 的
`java -version` 确认它是 JDK 8。

### 5.2 单模块中断恢复

每个阶段开始前都会原子写入 `workflow-checkpoint.txt`。Codex、Claude 和 Maven 都以受跟踪的
后台子进程运行；PID 原子写入运行目录的 `current-child.pid`，类型写入
`current-child.kind`。给工作流 Shell 发送 `HUP` / `INT` / `TERM` 时，Shell 会先终止当前
子进程，等待退出，必要时升级为 `KILL`，然后保存中断状态。

恢复命令：

```bash
./scripts/claude-codex-workflow.zsh --resume .ai-collab/<运行目录>
```

恢复规则：

- Codex 首次开发途中中断：保留已有修改，并重新执行一次完整的首次开发阶段；
- Codex 修复途中中断：保留已有修改，并重新执行当前同一轮修复；中断不额外消耗修复轮次；
- Maven 构建途中中断：重新执行完整 `clean verify`；
- Claude 评审途中中断：覆盖未完成的评审文件并重新评审；
- `BLOCKED`、工具失败或达到修复上限：修正文档、环境或提高轮次后重新构建和评审；
- 已经 `PASS` 的运行再次恢复只返回成功；
- 恢复时如果 `HEAD` 已改变，普通 `--resume` 拒绝继续；
- `00-baseline-head.txt` 缺失时拒绝恢复，不会静默把当前 HEAD 当作旧基线。

这里恢复的是工作区和阶段断点，不恢复 Codex/Claude 模型会话本身。

`SIGKILL` 无法被 trap 捕获。若工作流 Shell 被硬杀而 Codex/Claude/Maven 仍在运行，
`current-child.pid` 会保留；恢复流程发现该 PID 存活时会拒绝启动，避免两个流程并发修改同一
工作区。应先等待该进程自然结束，或向记录的 PID 发送 `TERM`，确认退出后再恢复。不要手工删除
`current-child.pid` 或锁目录来绕过保护。

如果中断期间确实产生了一个应纳入当前流程的新 Git 提交，使用显式重建基线：

```bash
./scripts/claude-codex-workflow.zsh --rebaseline .ai-collab/<运行目录>
```

该命令会保存旧、新 HEAD 和唯一快照前缀到 `rebaseline-history.tsv`，重新生成工作区快照，并至少重新执行完整
构建和 Claude 评审；快照名包含时间、进程 PID 和必要时的序号，同一秒连续执行也不会相互覆盖。
若断点正处于 Codex 首次开发或修复中，则先重跑该 Codex 阶段。它表示
“我明确接受这个提交属于本次运行”，不能用来掩盖来源不明的提交，也禁止直接编辑
`00-baseline-head.txt`。

若历史改写和 Git 对象清理使第一次启动时的 `00-original-head.txt` 已无法解析，`--rebaseline`
会把累计补丁基点显式重置为当前新基线，并写入 `original-head-reset-history.tsv`；重基线快照同时
成为后续 Claude 的评审比较基线，避免固定的 `00-baseline-git-diff.patch` 与新一轮累计补丁使用
不同 Git 基点。此后评审补丁只表示新基线之后的累计变化，第一次启动到新基线之间的差异不再能由 Git 还原。Git 状态、补丁或
原始 HEAD 校验失败时，原始 stderr 会保存在同名前缀的 `*.stderr.log`，控制台错误会指向该文件。

每次真正继续执行 `--resume` 或 `--rebaseline` 前，脚本都会把当时全部已修改 tracked 文件和
untracked 文件复制到独立的 `resume-<时间>-<PID>-files/`，同时保存状态、累计补丁和清单，并追加
`resume-backups.tsv`。因此用户在中断窗口里新建或修改的文件即使随后被工具误覆盖，也能从该次
恢复快照中找回；后一次恢复不会覆盖前一次备份。

`00-baseline-*` 只证明第一次启动前的用户改动，不能用于判断中断期间文件的归属。恢复后的 Codex
只有在对比首次基线补丁和上一轮已完成工作区补丁后，能够明确证明某项修改由自动流程产生时，才
可以继续整理该修改。未进入已完成补丁、发生于中断窗口、没有完整补丁或归属不明的当前改动，一律
按用户改动保护。恢复备份是意外恢复安全网，不是允许覆盖用户文件的授权。

### 5.3 多模块严格顺序执行

15 个模块必须拆成 15 个任务 Markdown。另建任务清单，例如 `tasks/执行顺序.txt`：

```text
# 每行一个工作区相对路径，顺序就是执行顺序
tasks/01-基础工程.md
tasks/02-数据库.md
tasks/03-文件上传.md
```

执行：

```bash
./scripts/claude-codex-sequence.zsh tasks/执行顺序.txt
```

总控只有在前一个模块完整 `PASS` 后才启动下一个。任一模块 `BLOCKED`、工具失败或达到修复上限，
总控立即停止并保留当前模块索引。处理完后恢复：

```bash
./scripts/claude-codex-sequence.zsh --resume .ai-collab/sequences/<运行目录>
```

总控使用启动时生成的清单快照，恢复时不会因为原清单后来被编辑而改变剩余顺序。单模块执行器和
顺序总控都有工作区互斥锁，不能并发修改同一个仓库。

给顺序总控发送 `TERM` 时，它会先终止当前单模块工作流；单模块工作流再终止正在运行的
Codex/Claude/Maven，整条子流程退出后总控才写 `INTERRUPTED_TERM`。恢复仍使用上面的
`--resume` 命令。

如果顺序流程因为模块运行期间 HEAD 变化而停止，先对状态文件中 `childRunDir` 指向的单模块目录
执行 `claude-codex-workflow.zsh --rebaseline <childRunDir>`，该模块通过后，再恢复顺序总控。

### 5.4 锁屏、睡眠和后台运行

脚本在 macOS 上自动使用 `caffeinate -i` 防止空闲系统睡眠；屏幕仍可关闭或锁定。它不能阻止
合盖、低电量、关机或用户强制睡眠。

需要关闭终端窗口时，用 `nohup` 后台启动，并把控制台日志写到安全位置：

```bash
(umask 077; nohup ./scripts/claude-codex-sequence.zsh tasks/执行顺序.txt \
  > /tmp/health-report-workflow.log 2>&1 &)
```

调试时可设置 `COLLAB_DISABLE_CAFFEINATE=1`；正式长任务不建议关闭。

## 6. 安全门禁与用户改动保护

- 启动时记录基线 `HEAD`，每个 Codex 阶段后和最终 PASS 前再次校验；
- 保存运行前 tracked 二进制补丁；
- 备份运行前所有已修改 tracked 文件和所有未跟踪文件；
- 每次恢复前再次保存状态、累计补丁和逐文件备份，追加索引且不覆盖历史快照；
- Claude 必须同时读取首次基线、当前评审比较基线、所有恢复快照与构建后的当前快照；
- 不自动回滚，因为任务本身可能合法修改同一文件；若误覆盖，可从运行目录手工恢复；
- 构建后的快照才是评审快照，避免 Maven 插件修改源码后补丁过期；
- 单模块和顺序流程使用互斥锁，异常退出后只清理已确认进程不存在的陈旧锁；
- 锁记录 Shell PID，运行目录另存当前子进程 PID；Shell 死亡但子进程仍活着时，陈旧锁不得清理；
- `completed-modules.tsv` 按模块序号原子替换。同一模块写完台账但索引尚未推进时崩溃，恢复重跑
  不会追加重复记录；
- 所有产物以 `umask 077` 创建，默认只允许当前用户访问。

## 7. 产物

单模块运行目录主要内容：

```text
00-task-path.txt                          本次任务文件路径
00-baseline-head.txt                      运行前 HEAD
00-original-head.txt                      累计补丁基点；通常是首次 HEAD，不可用时由显式重基线重置
00-baseline-git-status.txt                运行前工作区状态
00-baseline-git-diff.patch                运行前 tracked 二进制补丁
*-git-status.stderr.log                   Git 状态快照原始错误（成功时为空）
*-git-diff.stderr.log                     Git 补丁快照原始错误（成功时为空）
00-baseline-backup-manifest.txt           用户原有文件备份清单
00-baseline-files/                        已修改 tracked 与 untracked 文件原始内容
workflow-checkpoint.txt                   阶段、轮次和恢复所需路径
current-child.pid / current-child.kind    当前 Codex、Claude 或 Maven；正常退出后删除
rebaseline-history.tsv                    显式重建基线的旧、新 HEAD 审计记录（发生过时才有）
original-head-reset-history.tsv            原始提交不可用时累计补丁基点的重置记录（发生过时才有）
resume-backups.tsv                         每次恢复前快照、清单和备份目录索引（发生过恢复时才有）
resume-<时间>-<PID>-git-*                  某次恢复继续执行前的状态与累计补丁
resume-<时间>-<PID>-backup-manifest.txt    某次恢复继续执行前的可恢复文件清单
resume-<时间>-<PID>-files/                 某次恢复继续执行前的逐文件备份
review-baseline-*-path.txt                 Claude 当前评审比较基线引用（重基线后生成）
01-codex-implementation.md                Codex 首次开发总结
01-codex-implementation-cli.log           Codex CLI 完整控制台日志
02-build-r0.log                           Java 8 Maven verify 日志
02-build-summary-r0.txt                   退出码、测试总数和 ERROR 摘要
02-workspace-r0-git-*                     构建后的评审快照
03-claude-review-r0.md                    Claude Code 首次评审
03-claude-review-r0-cli.log               Claude CLI 错误日志
04-codex-fix-r1.md                        Codex 第一轮修复总结
...
workflow-status.txt                       当前或最终状态
```

顺序总控目录 `.ai-collab/sequences/<运行目录>/` 保存清单快照、当前模块索引、已完成模块表，以及
`modules/001`、`modules/002` 等独立单模块产物目录。

产物不得包含姓名、报告原文、模型响应正文、完整模型请求响应、凭证或健康数据。真实报告验证只记录
样本文件名、通过/失败和统计结果。运行完成后按项目数据保留策略清理不再需要的产物。

## 8. 使用边界

- 自动协作不会解决尚未拍板的产品或医疗问题；遇到这类问题必须 `BLOCKED`；
- Claude Code 的 `PASS` 不是上线批准，真实体检报告分析模型、菜品标签模型、S3、菜品接口和黄金样本仍需验收；
- 若开发方案本身存在会改变实现的 P0，Codex 不得自行绕过；
- 本流程不扩大任务授权，不允许自动发布、部署、创建 PR、commit 或 push；
- 不要把 15 个模块塞进一个任务文件，应让每个模块独立完成开发、构建、评审和修复闭环。

## 9. 流程脚本自测

脚本修改后运行：

```bash
./scripts/test-claude-codex-workflow.zsh
```

自测使用临时 Git 仓库和假 Codex/Claude/Maven/JDK，不调用真实模型，当前覆盖：

- 正常通过、用户原有修改备份与零测试门禁；
- `IMPLEMENTATION_RUNNING`、`BUILD_RUNNING`、`REVIEW_RUNNING`、`FIX_RUNNING` 的 TERM 中断恢复；
- 中断期间新增文件在恢复前被独立备份，且恢复提示词对归属不明改动采用默认保护；
- 修复阶段中断后重跑同一轮，不额外消耗轮次；
- 单模块 Shell 被 SIGKILL 后，存活孤儿进程阻止并发恢复；
- 顺序总控 TERM 逐级终止模块工作流和其当前子进程；
- `BLOCKED` 后顺序恢复，以及 `completed-modules.tsv` 崩溃窗口幂等；
- HEAD 变化时普通恢复失败、连续显式 `--rebaseline` 快照不覆盖、原始提交被清理后仍可重建基线，以及基线文件缺失时拒绝恢复；
- 控制台不再输出 zsh `typeset` 的局部变量值。
