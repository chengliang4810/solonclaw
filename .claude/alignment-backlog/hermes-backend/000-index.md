# Hermes 后端对齐 Backlog

生成时间：2026-06-02

## 扫描状态

- 状态：ready
- 对标仓库：`/Users/chengliang/code-repositories/hermes-agent`
- 当前仓库：`/Users/chengliang/code-projects/jimuqu-agent`
- 本轮候选数：21

## 已完成并推送到 dev

- 暂无（本轮待实现后回填）

## 推荐首批并行开发

- [023-health-detailed-runtime](023-health-detailed-runtime.md) — Expand detailed health with richer runtime snapshot（risk: low, parallelSafe: true）
- [028-config-drift-diagnostics](028-config-drift-diagnostics.md) — Add config drift and unknown-field diagnostics（risk: low, parallelSafe: true）
- [032-approval-timeout-denied-outcome](032-approval-timeout-denied-outcome.md) — 结构化审批超时与拒绝结果（risk: medium, parallelSafe: true）
- [038-skill-ignore-scan-filter](038-skill-ignore-scan-filter.md) — Skill scan ignore-file filtering（risk: low, parallelSafe: true）
- [provider-aware-model-list-url-gap](provider-aware-model-list-url-gap.md) — provider-aware model list URL resolution（risk: low, parallelSafe: true）
- [canonical-usage-pricing-gap](canonical-usage-pricing-gap.md) — 归一 usage/pricing 计费桶与来源元数据（risk: medium, parallelSafe: true）

## 高优先级候选

- [023-health-detailed-runtime](023-health-detailed-runtime.md) — Expand detailed health with richer runtime snapshot（risk: low, parallelSafe: true）
- [028-config-drift-diagnostics](028-config-drift-diagnostics.md) — Add config drift and unknown-field diagnostics（risk: low, parallelSafe: true）
- [041-memory-provider-rewound-hook](041-memory-provider-rewound-hook.md) — Memory provider session rewind notification（risk: medium, parallelSafe: false）
- [039-skill-usage-atomic-sidecar](039-skill-usage-atomic-sidecar.md) — Skill usage sidecar atomic writes and cross-process locking（risk: medium, parallelSafe: false）
- [040-skill-hub-path-guard](040-skill-hub-path-guard.md) — Skill hub install/uninstall path guard（risk: medium, parallelSafe: true）
- [016-cron-execution-context-isolation](016-cron-execution-context-isolation.md) — 隔离 Cron 执行上下文并补齐 headless 执行路径（risk: medium, parallelSafe: true）
- [032-approval-timeout-denied-outcome](032-approval-timeout-denied-outcome.md) — 结构化审批超时与拒绝结果（risk: medium, parallelSafe: true）
- [033-approval-context-session-isolation](033-approval-context-session-isolation.md) — 绑定审批上下文避免并发串线（risk: medium, parallelSafe: true）
- [model-capability-surface-gap](model-capability-surface-gap.md) — 补齐剩余模型能力标记和支持面（risk: medium, parallelSafe: false）
- [canonical-usage-pricing-gap](canonical-usage-pricing-gap.md) — 归一 usage/pricing 计费桶与来源元数据（risk: medium, parallelSafe: true）
- [034-gateway-undo-soft-rewind](034-gateway-undo-soft-rewind.md) — Use soft-delete rewind for undo（risk: medium, parallelSafe: false）
- [035-gateway-shutdown-drain](035-gateway-shutdown-drain.md) — Drain active runs before shutdown interrupt（risk: high, parallelSafe: false）
- [036-restart-notification-marker](036-restart-notification-marker.md) — Persist restart requester marker and send one-time recovery notice（risk: medium, parallelSafe: false）

## 中优先级候选

- [029-runtime-refresh-failure-record](029-runtime-refresh-failure-record.md) — Persist the latest runtime refresh failure（risk: low, parallelSafe: true）
- [030-browser-automation-doctor](030-browser-automation-doctor.md) — Teach doctor to diagnose browser automation prerequisites（risk: medium, parallelSafe: true）
- [031-doctor-issue-summary](031-doctor-issue-summary.md) — Summarize doctor issues and next actions（risk: low, parallelSafe: true）
- [038-skill-ignore-scan-filter](038-skill-ignore-scan-filter.md) — Skill scan ignore-file filtering（risk: low, parallelSafe: true）
- [020-tirith-security-diagnostics](020-tirith-security-diagnostics.md) — 对齐 Tirith 安全诊断与审计面（risk: low, parallelSafe: true）
- [provider-aware-model-list-url-gap](provider-aware-model-list-url-gap.md) — provider-aware model list URL resolution（risk: low, parallelSafe: true）
- [media-provider-metadata-surface-gap](media-provider-metadata-surface-gap.md) — 扩展多模态附件与媒体 provider 元数据面（risk: medium, parallelSafe: false）
- [037-mcp-discovery-nonblocking](037-mcp-discovery-nonblocking.md) — Make MCP discovery nonblocking at startup（risk: medium, parallelSafe: true）

## 疑似耦合 / 避免并行

- `023-health-detailed-runtime` ↔ `029-runtime-refresh-failure-record` — 疑似同一条 runtime/status 观测链路：023 要扩展 /health/detailed 的 runtime/gateway 快照，029 要把 refresh last-failure 持久化后暴露给 status/health/doctor。两者都会牵动 /Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/web/DashboardStatusService.java，建议先统一稳定 JSON key 与 runtime state DTO。
- `030-browser-automation-doctor` ↔ `031-doctor-issue-summary` — 030 是新增 browser automation doctor 检查项，031 是汇总 doctor issues/next actions；前者的输出很可能必须落到后者的 issue schema 中。若并行做，容易重复定义 severity、nextAction、availability 等字段。
- `031-doctor-issue-summary` ↔ `020-tirith-security-diagnostics` — 020 的安全诊断/audit summary 如果进入 doctor 表面，应复用 031 的统一 issue summary 格式；否则 /Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java 与 DashboardGatewayDoctorService 可能出现两套摘要语义。
- `032-approval-timeout-denied-outcome` ↔ `033-approval-context-session-isolation` — 两者都集中修改 /Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandApprovalService.java 和 /Users/chengliang/code-projects/jimuqu-agent/src/test/java/com/jimuqu/solon/claw/DangerousCommandApprovalServiceTest.java；timeout/deny outcome 与 session binding 应作为同一审批状态机设计，避免并行冲突。
- `034-gateway-undo-soft-rewind` ↔ `041-memory-provider-rewound-hook` — 041 的 rewound=true hook 依赖 034 的 undo/soft rewind 真实发生点；二者都是 transcript rewind 语义。建议先实现或明确 034 的 rewind 边界，再接 041 的 MemoryProvider 通知。
- `035-gateway-shutdown-drain` ↔ `036-restart-notification-marker` — 都围绕 gateway restart/shutdown 生命周期，且共同涉及 /Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/gateway/service/GatewayRestartCoordinator.java。drain 顺序、marker 保存和 one-shot 通知生命周期会互相影响。
- `038-skill-ignore-scan-filter` ↔ `040-skill-hub-path-guard` — 两者都可能修改 /Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/skillhub/service/DefaultSkillGuardService.java，并且都涉及 skill tree traversal/path normalization 规则；建议不要同时派给两个并行分支。
- `canonical-usage-pricing-gap` ↔ `media-provider-metadata-surface-gap` — 不是完全重复，但都提到 usage metadata 在媒体/模型结果 envelope 中的保留。audio/image/transcription usage 字段应优先对齐 canonical usage buckets，避免媒体 provider 另起一套计费字段。

## 发现但暂不纳入本轮的范围提醒

- Dashboard 前端/API client/类型联动：候选 localTargets 几乎全是后端，缺少 /Users/chengliang/code-projects/jimuqu-agent/web/src/api/client.ts、/Users/chengliang/code-projects/jimuqu-agent/web/src/views/ 等 Dashboard-first 配置与诊断页面落地项。
- 国内消息渠道适配器细节：没有单列 feishu、dingtalk、wecom、weixin、qqbot、yuanbao 的授权、验签、消息格式、媒体 ingress、重连、限流和命令兼容 gap。
- Agent 主循环与上下文治理：候选较少覆盖 /Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/engine/ 下的工具调用循环、流式 tool call、上下文预算/压缩、pending run 恢复与会话搜索。
- 具体多模态执行链路：已有 pricing/media metadata 候选，但缺少图像理解/生成、TTS、独立语音转写的端到端 provider 实现、Dashboard 配置、API 与 usage 贯通 gap。
- SQLite schema/migration 与兼容性：undo soft-delete、restart marker、runtime status/last-failure 都可能需要持久化或迁移，但列表里没有独立的 schema migration/backward compatibility 候选。
- CLI/TUI/console 用户入口：候选集中在 backend service/Web 表面，缺少 /Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/cli/ 与 /Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/tui/ 的命令和输出一致性 gap。
- 通用工具安全与审计：除 approval、Tirith、skill hub 外，文件、Shell/Python/JavaScript、process registry、web fetch/search 等工具的统一安全审计和可观测性没有单独覆盖。

## 跳过项

- 排除：前端、TUI、WebUI surfaces
- 排除：sms、webhook、海外渠道
- 排除：OpenAI 兼容 API Server、Profiles、多实例/多租户、worktree
