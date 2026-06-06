package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.regex.Pattern;

/** 承载终端ANSI清理器相关状态和辅助逻辑。 */
public final class TerminalAnsiSanitizer {
    /** ANSI控制SEQUENCE的统一常量值。 */
    private static final Pattern ANSI_CONTROL_SEQUENCE =
            Pattern.compile(
                    "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][\\s\\S]*?(?:\\u0007|\\u001B\\\\)|[PX^_][\\s\\S]*?(?:\\u001B\\\\)|[ -/]+[0-~]|[0-~])|\\u009B[0-?]*[ -/]*[@-~]|\\u009D[\\s\\S]*?(?:\\u0007|\\u009C)|[\\u0080-\\u009F]");

    /** C0展示控制的统一常量值。 */
    private static final Pattern C0_DISPLAY_CONTROL =
            Pattern.compile("[\\u0000-\\u0008\\u000B-\\u001A\\u001C-\\u001F\\u007F]");

    /** BIDI展示控制的统一常量值。 */
    private static final Pattern BIDI_DISPLAY_CONTROL =
            Pattern.compile("[\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

    /** 创建终端ANSI清理器实例。 */
    private TerminalAnsiSanitizer() {}

    /**
     * 剥离ANSI。
     *
     * @param text 待处理文本。
     * @return 返回strip ANSI结果。
     */
    public static String stripAnsi(String text) {
        String value = StrUtil.nullToEmpty(text);
        if (!hasEscapeLikeByte(value)) {
            return value;
        }
        String stripped = ANSI_CONTROL_SEQUENCE.matcher(value).replaceAll("");
        stripped = C0_DISPLAY_CONTROL.matcher(stripped).replaceAll("");
        return BIDI_DISPLAY_CONTROL.matcher(stripped).replaceAll("");
    }

    /**
     * 判断是否存在Escape Like Byte。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Escape Like Byte满足条件则返回 true，否则返回 false。
     */
    private static boolean hasEscapeLikeByte(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\u001B'
                    || ch == '\u007F'
                    || (ch >= '\u0000' && ch <= '\u0008')
                    || (ch >= '\u000B' && ch <= '\u001A')
                    || (ch >= '\u001C' && ch <= '\u001F')
                    || ch == '\u061C'
                    || ch == '\u200E'
                    || ch == '\u200F'
                    || (ch >= '\u202A' && ch <= '\u202E')
                    || (ch >= '\u2066' && ch <= '\u2069')
                    || (ch >= '\u0080' && ch <= '\u009F')) {
                return true;
            }
        }
        return false;
    }
}
