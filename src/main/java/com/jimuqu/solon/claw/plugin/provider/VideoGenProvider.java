package com.jimuqu.solon.claw.plugin.provider;

import java.util.Map;

/** 视频生成后端接口。 */
public interface VideoGenProvider {
    /**
     * 执行名称相关逻辑。
     *
     * @return 返回名称结果。
     */
    String name();

    /**
     * 判断是否Available。
     *
     * @return 如果Available满足条件则返回 true，否则返回 false。
     */
    boolean isAvailable();

    /**
     * 执行图片生成请求并返回缓存后的媒体引用。
     *
     * @param prompt 提示词参数。
     * @param options options 参数。
     * @return 返回generate结果。
     */
    VideoGenResult generate(String prompt, Map<String, Object> options);

    /** 表示VideoGen结果，携带调用方后续判断所需信息。 */
    class VideoGenResult {
        /** 是否启用success。 */
        private final boolean success;

        /** 记录VideoGen中的URL。 */
        private final String url;

        /** 记录VideoGen中的错误。 */
        private final String error;

        /**
         * 创建Video Gen结果实例，并注入运行所需依赖。
         *
         * @param success success 参数。
         * @param url 待校验或访问的 URL。
         * @param error 错误参数。
         */
        private VideoGenResult(boolean success, String url, String error) {
            this.success = success;
            this.url = url;
            this.error = error;
        }

        /**
         * 构造成功结果。
         *
         * @param url 待校验或访问的 URL。
         * @return 返回ok结果。
         */
        public static VideoGenResult ok(String url) {
            return new VideoGenResult(true, url, null);
        }

        /**
         * 构造失败结果并携带安全错误信息。
         *
         * @param error 错误参数。
         * @return 返回fail结果。
         */
        public static VideoGenResult fail(String error) {
            return new VideoGenResult(false, null, error);
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
         * 读取URL。
         *
         * @return 返回读取到的URL。
         */
        public String getUrl() {
            return url;
        }

        /**
         * 读取Error。
         *
         * @return 返回读取到的Error。
         */
        public String getError() {
            return error;
        }
    }
}
