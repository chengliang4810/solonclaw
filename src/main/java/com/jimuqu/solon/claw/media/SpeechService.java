package com.jimuqu.solon.claw.media;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.plugin.provider.SpeechProvider;
import com.jimuqu.solon.claw.plugin.provider.TranscriptionProvider;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BasicValueSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** TTS 与独立语音转写运行时服务，负责选择插件提供方并把语音结果接入附件缓存。 */
public class SpeechService {
    /** 单个音频输入或输出允许处理的最大字节数。 */
    private static final long MAX_AUDIO_BYTES = 32L * 1024L * 1024L;

    /** 附件缓存服务，用于保存 TTS 输出并解析待转写的本地语音缓存。 */
    private final AttachmentCacheService attachmentCacheService;

    /** TTS 提供方列表，按插件注册顺序选择第一个可用提供方。 */
    private final List<SpeechProvider> speechProviders;

    /** 语音转写提供方列表，按插件注册顺序选择第一个可用提供方。 */
    private final List<TranscriptionProvider> transcriptionProviders;

    /**
     * 创建语音服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param speechProviders TTS 能力提供方列表。
     * @param transcriptionProviders 语音转写能力提供方列表。
     */
    public SpeechService(
            AppConfig appConfig,
            AttachmentCacheService attachmentCacheService,
            List<SpeechProvider> speechProviders,
            List<TranscriptionProvider> transcriptionProviders) {
        this.attachmentCacheService =
                attachmentCacheService == null
                        ? new AttachmentCacheService(appConfig)
                        : attachmentCacheService;
        this.speechProviders = BasicValueSupport.emptyListIfNull(speechProviders);
        this.transcriptionProviders = BasicValueSupport.emptyListIfNull(transcriptionProviders);
    }

    /**
     * 执行语音合成请求并返回缓存后的音频引用。
     *
     * @param text 待合成的文本，不能为空。
     * @param voice 语音名称；为空时使用 provider 默认语音。
     * @param options 插件透传选项。
     * @return 返回合成结果、缓存附件和媒体用量。
     */
    public SpeechOutcome synthesize(String text, String voice, Map<String, Object> options) {
        if (StrUtil.isBlank(text)) {
            return SpeechOutcome.fail("TTS text is required");
        }
        SpeechProvider provider = chooseSpeechProvider();
        if (provider == null) {
            return SpeechOutcome.fail("No available speech provider");
        }
        try {
            SpeechProvider.SpeechResult result =
                    provider.synthesize(
                            text,
                            StrUtil.blankToDefault(voice, "default"),
                            BasicValueSupport.emptyMapIfNull(options));
            if (result == null || !result.isSuccess()) {
                return SpeechOutcome.fail(
                        safeError(result == null ? "TTS failed" : result.getError()));
            }
            byte[] audio = result.getAudio();
            if (BasicValueSupport.isEmpty(audio)) {
                return SpeechOutcome.fail("TTS provider returned empty audio");
            }
            if (audio.length > MAX_AUDIO_BYTES) {
                return SpeechOutcome.fail("TTS audio is too large");
            }
            String mimeType = StrUtil.blankToDefault(result.getMimeType(), "audio/wav");
            MessageAttachment attachment =
                    attachmentCacheService.cacheBytes(
                            PlatformType.MEMORY,
                            "voice",
                            "speech." + extension(mimeType),
                            mimeType,
                            false,
                            null,
                            audio);
            Map<String, Object> usage = mergeUsage(result.getMediaUsage());
            usage.put("audioOutputBytes", Long.valueOf(audio.length));
            return SpeechOutcome.ok(
                    attachment,
                    attachmentCacheService.mediaReference(attachment),
                    provider.name(),
                    usage);
        } catch (Exception e) {
            return SpeechOutcome.fail(safeError(e.getMessage()));
        }
    }

    /**
     * 执行语音转写请求并返回识别文本。
     *
     * @param attachment 语音附件，必须能解析到本地缓存文件。
     * @param options 插件透传选项。
     * @return 返回转写文本和媒体用量。
     */
    public TranscriptionOutcome transcribe(
            MessageAttachment attachment, Map<String, Object> options) {
        if (!isVoiceAttachment(attachment)) {
            return TranscriptionOutcome.fail("Voice attachment is required");
        }
        TranscriptionProvider provider = chooseTranscriptionProvider();
        if (provider == null) {
            return TranscriptionOutcome.fail("No available transcription provider");
        }
        try {
            byte[] audio = readAttachmentBytes(attachment);
            if (audio.length > MAX_AUDIO_BYTES) {
                return TranscriptionOutcome.fail("Voice attachment is too large");
            }
            TranscriptionProvider.TranscriptionResult result =
                    provider.transcribe(
                            audio,
                            StrUtil.blankToDefault(attachment.getMimeType(), "audio/wav"),
                            BasicValueSupport.emptyMapIfNull(options));
            if (result == null || !result.isSuccess()) {
                return TranscriptionOutcome.fail(
                        safeError(result == null ? "Transcription failed" : result.getError()));
            }
            Map<String, Object> usage = mergeUsage(result.getMediaUsage());
            usage.put("audioInputBytes", Long.valueOf(audio.length));
            return TranscriptionOutcome.ok(
                    StrUtil.nullToEmpty(result.getText()).trim(), provider.name(), usage);
        } catch (Exception e) {
            return TranscriptionOutcome.fail(safeError(e.getMessage()));
        }
    }

    /**
     * 选择语音提供方。
     *
     * @return 返回choose语音提供方结果。
     */
    private SpeechProvider chooseSpeechProvider() {
        for (SpeechProvider provider : speechProviders) {
            if (provider != null && provider.isAvailable()) {
                return provider;
            }
        }
        return null;
    }

    /**
     * 选择转写提供方。
     *
     * @return 返回choose Transcription提供方结果。
     */
    private TranscriptionProvider chooseTranscriptionProvider() {
        for (TranscriptionProvider provider : transcriptionProviders) {
            if (provider != null && provider.isAvailable()) {
                return provider;
            }
        }
        return null;
    }

    /**
     * 判断附件是否可作为语音转写输入。
     *
     * @param attachment 附件参数。
     * @return kind 为 voice 或 MIME 为 audio/* 时返回 true。
     */
    private boolean isVoiceAttachment(MessageAttachment attachment) {
        if (attachment == null) {
            return false;
        }
        String kind = StrUtil.nullToEmpty(attachment.getKind()).toLowerCase(Locale.ROOT);
        String mime = StrUtil.nullToEmpty(attachment.getMimeType()).toLowerCase(Locale.ROOT);
        return "voice".equals(kind) || mime.startsWith("audio/");
    }

    /**
     * 从附件本地缓存读取语音字节。
     *
     * @param attachment 附件参数。
     * @return 本地缓存文件的完整音频字节。
     */
    private byte[] readAttachmentBytes(MessageAttachment attachment) throws Exception {
        if (StrUtil.isBlank(attachment.getLocalPath())) {
            throw new IllegalArgumentException("Voice attachment has no local cache path");
        }
        File file = attachmentCacheService.resolveMediaReference(attachment.getLocalPath());
        if (!file.isFile()) {
            throw new IllegalArgumentException("Voice attachment file is missing");
        }
        return Files.readAllBytes(file.toPath());
    }

    /**
     * 复制插件返回的媒体用量，确保调用方可继续追加本地统计字段。
     *
     * @param usage 用量参数。
     * @return 可变有序用量 Map。
     */
    private Map<String, Object> mergeUsage(Map<String, Object> usage) {
        return BasicValueSupport.mutableLinkedMap(usage);
    }

    /**
     * 根据音频 MIME 选择缓存文件扩展名。
     *
     * @param mimeType MIME 类型参数。
     * @return 返回无点号扩展名。
     */
    private String extension(String mimeType) {
        String mime = StrUtil.nullToEmpty(mimeType).toLowerCase(Locale.ROOT);
        if (mime.contains("mpeg")) {
            return "mp3";
        }
        if (mime.contains("ogg")) {
            return "ogg";
        }
        if (mime.contains("mp4")) {
            return "m4a";
        }
        return "wav";
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Error结果。
     */
    private String safeError(String value) {
        return SecretRedactor.redact(
                StrUtil.blankToDefault(value, "Speech operation failed"), 1000);
    }

    /** 表示 TTS 结果，携带缓存附件、媒体引用和统计信息。 */
    public static class SpeechOutcome {
        /** 本次 TTS 是否成功。 */
        private final boolean success;

        /** 成功时缓存得到的音频附件。 */
        private final MessageAttachment attachment;

        /** 成功时可回填到会话或工具结果的媒体引用。 */
        private final String mediaReference;

        /** 实际执行 TTS 的插件提供方名称。 */
        private final String provider;

        /** 失败时经过脱敏处理的错误。 */
        private final String error;

        /** 保存媒体用量映射，便于按键快速查询。 */
        private final Map<String, Object> mediaUsage;

        /**
         * 创建语音Outcome实例，并注入运行所需依赖。
         *
         * @param success success 参数。
         * @param attachment 附件参数。
         * @param mediaReference 媒体引用参数。
         * @param provider 模型或能力提供方。
         * @param error 错误参数。
         * @param mediaUsage 媒体用量参数。
         */
        private SpeechOutcome(
                boolean success,
                MessageAttachment attachment,
                String mediaReference,
                String provider,
                String error,
                Map<String, Object> mediaUsage) {
            this.success = success;
            this.attachment = attachment;
            this.mediaReference = mediaReference;
            this.provider = provider;
            this.error = error;
            this.mediaUsage = BasicValueSupport.mutableLinkedMap(mediaUsage);
        }

        /**
         * 构造成功结果。
         *
         * @param attachment 附件参数。
         * @param mediaReference 媒体引用参数。
         * @param provider 模型或能力提供方。
         * @param mediaUsage 媒体用量参数。
         * @return 返回ok结果。
         */
        public static SpeechOutcome ok(
                MessageAttachment attachment,
                String mediaReference,
                String provider,
                Map<String, Object> mediaUsage) {
            return new SpeechOutcome(true, attachment, mediaReference, provider, null, mediaUsage);
        }

        /**
         * 构造失败结果并携带安全错误信息。
         *
         * @param error 错误参数。
         * @return 返回fail结果。
         */
        public static SpeechOutcome fail(String error) {
            return new SpeechOutcome(false, null, null, null, error, null);
        }

        /**
         * 判断 TTS 是否成功。
         *
         * @return 如果Success满足条件则返回 true，否则返回 false。
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 读取缓存音频附件。
         *
         * @return 返回读取到的附件。
         */
        public MessageAttachment getAttachment() {
            return attachment;
        }

        /**
         * 读取可被模型或前端引用的媒体地址。
         *
         * @return 返回读取到的媒体Reference。
         */
        public String getMediaReference() {
            return mediaReference;
        }

        /**
         * 读取实际使用的插件提供方名称。
         *
         * @return 返回读取到的提供方。
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 读取失败错误文本。
         *
         * @return 返回读取到的Error。
         */
        public String getError() {
            return error;
        }

        /**
         * 读取媒体用量。
         *
         * @return 返回读取到的媒体用量。
         */
        public Map<String, Object> getMediaUsage() {
            return mediaUsage;
        }
    }

    /** 表示语音转写结果，携带转写文本、提供方和媒体统计。 */
    public static class TranscriptionOutcome {
        /** 本次转写是否成功。 */
        private final boolean success;

        /** 成功时识别得到的文本。 */
        private final String text;

        /** 实际执行转写的插件提供方名称。 */
        private final String provider;

        /** 失败时经过脱敏处理的错误。 */
        private final String error;

        /** 保存媒体用量映射，便于按键快速查询。 */
        private final Map<String, Object> mediaUsage;

        /**
         * 创建Transcription Outcome实例，并注入运行所需依赖。
         *
         * @param success success 参数。
         * @param text 待处理文本。
         * @param provider 模型或能力提供方。
         * @param error 错误参数。
         * @param mediaUsage 媒体用量参数。
         */
        private TranscriptionOutcome(
                boolean success,
                String text,
                String provider,
                String error,
                Map<String, Object> mediaUsage) {
            this.success = success;
            this.text = text;
            this.provider = provider;
            this.error = error;
            this.mediaUsage = BasicValueSupport.mutableLinkedMap(mediaUsage);
        }

        /**
         * 构造成功结果。
         *
         * @param text 待处理文本。
         * @param provider 模型或能力提供方。
         * @param mediaUsage 媒体用量参数。
         * @return 返回ok结果。
         */
        public static TranscriptionOutcome ok(
                String text, String provider, Map<String, Object> mediaUsage) {
            return new TranscriptionOutcome(true, text, provider, null, mediaUsage);
        }

        /**
         * 构造失败结果并携带安全错误信息。
         *
         * @param error 错误参数。
         * @return 返回fail结果。
         */
        public static TranscriptionOutcome fail(String error) {
            return new TranscriptionOutcome(false, null, null, error, null);
        }

        /**
         * 判断语音转写是否成功。
         *
         * @return 如果Success满足条件则返回 true，否则返回 false。
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 读取转写文本。
         *
         * @return 返回读取到的Text。
         */
        public String getText() {
            return text;
        }

        /**
         * 读取实际使用的插件提供方名称。
         *
         * @return 返回读取到的提供方。
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 读取失败错误文本。
         *
         * @return 返回读取到的Error。
         */
        public String getError() {
            return error;
        }

        /**
         * 读取媒体用量。
         *
         * @return 返回读取到的媒体用量。
         */
        public Map<String, Object> getMediaUsage() {
            return mediaUsage;
        }
    }
}
