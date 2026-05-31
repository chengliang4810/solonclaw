package com.jimuqu.solon.claw.plugin.provider;

import java.util.Map;

/** 语音合成后端接口。 */
public interface SpeechProvider {
    String name();

    boolean isAvailable();

    SpeechResult synthesize(String text, String voice, Map<String, Object> options);

    class SpeechResult {
        private final boolean success;
        private final String mimeType;
        private final byte[] audio;
        private final String error;
        private final Map<String, Object> mediaUsage;

        private SpeechResult(
                boolean success,
                String mimeType,
                byte[] audio,
                String error,
                Map<String, Object> mediaUsage) {
            this.success = success;
            this.mimeType = mimeType;
            this.audio = audio;
            this.error = error;
            this.mediaUsage = mediaUsage;
        }

        public static SpeechResult ok(String mimeType, byte[] audio) {
            return new SpeechResult(true, mimeType, audio, null, null);
        }

        public static SpeechResult fail(String error) {
            return new SpeechResult(false, null, null, error, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMimeType() {
            return mimeType;
        }

        public byte[] getAudio() {
            return audio;
        }

        public String getError() {
            return error;
        }

        public Map<String, Object> getMediaUsage() {
            return mediaUsage;
        }
    }
}
