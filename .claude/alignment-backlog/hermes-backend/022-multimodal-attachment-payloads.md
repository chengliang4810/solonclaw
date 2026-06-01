# 022-multimodal-attachment-payloads

## 标题
让附件与语音/图像 payload 具备多模态感知

## 优先级 / 风险
- priority: medium
- risk: medium
- parallelSafe: true

## Hermes 参考
- `/Users/chengliang/code-repositories/hermes-agent/agent/model_metadata.py`
- `/Users/chengliang/code-repositories/hermes-agent/agent/transcription_provider.py`
- `/Users/chengliang/code-repositories/hermes-agent/agent/tts_provider.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/core/model/MessageAttachment.java`
- `src/main/java/com/jimuqu/solon/claw/support/MessageAttachmentSupport.java`
- `src/main/java/com/jimuqu/solon/claw/plugin/provider/TranscriptionProvider.java`
- `src/main/java/com/jimuqu/solon/claw/plugin/provider/SpeechProvider.java`
- `src/main/java/com/jimuqu/solon/claw/plugin/provider/ImageGenProvider.java`

## 当前缺口
当前附件侧已有 voice/transcribedText 字段，但后端主要做文本摘要注入；相比 Hermes，缺少图像 token 估算、语音/转写流转与多模态能力 gating，压缩前容易低估附件成本。

## 实现范围
让附件管线更偏多模态：对 image、voice、video、file 分别做可用性判断与 token/体积提示，必要时在进入对话前提前拒绝或降级；保持 composeEffectiveUserText 兼容。

## 验证方式
`mvn -Dskip.web.build=true -Dtest=AttachmentAwareConversationTest,MessagingToolsAttachmentTest,BoundedAttachmentIOTest,CliAttachmentResolverTest test`
