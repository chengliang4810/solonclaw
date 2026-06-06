package com.jimuqu.solon.claw.media;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.plugin.provider.SpeechProvider;
import com.jimuqu.solon.claw.plugin.provider.TranscriptionProvider;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** TTS 与独立语音转写运行时服务。 */
public class SpeechService {
    /** 最大音频字节的统一常量值。 */
    private static final long MAX_AUDIO_BYTES = 32L * 1024L * 1024L;

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 保存语音Providers集合，维持调用顺序或去重语义。 */
    private final List<SpeechProvider> speechProviders;

    /** 保存转写Providers集合，维持调用顺序或去重语义。 */
    private final List<TranscriptionProvider> transcriptionProviders;

    /**
     * 创建语音服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param speechProviders 语音Providers标识或键值。
     * @param transcriptionProviders 转写Providers标识或键值。
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
        this.speechProviders =
                speechProviders == null ? Collections.<SpeechProvider>emptyList() : speechProviders;
        this.transcriptionProviders =
                transcriptionProviders == null
                        ? Collections.<TranscriptionProvider>emptyList()
                        : transcriptionProviders;
    }

    /**
     * 执行语音合成请求并返回缓存后的音频引用。
     *
     * @param text 待处理文本。
     * @param voice 语音参数。
     * @param options options 参数。
     * @return 返回synthesize结果。
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
                            options == null ? Collections.<String, Object>emptyMap() : options);
            if (result == null || !result.isSuccess()) {
                return SpeechOutcome.fail(
                        safeError(result == null ? "TTS failed" : result.getError()));
            }
            byte[] audio = result.getAudio();
            if (audio == null || audio.length == 0) {
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
     * @param attachment 附件参数。
     * @param options options 参数。
     * @return 返回transcribe结果。
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
                            options == null ? Collections.<String, Object>emptyMap() : options);
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
     * 判断是否Voice附件。
     *
     * @param attachment 附件参数。
     * @return 如果Voice附件满足条件则返回 true，否则返回 false。
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
     * 读取附件Bytes。
     *
     * @param attachment 附件参数。
     * @return 返回读取到的附件Bytes。
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
     * 合并用量。
     *
     * @param usage 用量参数。
     * @return 返回用量结果。
     */
    private Map<String, Object> mergeUsage(Map<String, Object> usage) {
        return usage == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(usage);
    }

    /**
     * 执行扩展名相关逻辑。
     *
     * @param mimeType MIME 类型参数。
     * @return 返回extension结果。
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

    /** 表示语音结果，携带调用方后续判断所需信息。 */
    public static class SpeechOutcome {
        /** 是否启用success。 */
        private final boolean success;

        /** 记录语音中的附件。 */
        private final MessageAttachment attachment;

        /** 记录语音中的媒体引用。 */
        private final String mediaReference;

        /** 记录语音中的提供方。 */
        private final String provider;

        /** 记录语音中的错误。 */
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
            this.mediaUsage =
                    mediaUsage == null
                            ? Collections.<String, Object>emptyMap()
                            : new LinkedHashMap<String, Object>(mediaUsage);
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
         * 判断是否Success。
         *
         * @return 如果Success满足条件则返回 true，否则返回 false。
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 读取附件。
         *
         * @return 返回读取到的附件。
         */
        public MessageAttachment getAttachment() {
            return attachment;
        }

        /**
         * 读取媒体Reference。
         *
         * @return 返回读取到的媒体Reference。
         */
        public String getMediaReference() {
            return mediaReference;
        }

        /**
         * 读取提供方。
         *
         * @return 返回读取到的提供方。
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 读取Error。
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

    /** 表示转写结果，携带调用方后续判断所需信息。 */
    public static class TranscriptionOutcome {
        /** 是否启用success。 */
        private final boolean success;

        /** 记录转写中的文本。 */
        private final String text;

        /** 记录转写中的提供方。 */
        private final String provider;

        /** 记录转写中的错误。 */
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
            this.mediaUsage =
                    mediaUsage == null
                            ? Collections.<String, Object>emptyMap()
                            : new LinkedHashMap<String, Object>(mediaUsage);
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
         * 判断是否Success。
         *
         * @return 如果Success满足条件则返回 true，否则返回 false。
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 读取Text。
         *
         * @return 返回读取到的Text。
         */
        public String getText() {
            return text;
        }

        /**
         * 读取提供方。
         *
         * @return 返回读取到的提供方。
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 读取Error。
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
