# 外部对标后端 Backlog

生成时间：2026-06-01

## 已完成并推送到 dev

- `2b76e3c9` — 环境透传写入防护 / Add env passthrough write guard
- `9f7bdf0e` — 模型能力元数据 fallback 细化 / Refine model metadata fallback
- `c50bd5d7` — Cron 工具集约束加固 / Harden Cron toolset constraints

## 推荐首批并行开发

1. [023-health-detailed-runtime](023-health-detailed-runtime.md) — 扩展 `/health` 详细运行时视图
2. [024-env-probe-granularity](024-env-probe-granularity.md) — 细化子进程环境探针分级输出
3. [025-skill-external-dir-cache-and-normalization](025-skill-external-dir-cache-and-normalization.md) — 外部技能目录缓存与归一去重
4. [026-model-metadata-modalities-and-provenance](026-model-metadata-modalities-and-provenance.md) — 扩展模型元数据模态与来源字段
5. [027-context-estimator-image-cost](027-context-estimator-image-cost.md) — 为图片输入补粗略 token 成本估算

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
- [010-config-write-reveal-safety](010-config-write-reveal-safety.md) — 对齐 外部对标配置写入与 reveal 安全
- [011-model-metadata-capability-flags](011-model-metadata-capability-flags.md) — 扩展模型元数据能力标记
- [012-usage-pricing-canonical-buckets](012-usage-pricing-canonical-buckets.md) — 归一化 usage 与 pricing token buckets
- [028-config-drift-diagnostics](028-config-drift-diagnostics.md) — 增加配置漂移与未知字段诊断
- [029-runtime-refresh-failure-record](029-runtime-refresh-failure-record.md) — 记录 runtime config refresh 最近失败
- [030-browser-automation-doctor](030-browser-automation-doctor.md) — 增加浏览器自动化 doctor 依赖探测
- [031-doctor-issue-summary](031-doctor-issue-summary.md) — 汇总 doctor issues 与 next actions
- [032-approval-timeout-denied-outcome](032-approval-timeout-denied-outcome.md) — 结构化审批超时与拒绝结果
- [033-approval-context-session-isolation](033-approval-context-session-isolation.md) — 绑定审批上下文避免并发串线
- [034-gateway-undo-soft-rewind](034-gateway-undo-soft-rewind.md) — 使用软删除语义回退会话 turn
- [035-gateway-shutdown-drain](035-gateway-shutdown-drain.md) — 停机先 drain 再 interrupt 并清理工具进程
- [036-restart-notification-marker](036-restart-notification-marker.md) — 重启恢复标记与启动后通知
- [038-skill-ignore-scan-filter](038-skill-ignore-scan-filter.md) — 技能扫描支持 ignore 文件

## 中优先级候选

- [013-pending-session-recovery](013-pending-session-recovery.md) — 对齐 pending-session recovery
- [014-skill-progressive-preprocessing](014-skill-progressive-preprocessing.md) — 补齐技能渐进加载前置预处理
- [015-skill-bundle-progressive-reload](015-skill-bundle-progressive-reload.md) — 对齐技能 bundle 渐进重载策略
- [016-cron-execution-context-isolation](016-cron-execution-context-isolation.md) — 隔离 Cron job 执行上下文
- [017-headless-cron-execution-path](017-headless-cron-execution-path.md) — 拆分 headless Cron 与完整 agent loop
- [018-env-probe-diagnostics](018-env-probe-diagnostics.md) — 增加 subprocess policy 的 env probe 诊断
- [019-stream-diagnostics-health-output](019-stream-diagnostics-health-output.md) — 在后端健康输出中暴露 stream diagnostics
- [020-tirith-security-diagnostics](020-tirith-security-diagnostics.md) — 对齐 Tirith 安全诊断与外部对标 audit surface
- [021-provider-model-list-url-resolution](021-provider-model-list-url-resolution.md) — 增加 provider-aware model-list URL resolution
- [022-multimodal-attachment-payloads](022-multimodal-attachment-payloads.md) — 让附件与语音/图像 payload 具备多模态感知
- [037-mcp-discovery-nonblocking](037-mcp-discovery-nonblocking.md) — 启动期 MCP discovery 不阻塞网关事件循环
- [039-skill-usage-atomic-sidecar](039-skill-usage-atomic-sidecar.md) — 技能 usage sidecar 原子写与跨进程保护
- [040-skill-hub-path-guard](040-skill-hub-path-guard.md) — Hub 安装/卸载路径防逃逸
- [041-memory-provider-rewound-hook](041-memory-provider-rewound-hook.md) — MemoryProvider session switch 补 rewound 语义
- [042-skill-curator-archive-report](042-skill-curator-archive-report.md) — Curator 自动归档报告链

## 跳过项

- 已完成：环境透传写入防护
- 已完成：模型元数据 fallback
- 已完成：Cron toolsets 约束
- 排除：前端、TUI、WebUI surfaces
- 排除：技能市场大迁移
- 排除：构建/测试产物
