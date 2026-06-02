# media-provider-metadata-surface-gap

## 标题
扩展多模态附件与媒体 provider 元数据面

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: medium
- parallelSafe: false
- overlapHint: `022-multimodal-attachment-payloads`

## 对标能力
来源领域：backend-alignment

## 当前缺口
Hermes' media provider ABCs surface model/voice catalogs, setup schemas, and compatibility hints, and its result envelopes can carry usage metadata. The current project can synthesize/transcribe/generate, but the provider surface is much thinner, so routing and gating decisions have less metadata to work with and attachment handling remains mostly text-summary oriented.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/agent/transcription_provider.py:60-149`
- `/Users/chengliang/code-repositories/hermes-agent/agent/tts_provider.py:64-255`
- `/Users/chengliang/code-repositories/hermes-agent/agent/image_gen_provider.py:51-121`
- `/Users/chengliang/code-repositories/hermes-agent/agent/image_gen_registry.py:78-124`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/core/model/MessageAttachment.java:1-36`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/support/MessageAttachmentSupport.java:1-260`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/plugin/provider/TranscriptionProvider.java:1-51`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/plugin/provider/SpeechProvider.java:1-61`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/plugin/provider/ImageGenProvider.java:1-31`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/media/SpeechService.java:1-220`

## 验证方式
Add tests proving media providers can advertise catalogs and compatibility hints, and that audio/media usage metadata survives through SpeechService and related result envelopes.
