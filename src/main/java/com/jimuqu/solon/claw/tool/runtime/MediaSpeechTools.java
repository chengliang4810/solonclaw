package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.media.ImageAspectRatio;
import com.jimuqu.solon.claw.media.ImageGenerationService;
import com.jimuqu.solon.claw.media.SpeechService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.annotation.Param;

/** 内置图片生成、TTS 与语音转写工具。 */
public class MediaSpeechTools implements ToolProvider {
    /** 图像生成服务，负责 Provider 选择、媒体缓存和用量记录。 */
    private final ImageGenerationService imageGenerationService;

    /** 语音服务，负责 TTS 合成、独立语音转写和媒体用量记录。 */
    private final SpeechService speechService;

    /**
     * 创建媒体语音工具实例，并注入运行所需依赖。
     *
     * @param imageGenerationService 图像生成服务依赖。
     * @param speechService 语音服务依赖。
     */
    public MediaSpeechTools(
            ImageGenerationService imageGenerationService, SpeechService speechService) {
        this.imageGenerationService = imageGenerationService;
        this.speechService = speechService;
    }

    /**
     * 根据提示词生成图片，并返回缓存后的媒体引用。
     *
     * @param prompt 图像生成提示词。
     * @param aspectRatio 可选宽高比，默认 landscape。
     * @param imageUrl 可选编辑主图。
     * @param referenceImageUrls 可选参考图列表。
     * @return JSON 文本，成功时包含 mediaReference、provider、mimeType 和 mediaUsage。
     */
    public String generateImage(
            String prompt,
            ImageAspectRatio aspectRatio,
            String imageUrl,
            List<String> referenceImageUrls) {
        return generateImage(
                prompt,
                aspectRatio == null ? null : aspectRatio.name(),
                imageUrl,
                referenceImageUrls);
    }

    /** 把动态工具中的原始宽高比交给服务层统一校验，避免非法值被当成默认值。 */
    private String generateImage(
            String prompt, String aspectRatio, String imageUrl, List<String> referenceImageUrls) {
        ImageGenerationService.ImageGenerationOutcome outcome =
                imageGenerationService.generate(prompt, aspectRatio, imageUrl, referenceImageUrls);
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
     * 返回显式图片 schema 与反射生成的语音工具，规避枚举默认值在反射 schema 中的类型限制。
     *
     * @return 当前媒体工具集合。
     */
    @Override
    public Collection<FunctionTool> getTools() {
        List<FunctionTool> tools = new ArrayList<FunctionTool>();
        tools.add(imageGenerateTool());
        tools.addAll(new MethodToolProvider(this).includeProvide(false).getTools());
        return tools;
    }

    /** 构建参数与执行一致的 image_generate FunctionTool。 */
    private FunctionTool imageGenerateTool() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("prompt", property("string", "Image prompt"));

        Map<String, Object> aspectRatio = property("string", "Image aspect ratio");
        aspectRatio.put(
                "enum",
                Arrays.asList(
                        ImageAspectRatio.landscape.name(),
                        ImageAspectRatio.square.name(),
                        ImageAspectRatio.portrait.name()));
        aspectRatio.put("default", ImageAspectRatio.landscape.name());
        properties.put("aspect_ratio", aspectRatio);
        properties.put(
                "image_url",
                property(
                        "string",
                        "Source image URL, data URL, media:// reference, or runtime-cache path"));

        Map<String, Object> references =
                property("array", "Additional reference image URLs or cache references");
        references.put("items", property("string", null));
        properties.put("reference_image_urls", references);

        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("prompt"));
        return new FunctionToolDesc("image_generate")
                .description("Generate or edit an image and save it as media.")
                .returnType(String.class)
                .inputSchema(ONode.serialize(schema))
                .doHandle(
                        args ->
                                generateImage(
                                        text(args.get("prompt")),
                                        text(args.get("aspect_ratio")),
                                        text(args.get("image_url")),
                                        stringList(args.get("reference_image_urls"))));
    }

    /** 构造单个 JSON schema 属性。 */
    private Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("type", type);
        if (description != null) {
            property.put("description", description);
        }
        return property;
    }

    /** 把动态工具参数转换为可空字符串。 */
    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 把动态工具数组参数转换为字符串列表。 */
    private List<String> stringList(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) value) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /**
     * 将文本合成为语音媒体，并返回缓存后的媒体引用。
     *
     * @param text 待处理文本。
     * @param voice 可选语音名称。
     * @param optionsJson 透传给语音 Provider 的 JSON 选项。
     * @return JSON 文本，成功时包含 mediaReference、provider、mimeType 和 mediaUsage。
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
     * 转写缓存中的语音附件。
     *
     * @param mediaReference media:// 形式的缓存媒体引用。
     * @param mimeType MIME 类型参数。
     * @param optionsJson 透传给转写 Provider 的 JSON 选项。
     * @return JSON 文本，成功时包含转写文本、provider 和 mediaUsage。
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
     * 构造媒体工具统一返回结构。
     *
     * @param error 错误参数。
     * @return 包含当前 status 字段的有序 Map；失败时附加 error。
     */
    private Map<String, Object> base(boolean success, String error) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", success ? "success" : "error");
        if (!success) {
            result.put("error", error);
        }
        return result;
    }

    /**
     * 解析 Provider 透传选项。
     *
     * @return JSON 对象时返回可变 Map，其余情况返回空 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseOptions(String optionsJson) {
        if (StrUtil.isBlank(optionsJson)) {
            return new LinkedHashMap<String, Object>();
        }
        Object value = ONode.deserialize(optionsJson, Object.class);
        if (value instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) value);
        }
        return new LinkedHashMap<String, Object>();
    }
}
