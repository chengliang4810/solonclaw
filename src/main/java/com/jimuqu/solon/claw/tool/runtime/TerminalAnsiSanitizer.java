package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.regex.Pattern;

/** ECMA-48 ANSI/control sequence sanitizer for terminal and tool output. */
public final class TerminalAnsiSanitizer {
    private static final Pattern ANSI_CONTROL_SEQUENCE =
            Pattern.compile(
                    "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][\\s\\S]*?(?:\\u0007|\\u001B\\\\)|[PX^_][\\s\\S]*?(?:\\u001B\\\\)|[ -/]+[0-~]|[0-~])|\\u009B[0-?]*[ -/]*[@-~]|\\u009D[\\s\\S]*?(?:\\u0007|\\u009C)|[\\u0080-\\u009F]");

    private TerminalAnsiSanitizer() {}

    public static String stripAnsi(String text) {
        String value = StrUtil.nullToEmpty(text);
        if (!hasEscapeLikeByte(value)) {
            return value;
        }
        return ANSI_CONTROL_SEQUENCE.matcher(value).replaceAll("");
    }

    private static boolean hasEscapeLikeByte(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\u001B' || (ch >= '\u0080' && ch <= '\u009F')) {
                return true;
            }
        }
        return false;
    }
}
