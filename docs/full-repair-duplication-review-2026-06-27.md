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

## 当前保留的重复项

- `YuanbaoChannelAdapter`、`WeComChannelAdapter`、`QQBotChannelAdapter` 的 `disconnect()` 只剩状态字段清理薄包装，已复用 `ChannelConnectionSupport.disconnect(...)`，继续抽象会增加不必要的回调或继承约束。
- `DomesticQrSetupService.fail(...)` 与 `WeixinQrSetupService.fail(...)` 的私有 `TicketState` 类型不同，强制复用需要引入接口或基类，代码会更重。
- `MemoryProvider.syncTurn(...)` 与 `MemoryManager.syncTurn(...)` 是两个接口的默认协议方法，保留重复比引入额外父接口更清晰。
- `AgentRunSupervisor.extractText(...)`、`DefaultConversationOrchestrator.extractText(...)`、`SolonAiLlmGateway.extractText(...)` 的日志语义和 `<think>` 处理不同，未合并。
- `TerminalSetupCommands.applyProviderTemplateDefaults(...)` 面向两个不同请求对象，缺少共同协议；为消除相似代码引入接口或 adapter 不划算。
- 敏感键判断分布在配置、工具预览、MCP 展示等不同安全边界，语义不完全相同，未统一。
- `CronjobToolsSchedulerTest` / `DefaultCronSchedulerTest` 与 `DashboardDiagnosticOutputTest` / `DashboardSecurityProbeDiagnosticTest` 当前剩余重复检测命中均为 import 头部，属于同功能测试自然共享的依赖清单；为消除此类命中拆分 import 或包装类型没有实际维护收益。

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
