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

## 当前保留的重复项

- `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java:257-329` 与 `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java:440-501` 存在工具注册描述重复，后续应优先抽取同类 schema 构造或注册辅助方法。
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java:260-332`、`443-504`、`527-589`、`613-677` 存在多段工具 schema 重复，后续应与上一个 `DefaultToolRegistry` 重复项合并处理。
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java:593-632` 与 `859-898` 存在 40 行级别精确重复，后续应检查是否可复用参数定义。
- `src/test/java/com/jimuqu/solon/claw/QuietContextCollectorTest.java:204-251` 与 `src/test/java/com/jimuqu/solon/claw/RunStateCollectorTest.java:383-430` 存在测试夹具重复，优先级低于生产代码重复。
- `YuanbaoChannelAdapter`、`WeComChannelAdapter`、`QQBotChannelAdapter` 的 `disconnect()` 只剩状态字段清理薄包装，已复用 `ChannelConnectionSupport.disconnect(...)`，继续抽象会增加不必要的回调或继承约束。
- `DomesticQrSetupService.fail(...)` 与 `WeixinQrSetupService.fail(...)` 的私有 `TicketState` 类型不同，强制复用需要引入接口或基类，代码会更重。
- `MemoryProvider.syncTurn(...)` 与 `MemoryManager.syncTurn(...)` 是两个接口的默认协议方法，保留重复比引入额外父接口更清晰。
- `AgentRunSupervisor.extractText(...)`、`DefaultConversationOrchestrator.extractText(...)`、`SolonAiLlmGateway.extractText(...)` 的日志语义和 `<think>` 处理不同，未合并。
- `TerminalSetupCommands.applyProviderTemplateDefaults(...)` 面向两个不同请求对象，缺少共同协议；为消除相似代码引入接口或 adapter 不划算。
- 敏感键判断分布在配置、工具预览、MCP 展示等不同安全边界，语义不完全相同，未统一。

## 验证

- `python3 scripts/check-code-duplication.selftest.py`
- `python3 scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`
- `mvn -Dskip.web.build=true -Dtest=DelegationServiceTest test`
- `DomesticChannelEnhancementTest`
- `SessionSearchServiceTest`
- `MessageSequenceRepairTest`
- `SqliteAgentSessionTest`
- `SolonAiOwnedReActLoopTest`
- `git diff --check`
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`
