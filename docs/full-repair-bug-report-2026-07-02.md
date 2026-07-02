# 无人值守全面修复阶段 1.1 增量缺陷报告

生成时间：2026-07-02

## 对应外部对标能力点

- Dashboard-first setup / doctor：Web 页面应能完整展示诊断、模型、渠道与运行状态，不应出现缺失文案或组件库错误。
- 本地 CLI / TUI 交互层：终端用户输入 slash commands 时应得到确定的命令行为，不能误发模型或卡在不可关闭的覆盖层。
- 模型用量统计：TUI WebSocket 的 `message.complete` 事件应展示本轮真实模型用量。

## 审计范围

本报告汇总当前无人值守 E2E 与 focused 测试已确认的问题。已在旧报告中闭环的 BUG-001 至 BUG-014 不重复列入。

## BUG-015：TUI 流式完成事件用量始终为 0

状态：已修复，提交 `8772f568b`

影响范围：

- Node TUI 的 `message.complete` 事件。
- TUI 顶部或完成状态展示的模型请求次数、输入 token、输出 token、推理 token 与总 token。

当前事实：

- 真实日志中已出现 `LLM usage: inputTokens=18934, outputTokens=141`。
- TUI WebSocket `message.complete.usage` 仍为 `{"calls":0,"input":0,"output":0,"reasoning":0,"total":0}`。
- 回归测试模拟真实 provider 只在最后一个流式 chunk 返回 `AiUsage`，修复前 `requestCount` 仍为 0。

根因：

- `executeOwnedModelRequest()` 的流式路径保存了 `finalResponse[0]`，但结束后没有将 `finalResponse[0].getUsage()` 写入当前 `UsageCollector`。
- 该路径过度依赖 Solon AI stream interceptor 是否被调用；真实 TUI 路径中拦截器未能提供用量。

处理记录：

- 流式 `blockLast()` 结束后直接读取最终响应的 `getUsage()` 并合并到当前 `UsageCollector`。
- 若拦截器已经收过同一份原始 usage JSON，则跳过重复累计。
- `SolonAiOwnedReActLoopTest.shouldStreamOwnedLoopDeltasWhenEventSinkIsProvided` 已覆盖非零 usage。

验证命令：

```bash
mvn "-Dskip.web.build=true" "-Dtest=SolonAiOwnedReActLoopTest#shouldStreamOwnedLoopDeltasWhenEventSinkIsProvided" test
mvn "-Dskip.web.build=true" "-Dtest=SolonAiOwnedReActLoopTest,SolonAiLlmGatewayUsageTest" test
mvn "-Dskip.web.build=true" "-Dtest=TerminalUiApprovalRespondTest,TerminalUiRpcServiceTest" test
```

## BUG-016：Web 页面仍触发 Ant Design Vue 废弃属性告警

状态：已修复，提交 `ece1b45b9`

影响范围：

- 渠道页面。
- 任务历史抽屉。
- 运行记录检查点预览抽屉。
- 使用 `<Spin tip>` 或 `<Drawer width>` 的页面和组件。

当前事实：

- Web E2E 打开 `/solonclaw/channels`、任务弹窗和模型弹窗时，控制台输出组件库废弃 API warning。
- 已确认位置包括：
  - `web/src/views/solonclaw/ChannelsView.vue`
  - `web/src/components/solonclaw/jobs/JobCard.vue`
  - `web/src/views/solonclaw/RunsView.vue`

建议修复方向：

- 按当前 `antdv-next` API 替换废弃属性。
- 补一个静态测试覆盖常见废弃属性，避免之后继续新增。

## BUG-017：TUI `/help` 分页覆盖层关闭不稳定

状态：已修复，提交 `8e6ceaaf6`

影响范围：

- Node TUI 的 `/help` 分页面板。
- 后续输入命令的解析。

当前事实：

- 真实 PTY 测试中，输入 `/help` 后按 `Esc` 未关闭覆盖层。
- 随后按 `q` 会进入输入框，再输入 `/setup` 会拼成普通消息 `q/setup`。
- 该路径可能误触发模型请求。

建议复现：

1. 启动后端和 Node TUI。
2. 输入 `/help` 并回车。
3. 按 `Esc`。
4. 按 `q`。
5. 输入 `/setup` 并回车。

建议修复方向：

- 优先检查 `terminal-ui/src/app/useInputHandlers.ts` 的 `overlay.pager` 分支。
- 分页覆盖层应统一处理 `Esc` 和 `q` 关闭，并阻止关闭键落入普通输入框。

复核记录：

- 当前 `/help` 已走本地 panel；残留风险来自任意 pager 被 `Esc` 关闭后紧随的 `q` 落入空输入框。
- `overlayStore` 记录 pager 键盘关闭时间，`textInput` 吞掉一次紧随其后的空输入框 `q`，避免后续命令拼成 `q/setup`。

验证：

- `npm --prefix terminal-ui test -- overlayStore.test.ts`
- `npm --prefix terminal-ui run type-check`

## BUG-018：TUI 未知 slash command 被当成普通聊天发送模型

状态：已修复，提交 `3911f0ec4`

影响范围：

- Node TUI slash command 解析。
- 未配置模型或配置错误时的错误暴露。
- 用户误输入命令时的安全边界。

当前事实：

- setup-required 状态输入 `/not-a-real-command` 会进入 LLM 请求。
- 未配置 key 时终端显示上游 API key 错误，而不是本地“未知命令”提示。

建议修复方向：

- TUI 在发送聊天前先判断以 `/` 开头且不属于已注册命令的输入。
- 对未知命令返回本地提示，例如“未知命令，可输入 /help 查看命令列表”。
- 不要把未知 slash command 下发给模型。

处理记录：

- 在命令 catalog 已加载且本地命令、catalog exact、catalog 唯一前缀都无法匹配时，直接返回本地未知命令提示。
- 保留 catalog 未加载时的后端 fallback，避免启动早期动态技能命令不可用。
- 已用 `createSlashHandler.test.ts` 覆盖未知 slash command 不调用后端、不发送聊天。

## BUG-019：后端不可达时 Node TUI 高频刷屏和刷日志

状态：已修复，本次提交

影响范围：

- Node TUI 离线启动。
- 后端重启、网络断开或端口配置错误时的用户体验。
- `terminal-ui-bridge.log` 体积和可读性。

当前事实：

- `SOLONCLAW_SERVER_URL=http://127.0.0.1:19999` 启动 Node TUI 后，10 秒内重复输出大量 `fetch failed` 和“后端已断开”。
- 旧审计记录显示日志中 `backend handshake failed` 可达数千次。

建议修复方向：

- 对握手失败和重连失败做退避。
- UI 状态保持一条稳定离线提示，避免每次失败都追加活动消息。

处理记录：

- `planGatewayRecovery()` 增加恢复延迟：首轮立即恢复，后续 crash-loop 按 1s 递增退避，最高 5s。
- `useMainApp` 按恢复计划延迟 `gw.start()`，并用同一活动标签替换离线恢复提示，避免短时间内刷屏。

验证：

- `npm --prefix terminal-ui test -- gatewayRecovery.test.ts gatewayClient.test.ts`
- `npm --prefix terminal-ui run type-check`

## BUG-020：CLI 未知 slash command 会走服务启动路径

状态：已修复，提交 `88f08f103`

影响范围：

- `java -jar ... /not-a-real-command` 这类一次性 CLI 输入。
- 端口占用时的错误可读性。

当前事实：

- CLI 输入未知 slash command 时没有返回未知命令错误。
- 在端口占用环境下会尝试启动 Solon HTTP 服务并输出 `Address already in use: bind` 堆栈。

建议修复方向：

- CLI 入口对以 `/` 开头但未注册的命令先返回本地错误。
- 不进入 server 启动分支。

处理记录：

- 顶层 `/unknown` 启动参数先解析为一次性 CLI 模式，不再进入 Solon HTTP 服务启动路径。
- CLI/TUI runtime 对不支持的 slash command 返回本地错误，并阻止其进入 Agent 对话主循环。

验证：

- `mvn "-Dskip.web.build=true" "-Dtest=CliModeParserTest,CliRunnerTest" test`

## BUG-021：Node TUI setup-required 状态下 `/commands` 只回显

状态：已修复，提交 `6dc47cff8`

影响范围：

- 初次安装或配置缺失用户查看命令目录。
- setup-required 状态下的 slash command 一致性。

当前事实：

- Node TUI 进入“需要先完成设置”状态后输入 `/commands`，界面只留下 `· /commands`。
- CLI `/commands` 能正常显示命令目录。

建议修复方向：

- setup-required 状态也应允许本地命令目录类命令执行。
- 如果当前状态不支持，应给出明确提示，而不是只回显输入。

处理记录：

- 将 Node TUI 的 `/commands` 作为 `/help` 本地别名处理，setup-required 阶段不再落到 slash worker 或普通回显。
- 增加 `createSlashHandler` 回归测试，确认 `/commands` 打开本地帮助面板且不请求后端。

验证：

- `npm --prefix terminal-ui test -- createSlashHandler.test.ts slashParity.test.ts completionApply.test.ts`
