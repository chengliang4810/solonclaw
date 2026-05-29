package com.jimuqu.solon.claw.plugin.provider;

import java.util.Map;

/** 独立语音转写后端接口。 */
public interface TranscriptionProvider {
    String name();

    boolean isAvailable();

    TranscriptionResult transcribe(byte[] audio, String mimeType, Map<String, Object> options);

    class TranscriptionResult {
        private final boolean success;
        private final String text;
        private final String error;
        private final Map<String, Object> mediaUsage;

        private TranscriptionResult(
                boolean success, String text, String error, Map<String, Object> mediaUsage) {
            this.success = success;
            this.text = text;
            this.error = error;
            this.mediaUsage = mediaUsage;
        }

        public static TranscriptionResult ok(String text) {
            return new TranscriptionResult(true, text, null, null);
        }

        public static TranscriptionResult fail(String error) {
            return new TranscriptionResult(false, null, error, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getText() {
            return text;
        }

        public String getError() {
            return error;
        }

        public Map<String, Object> getMediaUsage() {
            return mediaUsage;
        }
    }
}
