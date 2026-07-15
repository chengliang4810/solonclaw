package com.jimuqu.solon.claw.media;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.provider.SpeechProvider;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.HutoolHttpErrorFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;

/** 使用 OpenAI 兼容 `/audio/speech` HTTP 协议的内置 TTS Provider。 */
final class OpenAiTtsProvider implements SpeechProvider {
    /** 动态应用配置；运行时刷新后下次请求直接读取新值。 */
    private final AppConfig appConfig;

    /**
     * 创建内置 TTS Provider。
     *
     * @param appConfig 应用运行配置。
     */
    OpenAiTtsProvider(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "openai-tts";
    }

    /** 仅在显式启用且端点、模型有效时报告可用。 */
    @Override
    public boolean isAvailable() {
        AppConfig.TtsConfig config = config();
        if (!config.isEnabled()
                || StrUtil.isBlank(config.getEndpoint())
                || StrUtil.isBlank(config.getModel())) {
            return false;
        }
        try {
            LlmProviderSupport.validateBaseUrl(config.getEndpoint());
            return !LlmProviderSupport.isDirectOpenAiBaseUrl(config.getEndpoint())
                    || StrUtil.isNotBlank(config.getApiKey());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 调用 OpenAI 兼容 TTS 端点并返回受限大小的音频字节。 */
    @Override
    public SpeechResult synthesize(String text, String voice, Map<String, Object> options) {
        AppConfig.TtsConfig config = config();
        String format =
                normalizeFormat(
                        optionText(options, "responseFormat", "format"),
                        config.getResponseFormat());
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(
                "model", StrUtil.blankToDefault(optionText(options, "model"), config.getModel()));
        payload.put("input", text);
        payload.put("voice", resolveVoice(voice, config.getVoice()));
        payload.put("response_format", format);
        Double requestedSpeed = optionNumber(options, "speed");
        double speed = requestedSpeed == null ? config.getSpeed() : requestedSpeed.doubleValue();
        if (Double.isFinite(speed) && speed >= 0.25d && speed <= 4.0d) {
            payload.put("speed", Double.valueOf(speed));
        }
        String instructions = optionText(options, "instructions");
        if (StrUtil.isNotBlank(instructions)) {
            payload.put("instructions", instructions);
        }

        HttpRequest request =
                HttpRequest.post(config.getEndpoint())
                        .timeout(timeoutMillis(config.getTimeoutSeconds()))
                        .setFollowRedirects(false)
                        .contentType(ContentType.JSON.toString())
                        .body(ONode.serialize(payload));
        if (StrUtil.isNotBlank(config.getApiKey())) {
            request.bearerAuth(config.getApiKey().trim());
        }
        try (HttpResponse response = request.execute()) {
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                return SpeechResult.fail(HutoolHttpErrorFormatter.failure("TTS request", response));
            }
            byte[] audio =
                    BoundedAttachmentIO.readHutoolResponse(response, SpeechService.MAX_AUDIO_BYTES);
            return SpeechResult.ok(responseMimeType(response, format), audio);
        }
    }

    /** 读取当前 TTS 配置。 */
    private AppConfig.TtsConfig config() {
        return appConfig.getSpeech().getTts();
    }

    /** 解析调用方音色；服务层的 default 占位值回落到配置音色。 */
    private String resolveVoice(String voice, String fallback) {
        if (StrUtil.isBlank(voice) || "default".equalsIgnoreCase(voice.trim())) {
            return StrUtil.blankToDefault(fallback, "alloy");
        }
        return voice.trim();
    }

    /** 规范化请求输出格式，非法工具选项回落到已校验的配置默认值。 */
    private String normalizeFormat(String requested, String fallback) {
        String value =
                StrUtil.blankToDefault(requested, StrUtil.blankToDefault(fallback, "mp3"))
                        .trim()
                        .toLowerCase(Locale.ROOT);
        if ("mp3".equals(value)
                || "opus".equals(value)
                || "aac".equals(value)
                || "flac".equals(value)
                || "wav".equals(value)
                || "pcm".equals(value)) {
            return value;
        }
        return "mp3";
    }

    /** 读取响应 MIME；兼容服务未返回音频类型时按请求格式推断。 */
    private String responseMimeType(HttpResponse response, String format) {
        String contentType = StrUtil.nullToEmpty(response.header("Content-Type")).trim();
        int separator = contentType.indexOf(';');
        if (separator >= 0) {
            contentType = contentType.substring(0, separator).trim();
        }
        if (contentType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            return contentType;
        }
        if ("mp3".equals(format)) {
            return "audio/mpeg";
        }
        if ("opus".equals(format)) {
            return "audio/ogg";
        }
        if ("aac".equals(format)) {
            return "audio/aac";
        }
        if ("flac".equals(format)) {
            return "audio/flac";
        }
        return "audio/wav";
    }

    /** 从工具选项读取首个非空字符串。 */
    private String optionText(Map<String, Object> options, String... keys) {
        if (options == null) {
            return "";
        }
        for (String key : keys) {
            Object value = options.get(key);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    /** 从工具选项读取有限数值，无法解析时忽略该选项。 */
    private Double optionNumber(Map<String, Object> options, String key) {
        if (options == null || options.get(key) == null) {
            return null;
        }
        Object value = options.get(key);
        if (value instanceof Number) {
            return Double.valueOf(((Number) value).doubleValue());
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 把秒级配置转换为 Hutool 使用的毫秒超时。 */
    private int timeoutMillis(int seconds) {
        long millis = Math.max(1L, seconds) * 1000L;
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
}
