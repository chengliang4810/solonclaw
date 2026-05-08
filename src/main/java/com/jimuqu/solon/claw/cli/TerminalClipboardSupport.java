package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** OSC52 clipboard helper for local terminal sessions. */
public final class TerminalClipboardSupport {
    private static final int MAX_COPY_CHARS = 200_000;
    private static final String OSC52_PREFIX = "\u001B]52;c;";
    private static final String OSC52_SUFFIX = "\u0007";

    private TerminalClipboardSupport() {}

    public static boolean copy(PrintWriter writer, String text) {
        String value = StrUtil.nullToEmpty(text);
        if (writer == null || value.length() == 0) {
            return false;
        }
        String clipped = value.length() > MAX_COPY_CHARS ? value.substring(0, MAX_COPY_CHARS) : value;
        writer.print(osc52(clipped));
        writer.flush();
        return true;
    }

    static String osc52(String text) {
        String base64 =
                Base64.getEncoder()
                        .encodeToString(StrUtil.nullToEmpty(text).getBytes(StandardCharsets.UTF_8));
        return OSC52_PREFIX + base64 + OSC52_SUFFIX;
    }
}
