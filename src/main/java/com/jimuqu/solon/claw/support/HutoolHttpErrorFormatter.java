package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import java.io.InputStream;

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
     * 读取受控 HTTP 响应正文，统一阻断重定向和错误状态。
     *
     * @param purpose 请求用途说明。
     * @param response 当前 Hutool HTTP 响应。
     * @return 受限读取后的响应正文。
     */
    public static String guardedBody(String purpose, HttpResponse response) {
        String location = response == null ? null : response.header("Location");
        int status = response == null ? -1 : response.getStatus();
        return guardedBody(purpose, status, location, response);
    }

    /**
     * 读取受控 HTTP 响应正文，供测试和无响应对象场景复用状态处理。
     *
     * @param purpose 请求用途说明。
     * @param status HTTP 状态码。
     * @param location 重定向地址。
     * @param response 当前 Hutool HTTP 响应。
     * @return 受限读取后的响应正文。
     */
    public static String guardedBody(
            String purpose, int status, String location, HttpResponse response) {
        if (status >= 300 && status < 400) {
            throw new IllegalStateException(
                    StrUtil.blankToDefault(purpose, "HTTP request")
                            + " blocked redirect: HTTP "
                            + status
                            + " -> "
                            + SecretRedactor.maskUrl(location));
        }
        if (status >= 400) {
            throw new IllegalStateException(
                    failure(purpose, status, response == null ? null : response.bodyStream()));
        }
        return BoundedAttachmentIO.readHutoolText(response, BoundedAttachmentIO.JSON_MAX_BYTES);
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
        String preview = InputStreamPreviewSupport.readUtf8(bodyStream, ERROR_PREVIEW_MAX_BYTES);
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

}
