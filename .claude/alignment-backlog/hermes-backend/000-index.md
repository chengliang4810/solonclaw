# Hermes 后端对齐 Backlog

生成时间：2026-06-01

## 已完成并推送到 dev

- `2b76e3c9` — 环境透传写入防护 / Add env passthrough write guard
- `9f7bdf0e` — 模型能力元数据 fallback 细化 / Refine model metadata fallback
- `c50bd5d7` — Cron 工具集约束加固 / Harden Cron toolset constraints

## 推荐首批并行开发

1. [003-tool-result-persistence-artifacts](003-tool-result-persistence-artifacts.md) — 对齐工具结果持久化与 artifacts
2. [007-cron-prompt-ingestion-validation](007-cron-prompt-ingestion-validation.md) — 强化 Cron prompt 写入校验
3. [009-dashboard-doctor-output](009-dashboard-doctor-output.md) — 对齐 Dashboard doctor 后端输出
4. [012-usage-pricing-canonical-buckets](012-usage-pricing-canonical-buckets.md) — 归一化 usage 与 pricing token buckets

## 高优先级候选

- [001-turn-loop-in-turn-recovery](001-turn-loop-in-turn-recovery.md) — 对齐回合循环与轮内恢复（高风险，暂不并行）
- [002-error-classification-failover](002-error-classification-failover.md) — 对齐错误分类与故障转移（高风险，暂不并行）
- [003-tool-result-persistence-artifacts](003-tool-result-persistence-artifacts.md) — 对齐工具结果持久化与 artifacts
- [004-memory-single-external-provider](004-memory-single-external-provider.md) — 对齐记忆管理器的单外部 provider 约束
- [005-memory-context-isolation-safety](005-memory-context-isolation-safety.md) — 增强记忆上下文隔离与安全边界
- [006-skill-security-trust-policy](006-skill-security-trust-policy.md) — 强化技能安全扫描与信任策略
- [007-cron-prompt-ingestion-validation](007-cron-prompt-ingestion-validation.md) — 强化 Cron prompt 写入校验
- [008-cron-runtime-assembled-prompt-safety](008-cron-runtime-assembled-prompt-safety.md) — 增加 Cron 运行时 assembled-prompt 安全检查
- [009-dashboard-doctor-output](009-dashboard-doctor-output.md) — 对齐 Dashboard doctor 后端输出
- [010-config-write-reveal-safety](010-config-write-reveal-safety.md) — 对齐 Hermes 配置写入与 reveal 安全
- [011-model-metadata-capability-flags](011-model-metadata-capability-flags.md) — 扩展模型元数据能力标记
- [012-usage-pricing-canonical-buckets](012-usage-pricing-canonical-buckets.md) — 归一化 usage 与 pricing token buckets

## 中优先级候选

- [013-pending-session-recovery](013-pending-session-recovery.md) — 对齐 pending-session recovery
- [014-skill-progressive-preprocessing](014-skill-progressive-preprocessing.md) — 补齐技能渐进加载前置预处理
- [015-skill-bundle-progressive-reload](015-skill-bundle-progressive-reload.md) — 对齐技能 bundle 渐进重载策略
- [016-cron-execution-context-isolation](016-cron-execution-context-isolation.md) — 隔离 Cron job 执行上下文
- [017-headless-cron-execution-path](017-headless-cron-execution-path.md) — 拆分 headless Cron 与完整 agent loop
- [018-env-probe-diagnostics](018-env-probe-diagnostics.md) — 增加 subprocess policy 的 env probe 诊断
- [019-stream-diagnostics-health-output](019-stream-diagnostics-health-output.md) — 在后端健康输出中暴露 stream diagnostics
- [020-tirith-security-diagnostics](020-tirith-security-diagnostics.md) — 对齐 Tirith 安全诊断与 Hermes audit surface
- [021-provider-model-list-url-resolution](021-provider-model-list-url-resolution.md) — 增加 provider-aware model-list URL resolution
- [022-multimodal-attachment-payloads](022-multimodal-attachment-payloads.md) — 让附件与语音/图像 payload 具备多模态感知

## 跳过项

- 已完成：环境透传写入防护
- 已完成：模型元数据 fallback
- 已完成：Cron toolsets 约束
- 排除：前端、TUI、WebUI surfaces
- 排除：技能市场大迁移
- 排除：构建/测试产物
