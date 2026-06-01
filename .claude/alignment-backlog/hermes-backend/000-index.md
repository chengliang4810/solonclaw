# Hermes 后端对齐 Backlog

生成时间：2026-06-01

## 本轮已选并行开发

- [ ] [session-lineage-and-export-apis](session-lineage-and-export-apis.md) — Add session lineage and export helpers（priority=medium / risk=low / parallelSafe=true）
- [ ] [add-hermes-style-env-probe-to-system-prompt](add-hermes-style-env-probe-to-system-prompt.md) — 补齐 Hermes 风格的环境探针注入（priority=high / risk=low / parallelSafe=true）
- [ ] [usage-pricing-canonical-buckets](usage-pricing-canonical-buckets.md) — 归一化 usage 与 pricing token buckets（priority=high / risk=medium / parallelSafe=true）
- [ ] [multimodal-attachment-payloads](multimodal-attachment-payloads.md) — 让附件与语音/图像 payload 具备多模态感知（priority=medium / risk=medium / parallelSafe=true）

## 高优先级待排队

- [ ] [sqlite-session-write-contention-hardening](sqlite-session-write-contention-hardening.md) — Harden shared SQLite session writes（risk=medium / parallelSafe=false）
- [ ] [persist-tool-result-artifacts](persist-tool-result-artifacts.md) — Persist oversized tool results as artifacts（risk=high / parallelSafe=false）
- [ ] [strengthen-pending-session-recovery](strengthen-pending-session-recovery.md) — Strengthen startup resume for interrupted sessions（risk=medium / parallelSafe=false）
- [ ] [memory-single-external-provider](memory-single-external-provider.md) — 对齐记忆管理器的单外部 provider 约束（risk=medium / parallelSafe=false）
- [ ] [memory-context-isolation-safety](memory-context-isolation-safety.md) — 增强记忆上下文隔离与安全边界（risk=medium / parallelSafe=true）
- [ ] [skill-security-trust-policy](skill-security-trust-policy.md) — 强化技能安全扫描与信任策略（risk=medium / parallelSafe=false）
- [ ] [cron-profile-isolation](cron-profile-isolation.md) — Add per-job cron profile isolation（risk=medium / parallelSafe=false）
- [ ] [cron-assembled-prompt-scanner](cron-assembled-prompt-scanner.md) — Split strict and assembled cron prompt scanners（risk=medium / parallelSafe=false）
- [ ] [split-stream-diagnostics-into-per-attempt-structured-telemetry](split-stream-diagnostics-into-per-attempt-structured-telemetry.md) — 细化流式中断诊断为按尝试记录（risk=medium / parallelSafe=true）
- [ ] [model-metadata-capability-flags](model-metadata-capability-flags.md) — 扩展模型元数据能力标记（risk=medium / parallelSafe=false）

## 中优先级待排队

- [ ] [skill-progressive-preprocessing](skill-progressive-preprocessing.md) — 补齐技能渐进加载前置预处理（risk=low / parallelSafe=true）
- [ ] [skill-bundle-progressive-reload](skill-bundle-progressive-reload.md) — 对齐技能 bundle 渐进重载策略（risk=low / parallelSafe=true）
- [ ] [cron-routing-token-all](cron-routing-token-all.md) — Add Hermes-style cron routing tokens and plugin-aware home fallback（risk=medium / parallelSafe=false）
- [ ] [cron-headless-execution-parity](cron-headless-execution-parity.md) — Align headless cron execution and script isolation（risk=low / parallelSafe=true）
- [ ] [align-doctor-with-security-advisories-and-remediation](align-doctor-with-security-advisories-and-remediation.md) — 把 doctor/安全告警做成可执行修复建议（risk=medium / parallelSafe=false）
- [ ] [harden-approval-session-and-policy-boundaries](harden-approval-session-and-policy-boundaries.md) — 收紧审批会话与危险命令边界（risk=high / parallelSafe=false）
- [ ] [provider-model-catalog-alignment](provider-model-catalog-alignment.md) — 对齐 provider/model 目录与能力筛选（risk=low / parallelSafe=true）

## 说明

- 本目录聚合 Hermes 与当前项目的后端能力差异，优先后端、Java 8、Solon 风格、最小改动。
- `selected` 表示已进入当前并行开发波次；其余默认为待排队。
- 若某主题在 `../external-backend/` 已有补充草案，对应文件内会附带引用。
