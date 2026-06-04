package com.jimuqu.solon.claw.plugin.provider;

import java.util.Map;

/** 图像生成后端接口。 */
public interface ImageGenProvider {
    String name();

    boolean isAvailable();

    ImageGenResult generate(String prompt, String aspectRatio, Map<String, Object> options);

    class ImageGenResult {
        private final boolean success;
        private final String url;
        private final String error;

        private ImageGenResult(boolean success, String url, String error) {
            this.success = success;
            this.url = url;
            this.error = error;
        }

        public static ImageGenResult ok(String url) {
            return new ImageGenResult(true, url, null);
        }

        public static ImageGenResult fail(String error) {
            return new ImageGenResult(false, null, error);
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
