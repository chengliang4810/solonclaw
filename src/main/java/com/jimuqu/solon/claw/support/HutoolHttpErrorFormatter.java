package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 承载HutoolHTTP错误Formatter相关状态和辅助逻辑。 */
public final class HutoolHttpErrorFormatter {
    /** 错误预览最大字节的统一常量值。 */
    private static final int ERROR_PREVIEW_MAX_BYTES = 4096;

    /** 错误预览最大CHARS的统一常量值。 */
    private static final int ERROR_PREVIEW_MAX_CHARS = 1000;

    /** 创建Hutool HTTP Error Formatter实例。 */
    private HutoolHttpErrorFormatter() {}

    /**
     * 执行failure相关逻辑。
     *
     * @param purpose purpose 参数。
     * @param response 当前响应对象。
     * @return 返回failure结果。
     */
    public static String failure(String purpose, HttpResponse response) {
        int status = response == null ? -1 : response.getStatus();
        return failure(purpose, status, response == null ? null : response.bodyStream());
    }

    /**
     * 执行failure相关逻辑。
     *
     * @param purpose purpose 参数。
     * @param status 状态参数。
     * @param bodyStream 正文流参数。
     * @return 返回failure结果。
     */
    public static String failure(String purpose, int status, InputStream bodyStream) {
        String base = StrUtil.blankToDefault(purpose, "HTTP request") + " failed: HTTP " + status;
        String preview = readPreview(bodyStream);
        if (StrUtil.isBlank(preview)) {
            return base;
        }
        String safe = preview.replace('\r', ' ').replace('\n', ' ').trim();
        safe = SecretRedactor.redact(safe, ERROR_PREVIEW_MAX_CHARS);
        if (StrUtil.isBlank(safe)) {
            return base;
        }
        return base + ", response preview: " + safe;
    }

    /**
     * 读取Preview。
     *
     * @param stream 流参数。
     * @return 返回读取到的Preview。
     */
    private static String readPreview(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int remaining = ERROR_PREVIEW_MAX_BYTES;
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
