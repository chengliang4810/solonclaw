# usage-pricing-canonical-buckets

## 标题
归一化 usage 与 pricing token buckets

## 状态
- status: selected

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true

## 分类
- category: backend-diff-backlog

## 现有补充草案
- 已有同主题草案：`../external-backend/012-usage-pricing-canonical-buckets.md`

## Hermes 参考
- Hermes centralizes richer model metadata in /Users/chengliang/code-repositories/hermes-agent/agent/models_dev.py with capability flags for tools, vision/attachment, PDF, audio, structured output, open weights, plus modality lists and costs.
- Hermes normalizes usage/account data in /Users/chengliang/code-repositories/hermes-agent/agent/account_usage.py and related pricing logic, exposing provider-specific snapshots instead of only raw token buckets.
- Hermes builds native multimodal payloads in /Users/chengliang/code-repositories/hermes-agent/agent/image_routing.py and adapts vendor-specific shapes in /Users/chengliang/code-repositories/hermes-agent/agent/gemini_cloudcode_adapter.py; image content parts, remote URLs, and audio-related multimodal signals are handled explicitly.
- Hermes also sanitizes message/tool payloads and strips unsupported image parts in /Users/chengliang/code-repositories/hermes-agent/agent/message_sanitization.py, preserving tool-call linkage while degrading gracefully when providers reject images.

## 当前项目观察
- Current project only exposes a simpler provider/model catalog in /Users/chengliang/code-projects/jimuqu-agent/web/src/shared/providers.ts and /Users/chengliang/code-projects/jimuqu-agent/web/src/shared/modelPicker.ts, with no obvious backend capability source-of-truth in the inspected surface.
- Current backlog already hints at missing backend parity in /Users/chengliang/code-projects/jimuqu-agent/.claude/alignment-backlog/external-backend/011-model-metadata-capability-flags.md, /Users/chengliang/code-projects/jimuqu-agent/.claude/alignment-backlog/external-backend/012-usage-pricing-canonical-buckets.md, and /Users/chengliang/code-projects/jimuqu-agent/.claude/alignment-backlog/external-backend/022-multimodal-attachment-payloads.md.
- The current model picker map is static and frontend-facing; I did not find an equivalent backend model capability parser or a canonical usage/pricing normalizer in the files inspected for this diff.

## 当前缺口
当前项目的 usage/cost 统计仍偏本地 token 桶乘单价，缺少 Hermes 的 canonical usage 归一层、request_count 以及 pricing source/fetched_at，导致不同 provider 的 usage shape 无法统一入账和展示。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/llm/LlmUsage.java`
- `src/main/java/com/jimuqu/solon/claw/pricing/ModelPrice.java`
- `src/main/java/com/jimuqu/solon/claw/pricing/UsageCostCalculator.java`

## 验证方式
补齐归一层后，跑 usage/pricing 相关单测，覆盖 Anthropic/OpenAI/OpenRouter/自定义端点的 usage shape 与费用汇总。

