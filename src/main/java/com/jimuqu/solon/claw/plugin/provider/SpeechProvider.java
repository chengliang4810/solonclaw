package com.jimuqu.solon.claw.plugin.provider;

import java.util.Map;

/** 语音合成后端接口。 */
public interface SpeechProvider {
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
     * 执行语音合成请求并返回缓存后的音频引用。
     *
     * @param text 待处理文本。
     * @param voice 语音参数。
     * @param options options 参数。
     * @return 返回synthesize结果。
     */
    SpeechResult synthesize(String text, String voice, Map<String, Object> options);

    /** 表示语音结果，携带调用方后续判断所需信息。 */
    class SpeechResult {
        /** 是否启用success。 */
        private final boolean success;

        /** 记录语音中的MIME 类型。 */
        private final String mimeType;

        /** 记录语音中的音频。 */
        private final byte[] audio;

        /** 记录语音中的错误。 */
        private final String error;

        /** 保存媒体用量映射，便于按键快速查询。 */
        private final Map<String, Object> mediaUsage;

        /**
         * 创建语音结果实例，并注入运行所需依赖。
         *
         * @param success success 参数。
         * @param mimeType MIME 类型参数。
         * @param audio 音频参数。
         * @param error 错误参数。
         * @param mediaUsage 媒体用量参数。
         */
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

        /**
         * 构造成功结果。
         *
         * @param mimeType MIME 类型参数。
         * @param audio 音频参数。
         * @return 返回ok结果。
         */
        public static SpeechResult ok(String mimeType, byte[] audio) {
            return new SpeechResult(true, mimeType, audio, null, null);
        }

        /**
         * 构造失败结果并携带安全错误信息。
         *
         * @param error 错误参数。
         * @return 返回fail结果。
         */
        public static SpeechResult fail(String error) {
            return new SpeechResult(false, null, null, error, null);
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
         * 读取Mime类型。
         *
         * @return 返回读取到的Mime类型。
         */
        public String getMimeType() {
            return mimeType;
        }

        /**
         * 读取Audio。
         *
         * @return 返回读取到的Audio。
         */
        public byte[] getAudio() {
            return audio;
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
