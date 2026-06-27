package com.jimuqu.solon.claw.support;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 输入流预览读取辅助逻辑，用于错误响应等只需要小片段正文的场景。 */
public final class InputStreamPreviewSupport {
    /** 工具类不允许创建实例。 */
    private InputStreamPreviewSupport() {}

    /**
     * 读取 UTF-8 文本预览。
     *
     * @param stream 输入流。
     * @param maxBytes 最大读取字节数。
     * @return 预览文本；读取失败时返回低敏错误类型。
     */
    public static String readUtf8(InputStream stream, int maxBytes) {
        if (stream == null) {
            return "";
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int remaining = Math.max(0, maxBytes);
            int read;
            while (remaining > 0
                    && (read = stream.read(buffer, 0, Math.min(buffer.length, remaining))) >= 0) {
                output.write(buffer, 0, read);
                remaining -= read;
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "unavailable: " + e.getClass().getSimpleName();
        }
    }
}
