package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.regex.Pattern;

/** ECMA-48 ANSI/control sequence sanitizer for terminal and tool output. */
public final class TerminalAnsiSanitizer {
    private static final Pattern ANSI_CONTROL_SEQUENCE =
            Pattern.compile(
                    "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][\\s\\S]*?(?:\\u0007|\\u001B\\\\)|[PX^_][\\s\\S]*?(?:\\u001B\\\\)|[ -/]+[0-~]|[0-~])|\\u009B[0-?]*[ -/]*[@-~]|\\u009D[\\s\\S]*?(?:\\u0007|\\u009C)|[\\u0080-\\u009F]");
    private static final Pattern C0_DISPLAY_CONTROL =
            Pattern.compile("[\\u0000-\\u0008\\u000B-\\u001A\\u001C-\\u001F\\u007F]");

    private TerminalAnsiSanitizer() {}

    public static String stripAnsi(String text) {
        String value = StrUtil.nullToEmpty(text);
        if (!hasEscapeLikeByte(value)) {
            return value;
        }
        String stripped = ANSI_CONTROL_SEQUENCE.matcher(value).replaceAll("");
        return C0_DISPLAY_CONTROL.matcher(stripped).replaceAll("");
    }

    private static boolean hasEscapeLikeByte(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\u001B'
                    || ch == '\u007F'
                    || (ch >= '\u0000' && ch <= '\u0008')
                    || (ch >= '\u000B' && ch <= '\u001A')
                    || (ch >= '\u001C' && ch <= '\u001F')
                    || (ch >= '\u0080' && ch <= '\u009F')) {
                return true;
            }
        }
        return false;
    }
}
