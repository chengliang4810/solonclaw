# provider-model-catalog-alignment

## 标题
对齐 provider/model 目录与能力筛选

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

## 分类
- category: backend-diff-backlog

## 现有补充草案
- 已有同主题草案：`../external-backend/021-provider-model-list-url-resolution.md`

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
Hermes 在模型目录侧会结合模型元数据做可用性筛选与噪音模型隐藏；当前项目前端只维护静态 provider preset，后端缺少同级别的 catalog 过滤、能力筛选和模型可用性驱动。

## 目标文件
- `web/src/shared/providers.ts`
- `web/src/shared/modelPicker.ts`

## 验证方式
同步 catalog 过滤规则后，验证模型选择列表与后端实际可调用模型集合一致，避免展示不可用或噪音模型。

