package com.jimuqu.solon.claw.media;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.provider.TranscriptionProvider;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.HutoolHttpErrorFormatter;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;

/** 使用 OpenAI 兼容 `/audio/transcriptions` HTTP 协议的内置独立 STT Provider。 */
final class OpenAiSttProvider implements TranscriptionProvider {
    /** 动态应用配置；运行时刷新后下次请求直接读取新值。 */
    private final AppConfig appConfig;

    /**
     * 创建内置独立 STT Provider。
     *
     * @param appConfig 应用运行配置。
     */
    OpenAiSttProvider(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "openai-stt";
    }

    /** 仅在显式启用且端点、模型有效时报告可用。 */
    @Override
    public boolean isAvailable() {
        AppConfig.SttConfig config = config();
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

    /** 以 multipart/form-data 调用独立 STT 端点并解析 JSON 文本结果。 */
    @Override
    @SuppressWarnings("unchecked")
    public TranscriptionResult transcribe(
            byte[] audio, String mimeType, Map<String, Object> options) {
        AppConfig.SttConfig config = config();
        HttpRequest request =
                HttpRequest.post(config.getEndpoint())
                        .timeout(timeoutMillis(config.getTimeoutSeconds()))
                        .setFollowRedirects(false)
                        .form("file", audio, fileName(mimeType))
                        .form(
                                "model",
                                StrUtil.blankToDefault(
                                        optionText(options, "model"), config.getModel()))
                        .form("response_format", "json");
        String language =
                StrUtil.blankToDefault(optionText(options, "language"), config.getLanguage());
        if (StrUtil.isNotBlank(language)) {
            request.form("language", language.trim());
        }
        String prompt = StrUtil.blankToDefault(optionText(options, "prompt"), config.getPrompt());
        if (StrUtil.isNotBlank(prompt)) {
            request.form("prompt", prompt);
        }
        Double temperature = optionNumber(options, "temperature");
        if (temperature != null
                && temperature.doubleValue() >= 0.0d
                && temperature.doubleValue() <= 1.0d) {
            request.form("temperature", temperature);
        }
        if (StrUtil.isNotBlank(config.getApiKey())) {
            request.bearerAuth(config.getApiKey().trim());
        }

        try (HttpResponse response = request.execute()) {
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                return TranscriptionResult.fail(
                        HutoolHttpErrorFormatter.failure("STT request", response));
            }
            String body =
                    BoundedAttachmentIO.readHutoolText(
                            response, BoundedAttachmentIO.JSON_MAX_BYTES);
            Object parsed = ONode.deserialize(body, Object.class);
            if (!(parsed instanceof Map)) {
                return TranscriptionResult.fail("STT provider returned invalid JSON");
            }
            Object text = ((Map<String, Object>) parsed).get("text");
            if (text == null) {
                return TranscriptionResult.fail("STT provider returned no text");
            }
            return TranscriptionResult.ok(String.valueOf(text));
        }
    }

    /** 读取当前 STT 配置。 */
    private AppConfig.SttConfig config() {
        return appConfig.getSpeech().getStt();
    }

    /** 按音频 MIME 生成 multipart 文件名，便于兼容服务识别输入格式。 */
    private String fileName(String mimeType) {
        String mime = StrUtil.nullToEmpty(mimeType).toLowerCase(Locale.ROOT);
        if (mime.contains("mpeg") || mime.contains("mp3")) {
            return "speech.mp3";
        }
        if (mime.contains("mp4") || mime.contains("m4a")) {
            return "speech.m4a";
        }
        if (mime.contains("ogg")) {
            return "speech.ogg";
        }
        if (mime.contains("webm")) {
            return "speech.webm";
        }
        if (mime.contains("flac")) {
            return "speech.flac";
        }
        if (mime.contains("aac")) {
            return "speech.aac";
        }
        return "speech.wav";
    }

    /** 从工具选项读取首个非空字符串。 */
    private String optionText(Map<String, Object> options, String key) {
        if (options == null || options.get(key) == null) {
            return "";
        }
        return StrUtil.nullToEmpty(String.valueOf(options.get(key))).trim();
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
