# 无人值守全面修复阶段 1.1 原子级功能缺陷报告

生成时间：2026-06-30

## 对应外部对标能力点

- 内置工具与安全审批：文件、终端、Web fetch/search、MCP 工具在真实用户环境中应按审批和安全策略稳定运行。
- 本地 TUI 交互层：终端用户通过 TUI 执行审批、命令和诊断时不应因 Windows shell 差异失败。
- Dashboard-first 诊断：测试和诊断输出应能在 Windows 环境下稳定生成，避免乱码、路径归一化和文件锁误判。

## 审计范围

本报告基于当前工作树 `D:\projects\jimuqu-agent-unattended-target-execution`、分支 `feat/unattended-target-execution`、提交 `225f41675292f6b39053c17c6223f8c8cae4a850`。

已执行关键命令：

```bash
mvn -q -DskipTests package
mvn -q test
npm --prefix terminal-ui run lint -- --quiet
python scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages
```

## BUG-009：代理/测试环境下公共域名解析到 198.18.* 后被 URL 安全策略误判为私有地址

状态：已修复

影响范围：

- Web fetch/search 内置工具。
- MCP resource/prompt 工具中的外部 URL 读取。
- execute_code 暴露的 Web RPC helper。
- 使用本机代理或 fake-ip DNS 的用户环境。

当前事实：

- `mvn -q test` 中多个测试因为 `example.com`、`example.invalid`、`api.search.brave.com` 被解析到 `198.18.*` 而触发 `BLOCKED: URL 安全策略阻止访问`。
- 当前机器网络路径显示 Maven Central 也经过 `Mihomo` 接口，说明 198.18.* 属于本机代理环境常见解析结果。
- 严格安全策略需要继续阻止真实内网和元数据地址，但不能把公共域名在代理 fake-ip 模式下的解析结果简单等同为用户请求私有网络。

源码/报告证据：

- `target/surefire-reports/com.jimuqu.solon.claw.ToolRegistryWebAndCodeToolsTest.txt`
- `target/surefire-reports/com.jimuqu.solon.claw.McpRuntimeServiceTest.txt`
- `target/surefire-reports/com.jimuqu.solon.claw.DangerousCommandGatewayApprovalTest.txt`
- `target/surefire-reports/com.jimuqu.solon.claw.tool.runtime.SolonClawExecuteCodeWebRpcTest.txt`

建议修复方向：

- 在 URL 安全策略中明确区分用户显式请求的 literal IP、DNS 解析出的代理 fake-ip、以及最终连接目标。
- 对 198.18.0.0/15 保持默认谨慎，但允许受信任代理模式下的公共域名通过，或提供当前 `solonclaw` 命名的显式安全配置。
- 补充测试覆盖 fake-ip 代理解析场景，避免真实内网地址被误放行。

处理记录：

- 已将 DNS 解析得到的 `198.18.0.0/15` 代理 fake-ip 与用户显式请求的 IP 字面量区分处理。
- 公共域名解析到 fake-ip 时不再触发内网硬阻断，仍会进入 `network_external_operation` 外部网络审批。
- `https://198.18.0.43/...` 这类 IP 字面量仍按内网/保留地址阻断。
- `TerminalUiApprovalRespondTest.directShellApprovalRequeuesNextSecurityPolicyWhenReplayIsBlockedAgain` 已能进入网络审批队列，但仍受 BUG-011 的 Windows 路径字符串断言影响，留到 BUG-011 单独修复。

## BUG-010：TUI 直接 Shell 审批路径使用 `printf` 导致 Windows 用户审批后命令仍失败

状态：已修复

影响范围：

- TUI 审批命令 `/approve`、审批选择器和 session 级审批复用。
- Windows 用户在 TUI 中批准写文件或链式安全策略后，命令可能因 shell 命令不可用而失败。

当前事实：

- `TerminalUiApprovalRespondTest` 多个失败显示审批通过后执行 `printf ... > file`。
- Windows shell 返回 `'printf' is not recognized as an internal or external command`。
- 用户看到审批成功但命令失败，真实交互体验不符合“批准后继续执行”的预期。

源码/报告证据：

- `target/surefire-reports/com.jimuqu.solon.claw.TerminalUiApprovalRespondTest.txt`
- 失败用例包括 `approvalRespondRunsDirectShellCommandAfterSecurityPolicyApproval`、`directShellApprovalSelectorRunsTheSelectedPendingCommand`、`directShellSessionApprovalAllowsNextOutsideWorkspaceWrite`。

建议修复方向：

- 测试夹具改用跨平台 Java/Node/PowerShell 可用的写文件命令，或通过项目已有工具抽象写入，避免 shell 内建差异。
- 若生产路径会生成 shell 片段，应在 Windows 下使用兼容命令或拒绝生成不可用命令。
- 添加 Windows 与类 Unix 命令兼容性断言，保证 TUI 审批回放真正可执行。

处理记录：

- 已将 TUI direct shell 审批测试中的 `printf ... > file` 写入夹具改为跨平台写入命令，Windows 下使用 `echo value>file` 避免依赖 POSIX shell。
- 已将文件内容断言调整为校验目标文本前缀，兼容不同 shell 的换行行为，同时保留审批回放执行成功断言。
- 已验证 TUI direct shell 审批相关 4 个方法和 Dashboard 诊断输出方法通过。

## BUG-011：路径归一化在审批回放中丢失 Windows 分隔符

状态：待修复

影响范围：

- 危险命令审批 token。
- 文件路径安全策略和命令 URL 检查顺序。
- Windows 下路径审计和复放一致性。

当前事实：

- `SecurityPolicyServiceTest.shouldConsumeApprovedCommandPathTokenBeforeCheckingCommandUrls` 失败信息显示 initial path 为 `D:projectsjimuqu-agent...`，replay path 为正常 `D:\projects\...`。
- 初始路径丢失了反斜杠分隔符，导致审批 token 无法匹配复放路径。

源码/报告证据：

- `target/surefire-reports/com.jimuqu.solon.claw.SecurityPolicyServiceTest.txt`

建议修复方向：

- 审批 token 的路径规范化应统一使用 `Path`/`toRealPath`/标准 URI 表示，不做会吞掉反斜杠的字符串正则处理。
- 回归测试应同时覆盖 Windows drive path 和普通相对路径。

## BUG-012：TUI lint 门禁当前失败，阻断无人值守提交前质量验证

状态：已修复

影响范围：

- 本地 TUI 质量门禁。
- 阶段 1.3 未使用变量和 lint 复核。
- 后续 TUI E2E 修复提交的可信度。

当前事实：

- `npm --prefix terminal-ui run lint -- --quiet` 当前失败 7 个 error。
- 5 个 error 是 import/export 排序，可由 ESLint 自动修复。
- 2 个 error 是 `terminal-ui/src/components/prompts.tsx` 中 ANSI 控制字符正则触发 `no-control-regex`。

证据：

```text
terminal-ui/src/__tests__/brandingMcpCount.test.ts:7 perfectionist/sort-named-imports
terminal-ui/src/components/activeSessionSwitcher.tsx:25 perfectionist/sort-named-imports
terminal-ui/src/components/activeSessionSwitcher.tsx:42 perfectionist/sort-named-exports
terminal-ui/src/components/channelSetup.tsx:11 perfectionist/sort-imports
terminal-ui/src/components/channelSetupInput.ts:12 perfectionist/sort-named-imports
terminal-ui/src/components/prompts.tsx:28 no-control-regex
terminal-ui/src/components/prompts.tsx:28 no-control-regex
```

建议修复方向：

- 先用 ESLint 自动修复排序类 error。
- 对 ANSI 正则使用具名常量和局部 ESLint 说明，或改成字符码拼接方式，避免规则误报并保持语义清晰。
- 修复后运行 `npm --prefix terminal-ui run lint -- --quiet`、`npm --prefix terminal-ui run type-check`。

处理记录：

- 已用 ESLint 自动修复 import/export 排序问题。
- 已把 ANSI ESC 控制字符从正则字面量调整为 `String.fromCharCode(27)` 生成的 `RegExp` 常量，避免关闭 `no-control-regex`。
- 已验证 `npm --prefix terminal-ui run lint -- --quiet`、`npm --prefix terminal-ui run type-check`、`npm --prefix terminal-ui run build` 和相关 Vitest 测试通过。

## BUG-013：Windows 非管理员环境下符号链接和日志文件锁测试不稳定

状态：待修复

影响范围：

- Skill bundle 路径逃逸防护测试。
- 滚动日志 appender 外部 rename/delete 测试。
- Windows 普通用户运行完整测试套件的可信度。

当前事实：

- `SkillBundlePathSupportTest.shouldRejectSymlinkEscapedHubTargets` 因 Windows 普通用户无创建符号链接权限报错。
- `WatchedRollingFileAppenderTest` 因文件被另一个进程使用，无法 rename/delete。
- 这类失败使完整 `mvn test` 无法作为无人值守质量门禁。

源码/报告证据：

- `target/surefire-reports/com.jimuqu.solon.claw.skillhub.support.SkillBundlePathSupportTest.txt`
- `target/surefire-reports/com.jimuqu.solon.claw.WatchedRollingFileAppenderTest.txt`

建议修复方向：

- 符号链接测试在 Windows 权限不足时显式跳过或使用 JUnit assumption，同时保留具备权限环境下的逃逸防护断言。
- 日志文件测试应关闭 appender/释放句柄后再执行 rename/delete，或使用 Windows 兼容的轮询等待文件释放。

## BUG-014：配置模板/诊断输出在当前测试环境出现中文乱码，影响断言和用户可读性

状态：待修复

影响范围：

- 配置模板 scope 暴露测试。
- Dashboard 诊断输出预览。
- Windows 控制台和测试报告中的中文可读性。

当前事实：

- `AppConfigPathNormalizationTest` 输出中中文注释呈现为 mojibake。
- `DashboardDiagnosticOutputTest` 期望包含 `tail-preview token=***`，实际输出是 `printf` 命令失败后的乱码片段。
- 当前 Maven 环境显示平台编码为 GBK，而项目大量用户可见文本为中文。

源码/报告证据：

- `target/surefire-reports/com.jimuqu.solon.claw.AppConfigPathNormalizationTest.txt`
- `target/surefire-reports/com.jimuqu.solon.claw.DashboardDiagnosticOutputTest.txt`
- `mvn -v` 显示 `platform encoding: GBK`

建议修复方向：

- 测试进程和外部命令输出统一按 UTF-8 解码，必要时在 surefire 或执行器中显式设置。
- 避免诊断测试依赖 Windows 不支持的 `printf`。
- Dashboard 诊断输出应对命令失败和编码异常给出稳定、可读、脱敏的错误文本。

处理记录：

- 已将 Dashboard 管理进程诊断输出测试的大输出命令改为跨平台实现，Windows 下不再依赖 POSIX `printf`。
- 中文乱码和全局测试编码问题仍待单独修复，本项保持待修复。

## 当前完整测试摘要

`mvn -q test` 结果：

```text
Tests run: 2088, Failures: 23, Errors: 8, Skipped: 17
```

失败报告集中在：

- `AppConfigPathNormalizationTest`
- `DangerousCommandCodeAndNetworkPolicyTest`
- `DangerousCommandFilePolicyTest`
- `DangerousCommandGatewayApprovalTest`
- `DashboardDiagnosticOutputTest`
- `GatewayRuntimeStatusServiceTest`
- `McpRuntimeServiceTest`
- `SecurityPolicyServiceTest`
- `SkillBundlePathSupportTest`
- `TerminalUiApprovalRespondTest`
- `SolonClawExecuteCodeWebRpcTest`
- `ToolRegistryWebAndCodeToolsTest`
- `WatchedRollingFileAppenderTest`

## 后续处理顺序建议

1. 先修复 `terminal-ui` lint error，恢复 TUI 质量门禁。
2. 再修复 Windows shell 与路径归一化问题，恢复 TUI 审批相关测试。
3. 单独设计 fake-ip 代理环境下的 URL 安全策略，避免误放行真实内网。
4. 最后处理 Windows 测试兼容性和编码问题，逐步恢复完整 `mvn test`。
