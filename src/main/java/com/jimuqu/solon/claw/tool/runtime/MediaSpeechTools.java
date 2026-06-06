package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.media.ImageGenerationService;
import com.jimuqu.solon.claw.media.SpeechService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 内置图片生成、TTS 与语音转写工具。 */
public class MediaSpeechTools {
    /** 注入图片Generation服务，用于调用对应业务能力。 */
    private final ImageGenerationService imageGenerationService;

    /** 注入语音服务，用于调用对应业务能力。 */
    private final SpeechService speechService;

    /**
     * 创建媒体语音工具实例，并注入运行所需依赖。
     *
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     */
    public MediaSpeechTools(
            ImageGenerationService imageGenerationService, SpeechService speechService) {
        this.imageGenerationService = imageGenerationService;
        this.speechService = speechService;
    }

    /**
     * 执行generate图片相关逻辑。
     *
     * @param prompt 提示词参数。
     * @param aspectRatio aspectRatio 参数。
     * @param optionsJson optionsJSON参数。
     * @return 返回generate图片结果。
     */
    @ToolMapping(name = "image_generate", description = "Generate an image and save it as media.")
    public String generateImage(
            @Param(name = "prompt", description = "Image prompt") String prompt,
            @Param(
                            name = "aspectRatio",
                            required = false,
                            description = "Aspect ratio, for example 1:1")
                    String aspectRatio,
            @Param(name = "optionsJson", required = false, description = "Optional JSON options")
                    String optionsJson) {
        ImageGenerationService.ImageGenerationOutcome outcome =
                imageGenerationService.generate(prompt, aspectRatio, parseOptions(optionsJson));
        Map<String, Object> result = base(outcome.isSuccess(), outcome.getError());
        if (outcome.isSuccess()) {
            result.put("kind", "image");
            result.put("mediaReference", outcome.getMediaReference());
            result.put("provider", outcome.getProvider());
            result.put("mimeType", outcome.getAttachment().getMimeType());
            result.put("mediaUsage", outcome.getMediaUsage());
        }
        return ONode.serialize(result);
    }

    /**
     * 执行文本To语音相关逻辑。
     *
     * @param text 待处理文本。
     * @param voice 语音参数。
     * @param optionsJson optionsJSON参数。
     * @return 返回text To语音结果。
     */
    @ToolMapping(name = "text_to_speech", description = "Synthesize text to speech media.")
    public String textToSpeech(
            @Param(name = "text", description = "Text to synthesize") String text,
            @Param(name = "voice", required = false, description = "Voice name") String voice,
            @Param(name = "optionsJson", required = false, description = "Optional JSON options")
                    String optionsJson) {
        SpeechService.SpeechOutcome outcome =
                speechService.synthesize(text, voice, parseOptions(optionsJson));
        Map<String, Object> result = base(outcome.isSuccess(), outcome.getError());
        if (outcome.isSuccess()) {
            result.put("kind", "voice");
            result.put("mediaReference", outcome.getMediaReference());
            result.put("provider", outcome.getProvider());
            result.put("mimeType", outcome.getAttachment().getMimeType());
            result.put("mediaUsage", outcome.getMediaUsage());
        }
        return ONode.serialize(result);
    }

    /**
     * 执行transcribe语音相关逻辑。
     *
     * @param mediaReference 媒体引用参数。
     * @param mimeType MIME 类型参数。
     * @param optionsJson optionsJSON参数。
     * @return 返回transcribe语音结果。
     */
    @ToolMapping(name = "speech_transcribe", description = "Transcribe a cached voice attachment.")
    public String transcribeSpeech(
            @Param(name = "mediaReference", description = "media:// reference")
                    String mediaReference,
            @Param(name = "mimeType", required = false, description = "Audio MIME type")
                    String mimeType,
            @Param(name = "optionsJson", required = false, description = "Optional JSON options")
                    String optionsJson) {
        if (!StrUtil.nullToEmpty(mediaReference).trim().startsWith("media://")) {
            return ONode.serialize(base(false, "speech_transcribe requires a media:// reference"));
        }
        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind("voice");
        attachment.setLocalPath(mediaReference);
        attachment.setMimeType(mimeType);
        SpeechService.TranscriptionOutcome outcome =
                speechService.transcribe(attachment, parseOptions(optionsJson));
        Map<String, Object> result = base(outcome.isSuccess(), outcome.getError());
        if (outcome.isSuccess()) {
            result.put("text", outcome.getText());
            result.put("provider", outcome.getProvider());
            result.put("mediaUsage", outcome.getMediaUsage());
        }
        return ONode.serialize(result);
    }

    /**
     * 执行基础相关逻辑。
     *
     * @param success success 参数。
     * @param error 错误参数。
     * @return 返回base结果。
     */
    private Map<String, Object> base(boolean success, String error) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", Boolean.valueOf(success));
        if (!success) {
            result.put("error", error);
        }
        return result;
    }

    /**
     * 解析Options。
     *
     * @param optionsJson optionsJSON参数。
     * @return 返回解析后的Options。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        Object value = ONode.deserialize(optionsJson, Object.class);
        if (value instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) value);
        }
        return new LinkedHashMap<String, Object>();
    }
}
