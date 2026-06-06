package com.jimuqu.solon.claw.llm.dialect;

/** 封装原始响应日志辅助逻辑，降低主流程中的重复实现。 */
final class RawResponseLogSupport {
    /** 最大日志正文LENGTH的统一常量值。 */
    private static final int MAX_LOG_BODY_LENGTH = 16000;

    /** 最大HEX字节的统一常量值。 */
    private static final int MAX_HEX_BYTES = 64;

    /** 创建原始响应日志辅助实例。 */
    private RawResponseLogSupport() {}

    /**
     * 执行预览相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回preview结果。
     */
    static String preview(String body) {
        if (body == null) {
            return "<null>";
        }
        String text =
                body.length() <= MAX_LOG_BODY_LENGTH
                        ? body
                        : body.substring(0, MAX_LOG_BODY_LENGTH)
                                + "\n...[truncated, totalLength="
                                + body.length()
                                + "]";
        return escapeControls(text);
    }

    /**
     * 执行hexHead相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回hex Head结果。
     */
    static String hexHead(String body) {
        if (body == null) {
            return "";
        }
        int limit = Math.min(body.length(), MAX_HEX_BYTES);
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            int value = body.charAt(i) & 0xff;
            if (value < 16) {
                buffer.append('0');
            }
            buffer.append(Integer.toHexString(value).toUpperCase(java.util.Locale.ROOT));
        }
        if (body.length() > limit) {
            buffer.append(" ...");
        }
        return buffer.toString();
    }

    /**
     * 转义Controls。
     *
     * @param text 待处理文本。
     * @return 返回escape Controls结果。
     */
    private static String escapeControls(String text) {
        StringBuilder buffer = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                buffer.append("\\n");
            } else if (ch == '\r') {
                buffer.append("\\r");
            } else if (ch == '\t') {
                buffer.append("\\t");
            } else if (ch < 32 || ch == 127) {
                buffer.append("\\u");
                String hex = Integer.toHexString(ch).toUpperCase(java.util.Locale.ROOT);
                for (int j = hex.length(); j < 4; j++) {
                    buffer.append('0');
                }
                buffer.append(hex);
            } else {
                buffer.append(ch);
            }
        }
        return buffer.toString();
    }
}
