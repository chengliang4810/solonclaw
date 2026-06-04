package com.jimuqu.solon.claw.plugin.provider;

import java.util.Map;

/** 视频生成后端接口。 */
public interface VideoGenProvider {
    String name();

    boolean isAvailable();

    VideoGenResult generate(String prompt, Map<String, Object> options);

    class VideoGenResult {
        private final boolean success;
        private final String url;
        private final String error;

        private VideoGenResult(boolean success, String url, String error) {
            this.success = success;
            this.url = url;
            this.error = error;
        }

        public static VideoGenResult ok(String url) {
            return new VideoGenResult(true, url, null);
        }

        public static VideoGenResult fail(String error) {
            return new VideoGenResult(false, null, error);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getUrl() {
            return url;
        }

        public String getError() {
            return error;
        }
    }
}
