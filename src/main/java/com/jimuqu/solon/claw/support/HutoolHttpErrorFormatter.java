package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Formats Hutool HTTP failures with bounded, redacted response previews. */
public final class HutoolHttpErrorFormatter {
    private static final int ERROR_PREVIEW_MAX_BYTES = 4096;
    private static final int ERROR_PREVIEW_MAX_CHARS = 1000;

    private HutoolHttpErrorFormatter() {}

    public static String failure(String purpose, HttpResponse response) {
        int status = response == null ? -1 : response.getStatus();
        return failure(purpose, status, response == null ? null : response.bodyStream());
    }

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
