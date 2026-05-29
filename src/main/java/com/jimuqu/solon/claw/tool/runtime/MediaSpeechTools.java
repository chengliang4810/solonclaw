package com.jimuqu.solon.claw.tool.runtime;

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
    private final ImageGenerationService imageGenerationService;
    private final SpeechService speechService;

    public MediaSpeechTools(
            ImageGenerationService imageGenerationService, SpeechService speechService) {
        this.imageGenerationService = imageGenerationService;
        this.speechService = speechService;
    }

    @ToolMapping(name = "image_generate", description = "Generate an image and save it as media.")
    public String generateImage(
            @Param(name = "prompt", description = "Image prompt") String prompt,
            @Param(name = "aspectRatio", required = false, description = "Aspect ratio, for example 1:1")
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

    @ToolMapping(name = "speech_transcribe", description = "Transcribe a cached voice attachment.")
    public String transcribeSpeech(
            @Param(name = "mediaReference", description = "media:// reference or local cache path")
                    String mediaReference,
            @Param(name = "mimeType", required = false, description = "Audio MIME type") String mimeType,
            @Param(name = "optionsJson", required = false, description = "Optional JSON options")
                    String optionsJson) {
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

    private Map<String, Object> base(boolean success, String error) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", Boolean.valueOf(success));
        if (!success) {
            result.put("error", error);
        }
        return result;
    }

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
