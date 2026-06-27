# TUI 命令真实审计缺陷报告

生成时间：2026-06-28

本文档对应阶段 1.1：以单个功能点为最小单位保存已证实的功能 bug 报告。以下问题已在当前分支修复并合并到 `dev`。

## BUG-004：Cron 创建任务输出在非 UTF-8 终端下无法稳定解析 job id

状态：已修复，提交 `0962d68ec`

影响范围：

- `/cron add` 创建定时任务后的复制、审计和后续生命周期操作。
- CLI/TUI 自动化审计中的 `/cron inspect`、`/cron pause`、`/cron resume`、`/cron history`、`/cron remove` 串联流程。

当前事实：

- `DefaultCronCommandHandler` 原本只输出中文前缀 `已创建定时任务：<id>`。
- 真实审计环境使用 `LC_ALL=C` 时，中文前缀会退化为问号，脚本无法从输出中解析任务 id。
- 用户仍能看到一串 id，但自动化和复制使用都缺少稳定字段。

可复现现象：

```bash
python3 scripts/audit-terminal-commands.py --no-defaults --include-write-commands --cron-lifecycle --timeout-seconds 20
```

修复前在创建任务后失败：

```text
OUT: 未能从创建输出中解析 cron job id
```

修复方向：

- `/cron add` 成功回复保留中文说明，同时追加稳定 ASCII 字段 `job_id=<id>`。
- 审计脚本优先解析 `job_id=`，再兼容旧中文输出。

验证结果：

```bash
mvn -q -Dskip.web.build=true -Dtest=CommandEnhancementTest#shouldSupportJimuquCronFlagSyntaxAndSkillEditing test
python3 scripts/audit-terminal-commands.selftest.py
mvn -q -Dskip.web.build=true -DskipTests package
python3 scripts/audit-terminal-commands.py --no-defaults --include-write-commands --cron-lifecycle --timeout-seconds 20
```

结果：Cron 生命周期 10 项通过，`audit.findings=0`。

## BUG-005：旧 Java `--tui` 入口被真实审计误判为可交互 TUI

状态：已修复，提交 `af31aba85`

影响范围：

- `java -jar target/solonclaw-0.0.1.jar --tui` 裸启动入口。
- TUI 命令真实审计脚本的入口判断。

当前事实：

- 当前真实 TUI 是 Node TUI，通过 `bin/solonclaw` 启动并连接后端 WebSocket。
- 裸 `java -jar ... --tui` 没有交互式 TUI 实现，原本会走一次性 prompt 分支，因缺少输入而退出 1。
- 审计脚本把旧 Java `--tui` 当成真实交互入口，批量写入命令后只看到 PTY 输入回显，误报大量 `missing_prompt_echo`。

可复现现象：

```bash
python3 scripts/audit-terminal-commands.py --no-defaults --tui-pty --timeout-seconds 20
```

修复前输出类似：

```text
tui SUSPECT --tui PTY
issues=missing_prompt_echo:/setup,...
```

修复方向：

- 裸 Java `--tui` 不再报“缺少输入内容”，改为明确提示使用 `solonclaw` 启动本地 TUI。
- 提示中追加稳定 ASCII 标记 `node_tui_entry=solonclaw`，避免非 UTF-8 终端下审计再次依赖中文。
- 审计脚本的旧 `--tui` 路径只验证入口提示；真实交互继续使用 `--node-tui-pty`。

验证结果：

```bash
mvn -q -Dskip.web.build=true -Dtest=CliRunnerTest test
python3 scripts/audit-terminal-commands.selftest.py
mvn -q -Dskip.web.build=true -DskipTests package
python3 scripts/audit-terminal-commands.py --no-defaults --tui-pty --timeout-seconds 20
python3 scripts/audit-terminal-commands.py --no-defaults --node-tui-pty --timeout-seconds 20 --node-tui-command /details --node-tui-command /voice --node-tui-command /indicator --node-tui-command /rollback --node-tui-command /exit
python3 scripts/audit-terminal-commands.py --timeout-seconds 20
```

结果：

- 旧 `--tui` 入口提示审计通过，`audit.findings=0`。
- Node TUI 真实交互审计通过，`audit.findings=0`。
- 全量终端命令审计 93 项通过，`audit.findings=0`。

## BUG-006：TUI 审批响应缺少 session_id 时会假成功

状态：已修复，本次提交

影响范围：

- TUI 审批弹层的批准、拒绝和 Ctrl-C 拒绝路径。
- 后端 `approval.respond` RPC。

根因：

- 后端 `approval.respond` 在 `session_id` 为空时直接返回 `ok=true`，但没有执行真实 `/approve` 或 `/deny` 流程。
- TUI 前端原本没有把 `approval.request` 事件信封里的 `session_id` 保存到审批弹层，只依赖当前 UI 会话标识。

修复方向：

- 后端在缺少 `session_id` 时返回 `ok=false` 和 `missing_session_id` warning，避免前端误判为授权成功。
- TUI 审批弹层保存事件级 `session_id`，批准和 Ctrl-C 拒绝时优先回传该会话标识。

验证结果：

```bash
mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=TerminalUiApprovalRespondTest,DangerousCommandApprovalCommandTest,DangerousCommandApprovalServiceTest test
npm --prefix terminal-ui test -- createGatewayEventHandler.test.ts
npm --prefix terminal-ui run type-check
```
