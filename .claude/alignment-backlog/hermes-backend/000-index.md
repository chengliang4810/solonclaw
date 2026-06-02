# Hermes 后端对齐 Backlog

生成时间：2026-06-02

## 扫描状态

- 状态：ready
- 对标仓库：`/Users/chengliang/code-repositories/hermes-agent`
- hermes-agent 本轮基线 SHA：`a5371b3e680e213441be104117c0aa2c008f5188`
- 当前仓库：`/Users/chengliang/code-projects/jimuqu-agent`
- 本轮候选数：22
- 本轮策略：选择 4 个低冲突、测试明确的后端能力点实现；其余 in-scope 候选本轮标记为 deferred，下一轮继续。

## 已完成并推送到 dev

- `32a0b47f` — 扩展详细健康运行时快照 / Expand detailed health runtime snapshot
- `0b8950b1` — 增加配置漂移诊断 / Add config drift diagnostics
- `c9717331` — 增加 Tirith 安全诊断摘要 / Add Tirith security diagnostics summary
- `6a249913` — 归一 usage pricing 计费桶 / Normalize usage pricing billing buckets

## 本轮完成并推送

- `6c8f299f` — [040-skill-hub-path-guard](040-skill-hub-path-guard.md) — Skill hub install/uninstall path guard（status: done）
- `342e0b73` — [model-capability-surface-gap](model-capability-surface-gap.md) — 补齐剩余模型能力标记和支持面（status: done）
- `e8d584c8` — [029-runtime-refresh-failure-record](029-runtime-refresh-failure-record.md) — Persist the latest runtime refresh failure（status: done）
- `38da0e58` — [037-mcp-discovery-nonblocking](037-mcp-discovery-nonblocking.md) — Make MCP discovery nonblocking at startup（status: done）

## 本轮新增 backlog

- [042-video-generation-runtime-tool-wiring](042-video-generation-runtime-tool-wiring.md) — 补齐视频生成运行时/工具接线（status: deferred, risk: low-medium, reason: 轻量复扫发现 Hermes 已有 video generation dispatch/picker 测试面，当前项目仅有 provider 抽象，无运行时 service/tool wiring；不纳入本轮批次）

## Deferred（in-scope，下一轮继续）

- [016-cron-execution-context-isolation](016-cron-execution-context-isolation.md) — 隔离 Cron 执行上下文并补齐 headless 执行路径（reason: 本轮选择低冲突 runtime/MCP/skill/model 批次，Cron 执行上下文留到下一轮）
- [030-browser-automation-doctor](030-browser-automation-doctor.md) — Teach doctor to diagnose browser automation prerequisites（reason: 与 doctor summary 输出耦合，留到下一轮统一诊断 schema）
- [031-doctor-issue-summary](031-doctor-issue-summary.md) — Summarize doctor issues and next actions（reason: 与 browser/security doctor surface 耦合，留到下一轮统一摘要格式）
- [032-approval-timeout-denied-outcome](032-approval-timeout-denied-outcome.md) — 结构化审批超时与拒绝结果（reason: 审批状态机改动面独立且与 033 耦合，本轮不混入）
- [033-approval-context-session-isolation](033-approval-context-session-isolation.md) — 绑定审批上下文避免并发串线（reason: 与 032 同属审批状态机，下一轮合并设计）
- [034-gateway-undo-soft-rewind](034-gateway-undo-soft-rewind.md) — Use soft-delete rewind for undo（reason: 与 memory rewound hook 依赖同一 rewind 语义，下一轮处理）
- [035-gateway-shutdown-drain](035-gateway-shutdown-drain.md) — Drain active runs before shutdown interrupt（reason: gateway restart/shutdown 生命周期风险高，下一轮单独处理）
- [036-restart-notification-marker](036-restart-notification-marker.md) — Persist restart requester marker and send one-time recovery notice（reason: 与 shutdown drain 生命周期耦合，下一轮处理）
- [038-skill-ignore-scan-filter](038-skill-ignore-scan-filter.md) — Skill scan ignore-file filtering（reason: 与本轮 040 同属 skill traversal/path 规则，但目标文件不同；为避免混改，下一轮处理）
- [039-skill-usage-atomic-sidecar](039-skill-usage-atomic-sidecar.md) — Skill usage sidecar atomic writes and cross-process locking（reason: 涉及 sidecar lock/atomic write，下一轮单独测试）
- [041-memory-provider-rewound-hook](041-memory-provider-rewound-hook.md) — Memory provider session rewind notification（reason: 依赖 undo/rewind 真实发生点，待 034 后处理）
- [media-provider-metadata-surface-gap](media-provider-metadata-surface-gap.md) — 扩展多模态附件与媒体 provider 元数据面（reason: 与更大的媒体 provider execution chain 相关，本轮只处理模型 metadata surface）
- [provider-aware-model-list-url-gap](provider-aware-model-list-url-gap.md) — provider-aware model list URL resolution（reason: 当前代码已有 provider-aware helper；本轮不扩展测试矩阵，下一轮复核后决定 done/deferred）
- [042-video-generation-runtime-tool-wiring](042-video-generation-runtime-tool-wiring.md) — 补齐视频生成运行时/工具接线（reason: 本轮轻量复扫新增，下一轮处理）

## Out-of-scope / 跳过项

- 前端、TUI、WebUI surfaces：本轮只处理后端能力；必要 Dashboard 后端 API surface 可做，前端页面不纳入。
- sms、webhook、海外渠道：当前项目 CLAUDE.md 明确不做或默认不做。
- OpenAI 兼容 API Server、Profiles、多实例/多租户、worktree：当前项目 CLAUDE.md 明确不做。

## 疑似耦合 / 避免并行

- `023-health-detailed-runtime` ↔ `029-runtime-refresh-failure-record` — 同一条 runtime/status 观测链路；023 已完成，本轮 029 只补 latest refresh failure record 与 health/status/doctor/diagnostics surface。
- `030-browser-automation-doctor` ↔ `031-doctor-issue-summary` — browser automation doctor 检查项应进入统一 issue summary schema，留到下一轮一起处理。
- `031-doctor-issue-summary` ↔ `020-tirith-security-diagnostics` — 020 已完成；若后续纳入 doctor 摘要，应复用统一 issue summary 格式。
- `032-approval-timeout-denied-outcome` ↔ `033-approval-context-session-isolation` — 两者集中修改审批状态机，应作为同一设计处理。
- `034-gateway-undo-soft-rewind` ↔ `041-memory-provider-rewound-hook` — 041 依赖 034 的 rewind 边界。
- `035-gateway-shutdown-drain` ↔ `036-restart-notification-marker` — 都围绕 gateway restart/shutdown 生命周期。
- `038-skill-ignore-scan-filter` ↔ `040-skill-hub-path-guard` — 都涉及 skill tree/path 规则；本轮仅做 040，038 deferred。
- `canonical-usage-pricing-gap` ↔ `media-provider-metadata-surface-gap` — usage pricing 已完成；媒体 provider 元数据留待下一轮按 canonical usage bucket 接入。

## 本轮测试记录

- 通过：`git diff --check`
- 通过：`mvn -Dskip.web.build=true "-Dtest=SkillBundlePathSupportTest,SkillImportServiceTest,ModelMetadataServiceTest,RuntimeRefreshBehaviorTest,DashboardStatusServiceTest,DashboardDiagnosticOutputTest,McpRuntimeServiceTest" test`（141 tests, 0 failures, 0 errors, 0 skipped）
- 备注：此前不带 `-Dskip.web.build=true` 的同一测试命令也通过（138 tests, 0 failures），但触发了前端 npm build；最终记录以后端相关测试命令为准。

## 本轮 commit / push 记录

- `6c8f299f` — `040-skill-hub-path-guard`
- `342e0b73` — `model-capability-surface-gap`
- `e8d584c8` — `029-runtime-refresh-failure-record`
- `38da0e58` — `037-mcp-discovery-nonblocking`
- `6c260c33` — Backlog 文档更新
- push：dev 已推送 GitHub/Gitee；main 已快进合并并推送 GitHub/Gitee

## 发现但暂不纳入本轮的范围提醒

- Dashboard 前端/API client/类型联动：候选 localTargets 几乎全是后端，缺少 `web/src/api/client.ts`、`web/src/views/` 等 Dashboard-first 配置与诊断页面落地项；本轮按后端目标不处理前端。
- 国内消息渠道适配器细节：没有单列 feishu、dingtalk、wecom、weixin、qqbot、yuanbao 的授权、验签、消息格式、媒体 ingress、重连、限流和命令兼容 gap。
- Agent 主循环与上下文治理：候选较少覆盖 `src/main/java/com/jimuqu/solon/claw/engine/` 下的工具调用循环、流式 tool call、上下文预算/压缩、pending run 恢复与会话搜索。
- 具体多模态执行链路：已有 pricing/media metadata/video 候选，但缺少图像理解/生成、TTS、独立语音转写的端到端 provider 实现、Dashboard 配置、API 与 usage 贯通 gap。
- SQLite schema/migration 与兼容性：undo soft-delete、restart marker、runtime status/last-failure 都可能需要持久化或迁移，后续需要按功能点补齐。
- CLI/TUI/console 用户入口：候选集中在 backend service/Web 表面，缺少 `cli/` 与 `tui/` 的命令和输出一致性 gap。
- 通用工具安全与审计：除 approval、Tirith、skill hub 外，文件、Shell/Python/JavaScript、process registry、web fetch/search 等工具的统一安全审计和可观测性没有单独覆盖。
