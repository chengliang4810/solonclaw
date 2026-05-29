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
    private static final long MAX_AUDIO_BYTES = 32L * 1024L * 1024L;

    private final AttachmentCacheService attachmentCacheService;
    private final List<SpeechProvider> speechProviders;
    private final List<TranscriptionProvider> transcriptionProviders;

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
                speechProviders == null
                        ? Collections.<SpeechProvider>emptyList()
                        : speechProviders;
        this.transcriptionProviders =
                transcriptionProviders == null
                        ? Collections.<TranscriptionProvider>emptyList()
                        : transcriptionProviders;
    }

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
                    attachment, attachmentCacheService.mediaReference(attachment), provider.name(), usage);
        } catch (Exception e) {
            return SpeechOutcome.fail(safeError(e.getMessage()));
        }
    }

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

    private SpeechProvider chooseSpeechProvider() {
        for (SpeechProvider provider : speechProviders) {
            if (provider != null && provider.isAvailable()) {
                return provider;
            }
        }
        return null;
    }

    private TranscriptionProvider chooseTranscriptionProvider() {
        for (TranscriptionProvider provider : transcriptionProviders) {
            if (provider != null && provider.isAvailable()) {
                return provider;
            }
        }
        return null;
    }

    private boolean isVoiceAttachment(MessageAttachment attachment) {
        if (attachment == null) {
            return false;
        }
        String kind = StrUtil.nullToEmpty(attachment.getKind()).toLowerCase(Locale.ROOT);
        String mime = StrUtil.nullToEmpty(attachment.getMimeType()).toLowerCase(Locale.ROOT);
        return "voice".equals(kind) || mime.startsWith("audio/");
    }

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

    private Map<String, Object> mergeUsage(Map<String, Object> usage) {
        return usage == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(usage);
    }

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

    private String safeError(String value) {
        return SecretRedactor.redact(StrUtil.blankToDefault(value, "Speech operation failed"), 1000);
    }

    public static class SpeechOutcome {
        private final boolean success;
        private final MessageAttachment attachment;
        private final String mediaReference;
        private final String provider;
        private final String error;
        private final Map<String, Object> mediaUsage;

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

        public static SpeechOutcome ok(
                MessageAttachment attachment,
                String mediaReference,
                String provider,
                Map<String, Object> mediaUsage) {
            return new SpeechOutcome(true, attachment, mediaReference, provider, null, mediaUsage);
        }

        public static SpeechOutcome fail(String error) {
            return new SpeechOutcome(false, null, null, null, error, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public MessageAttachment getAttachment() {
            return attachment;
        }

        public String getMediaReference() {
            return mediaReference;
        }

        public String getProvider() {
            return provider;
        }

        public String getError() {
            return error;
        }

        public Map<String, Object> getMediaUsage() {
            return mediaUsage;
        }
    }

    public static class TranscriptionOutcome {
        private final boolean success;
        private final String text;
        private final String provider;
        private final String error;
        private final Map<String, Object> mediaUsage;

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

        public static TranscriptionOutcome ok(
                String text, String provider, Map<String, Object> mediaUsage) {
            return new TranscriptionOutcome(true, text, provider, null, mediaUsage);
        }

        public static TranscriptionOutcome fail(String error) {
            return new TranscriptionOutcome(false, null, null, error, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getText() {
            return text;
        }

        public String getProvider() {
            return provider;
        }

        public String getError() {
            return error;
        }

        public Map<String, Object> getMediaUsage() {
            return mediaUsage;
        }
    }
}
