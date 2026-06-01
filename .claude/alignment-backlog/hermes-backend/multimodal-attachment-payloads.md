# multimodal-attachment-payloads

## 标题
让附件与语音/图像 payload 具备多模态感知

## 状态
- status: selected

## 优先级 / 风险
- priority: medium
- risk: medium
- parallelSafe: true

## 分类
- category: backend-diff-backlog

## 现有补充草案
- 已有同主题草案：`../external-backend/022-multimodal-attachment-payloads.md`

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
当前项目的附件管线更偏文本摘要注入，缺少 Hermes 那种针对 image、voice、video、file 的可用性判断与 payload 规范化；因此图像 token 估算、音频/转写流转和多模态 gating 都不完整。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/core/model/MessageAttachment.java`
- `src/main/java/com/jimuqu/solon/claw/support/MessageAttachmentSupport.java`
- `src/main/java/com/jimuqu/solon/claw/plugin/provider/TranscriptionProvider.java`
- `src/main/java/com/jimuqu/solon/claw/plugin/provider/SpeechProvider.java`
- `src/main/java/com/jimuqu/solon/claw/plugin/provider/ImageGenProvider.java`

## 验证方式
补齐后执行附件会话、消息工具与边界 IO 相关测试，确认 image/voice/file 的降级与拒绝策略正确。

