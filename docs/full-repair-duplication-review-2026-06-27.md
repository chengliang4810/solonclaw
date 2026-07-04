# 阶段 1.4 代码重复检测与复用优化记录

日期：2026-06-27

## 已完成的复用改造

- 运行时配置解析器定位：复用统一配置定位入口，减少配置读取分叉。
- 控制台配置扁平化：复用配置扁平化逻辑，避免控制台输出重复维护字段展开规则。
- 命令容量格式化：复用容量展示格式化逻辑，保持命令输出一致。
- 工具调用标识追踪：复用工具调用 ID 捕获逻辑，减少 ReAct 追踪路径重复。
- 渠道配置定位：复用国内渠道配置查找逻辑。
- 文件工具名判断：复用文件工具名称识别逻辑。
- 匹配引号清理：复用成对引号剥离逻辑。
- 输入流预览读取：复用有界输入流预览读取逻辑。
- QQBot JSON 请求执行：`postJson` 与 `putJson` 统一委托到 `requestJson`。
- 助手消息文本提取：会话搜索摘要复用 `MessageSupport.assistantText`。
- 助手信息量评分：SQLite 会话去重复用 `MessageSupport.assistantInformationScore`。
- 助手工具调用签名比较：SQLite 会话去重复用 `MessageSupport.sameAssistantToolCalls`。
- 助手可见文本比较：LLM 网关复用 `MessageSupport.sameVisibleContent`。
- 工具全集策略：`DefaultDelegationService` 与 `DefaultToolRegistry` 复用 `AgentRuntimePolicy.knownToolNames()`，同时补齐 dashboard/manage 工具，避免委托子会话在 toolsets 限制下漏关工具。
- 工具注册表构造链：短构造函数逐层委托已有长构造函数，消除重复的可选依赖 null 参数展开。
- 运行仓储测试基类：`QuietContextCollectorTest` 与 `RunStateCollectorTest` 复用 `UnsupportedAgentRunRepository`，只保留各自采集器会调用的方法。
- 危险命令审批测试支撑：`DangerousCommandApprovalServiceTest`、`DangerousCommandCredentialPolicyTest`、`DangerousCommandCodeAndNetworkPolicyTest`、`DangerousCommandFilePolicyTest` 与 `DangerousCommandGatewayApprovalTest` 复用 `DangerousCommandApprovalTestSupport`，删除拆分后复制的 Tirith、DNS、trace 与安全断言 helper。
- 仪表盘诊断测试支撑：`DashboardDiagnosticOutputTest` 与 `DashboardSecurityProbeDiagnosticTest` 复用 `DashboardDiagnosticTestSupport`，删除拆分后复制的诊断服务替身、仓储替身与探针断言 helper。
- 定时任务测试支撑：`CronjobToolsSchedulerTest` 与 `DefaultCronSchedulerTest` 复用 `CronSchedulerTestSupport`，删除重复的定时任务记录构造、字符串重复、工具方法定位、工具列表与参数元数据 helper。
- 仪表盘投递服务测试桩：`DashboardStatusServiceTest` 复用 `DashboardDiagnosticTestSupport.FixedDeliveryService`，删除本地只支持单状态的重复桩。
- 模型价格字段写入：`AppConfigLoader` 与 `PriceCatalog` 复用 `ModelPrice.applyTokenPrice(...)`，保留各自解析入口，只统一 `input`、`output`、`cache_read`、`cache_write`、`reasoning` 的字段写入语义。
- 终端参数切分：`TerminalSetupCommands` 与 `TuiRuntimeProtocolService` 复用 `BasicValueSupport.shellTokens(...)`，只合并不处理转义字符的单双引号切分逻辑。
- 定时任务短重载转发：`CronjobTools` 无 `limit/reason` 的短重载改为复用已有中间重载，减少一份长参数 null 展开。
- 定时任务状态重载转发：`CronjobTools` 的无技能增删字段重载复用 `cronjobWithStateDefaults(...)` / `cronjobWithState(...)`，保留公开重载签名和 `@ToolMapping` 主入口，消除当前 25 行阈值下的 CronjobTools 重复块。
- 终端工具重载元数据：`SolonClawShellSkill` 仅保留真正 `@ToolMapping` 方法上的 `@Param` 元数据，删除两个 Java 便捷转发重载上的重复注解块，方法签名和行为不变。
- 命令服务构造器链：`DefaultCommandService` 删除未直接使用的 public 桥接构造器，只保留单一完整构造器，测试辅助调用点显式传入原本桥接层补齐的默认依赖。

## 当前保留的重复项

- `YuanbaoChannelAdapter`、`WeComChannelAdapter`、`QQBotChannelAdapter` 的 `disconnect()` 只剩状态字段清理薄包装，已复用 `ChannelConnectionSupport.disconnect(...)`，继续抽象会增加不必要的回调或继承约束。
- `DomesticQrSetupService.fail(...)` 与 `WeixinQrSetupService.fail(...)` 的私有 `TicketState` 类型不同，强制复用需要引入接口或基类，代码会更重。
- `MemoryProvider.syncTurn(...)` 与 `MemoryManager.syncTurn(...)` 是两个接口的默认协议方法，保留重复比引入额外父接口更清晰。
- `AgentRunSupervisor.extractText(...)`、`DefaultConversationOrchestrator.extractText(...)`、`SolonAiLlmGateway.extractText(...)` 的日志语义和 `<think>` 处理不同，未合并。
- `TerminalSetupCommands.applyProviderTemplateDefaults(...)` 面向两个不同请求对象，缺少共同协议；为消除相似代码引入接口或 adapter 不划算。
- 敏感键判断分布在配置、工具预览、MCP 展示等不同安全边界，语义不完全相同，未统一。
- `TerminalSessionBrowser.shellTokens(...)` 支持反斜杠转义，与 `BasicValueSupport.shellTokens(...)` 的无转义语义不同，暂不强行合并。
- `CronjobTools.cronjob(...)` 仍保留公开重载作为测试和 Java 调用入口，但状态字段桥接已收口到私有 helper；更大范围的内部请求对象化暂缓，避免改动主 `@ToolMapping` 签名。
- `MemoryView.vue` 与 `PersonaFileView.vue`、`PersonaDiaryView.vue` 与 `SkillsView.vue` 的 UI 相似点已确认，但涉及前端组件抽取和视觉回归，先作为后续前端原子项处理。

## 2026-06-28 扫描器噪声收口

- 重复检测脚本已忽略 `package` / `import` 前言行，避免把同类测试文件的依赖清单误报为代码重复。
- 当前 `--min-lines 40` 扫描无重复输出。

## 验证

- `python3 scripts/check-code-duplication.selftest.py`
- `python3 scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`
- `mvn -Dskip.web.build=true -DskipTests compile`
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest,DelegationServiceTest test`
- `mvn -Dskip.web.build=true -Dtest=QuietContextCollectorTest,RunStateCollectorTest test`
- `mvn -Dskip.web.build=true -Dtest=DelegationServiceTest test`
- `DomesticChannelEnhancementTest`
- `SessionSearchServiceTest`
- `MessageSequenceRepairTest`
- `SqliteAgentSessionTest`
- `SolonAiOwnedReActLoopTest`
- `git diff --check`
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`

## 2026-06-27 追加验证

- `mvn -Dskip.web.build=true -Dtest=DangerousCommandApprovalServiceTest,DangerousCommandCredentialPolicyTest,DangerousCommandCodeAndNetworkPolicyTest,DangerousCommandFilePolicyTest,DangerousCommandGatewayApprovalTest test`
- `python3 scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`
- `mvn -Dskip.web.build=true -Dtest=DashboardDiagnosticOutputTest,DashboardSecurityProbeDiagnosticTest test`
- `mvn -Dskip.web.build=true -Dtest=CronjobToolsSchedulerTest,DefaultCronSchedulerTest test`
- `mvn -Dskip.web.build=true -DskipTests -Dmaven.compiler.showWarnings=true test-compile`
- `mvn -Dskip.web.build=true -Dtest=DashboardStatusServiceTest test`
- `mvn -Dskip.web.build=true -Dtest=UsagePricingTest,ProviderDisplayGroupingTest,DashboardStatusServiceTest test`
- `mvn -Dskip.web.build=true -DskipTests test-compile`
- `mvn -Dskip.web.build=true -Dtest=RuntimeSetupServiceTest test`
- `mvn -Dskip.web.build=true -Dtest=CronjobToolsSchedulerTest,DefaultCronSchedulerTest test`
- `python3 scripts/check-code-duplication.selftest.py`
- `python3 scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`
- `python3 scripts/check-code-duplication.py --report-only --min-lines 25 src/main/java/com/jimuqu/solon/claw/tool/runtime/SolonClawShellSkill.java`
- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=SolonClawShellSkillTest,ToolRegistryProcessToolsTest test`

## 2026-07-04 追加验证

- `mvn -Dskip.web.build=true -Dtest=CronjobToolsSchedulerTest test`
- `python scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`
- `mvn -Dskip.web.build=true -DskipTests compile`
- `mvn -Dskip.web.build=true -Dtest=DefaultCronSchedulerTest,PluginRuntimeIntegrationTest,ProactiveCommandTest,SkillsHubCommandTest,VersionUpdateCommandTest test`
- `python scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java`
