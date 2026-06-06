package com.jimuqu.solon.claw.plugin.provider;

import java.util.Map;

/** 独立语音转写后端接口。 */
public interface TranscriptionProvider {
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
     * 执行语音转写请求并返回识别文本。
     *
     * @param audio 音频参数。
     * @param mimeType MIME 类型参数。
     * @param options options 参数。
     * @return 返回transcribe结果。
     */
    TranscriptionResult transcribe(byte[] audio, String mimeType, Map<String, Object> options);

    /** 表示转写结果，携带调用方后续判断所需信息。 */
    class TranscriptionResult {
        /** 是否启用success。 */
        private final boolean success;

        /** 记录转写中的文本。 */
        private final String text;

        /** 记录转写中的错误。 */
        private final String error;

        /** 保存媒体用量映射，便于按键快速查询。 */
        private final Map<String, Object> mediaUsage;

        /**
         * 创建Transcription结果实例，并注入运行所需依赖。
         *
         * @param success success 参数。
         * @param text 待处理文本。
         * @param error 错误参数。
         * @param mediaUsage 媒体用量参数。
         */
        private TranscriptionResult(
                boolean success, String text, String error, Map<String, Object> mediaUsage) {
            this.success = success;
            this.text = text;
            this.error = error;
            this.mediaUsage = mediaUsage;
        }

        /**
         * 构造成功结果。
         *
         * @param text 待处理文本。
         * @return 返回ok结果。
         */
        public static TranscriptionResult ok(String text) {
            return new TranscriptionResult(true, text, null, null);
        }

        /**
         * 构造失败结果并携带安全错误信息。
         *
         * @param error 错误参数。
         * @return 返回fail结果。
         */
        public static TranscriptionResult fail(String error) {
            return new TranscriptionResult(false, null, error, null);
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
