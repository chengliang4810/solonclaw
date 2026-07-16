package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import java.util.regex.Pattern;

/** 承载终端输入清理器相关状态和辅助逻辑。 */
public class TerminalUiInputSanitizer {
    /** DSR响应的统一常量值。 */
    private static final Pattern DSR_RESPONSE =
            Pattern.compile("(?:\\u001B\\[|\\^\\[\\[|\\u009B)\\d{1,5};\\d{1,5}R");

    /** SGRMOUSE响应的统一常量值。 */
    private static final Pattern SGR_MOUSE_RESPONSE =
            Pattern.compile("(?:\\u001B\\[|\\^\\[\\[|\\u009B)?<\\d{1,5};\\d{1,5};\\d{1,5}[Mm]");

    /** DEGRADEDMOUSEBURSTNOISE的统一常量值。 */
    private static final Pattern DEGRADED_MOUSE_BURST_NOISE =
            Pattern.compile("(?s)^(?=.*\\d)(?=.*;)(?=(?:[^Mm]*[Mm]){3})[\\d;<\\[\\]IMm \\u001B]+$");

    /** OSC响应的统一常量值。 */
    private static final Pattern OSC_RESPONSE =
            Pattern.compile("(?:\\u001B\\]|\\u009D)[\\s\\S]*?(?:\\u0007|\\u001B\\\\|\\u009C)");

    /** OSC11BACKGROUND响应的统一常量值。 */
    private static final Pattern OSC11_BACKGROUND_RESPONSE =
            Pattern.compile(
                    "(?:\\]|\\^\\])11;rgb:[0-9a-fA-F]{1,4}/[0-9a-fA-F]{1,4}/[0-9a-fA-F]{1,4}(?:\\u0007|\\u001B\\\\|\\u009C|\\^G|\\^\\\\)");

    /** BRACKETEDPASTEWRAPPER的统一常量值。 */
    private static final Pattern BRACKETED_PASTE_WRAPPER =
            Pattern.compile("(?:\\u001B\\[|\\^\\[\\[|\\u009B)(?:200|201)~");

    /** 创建终端输入清理器实例。 */
    private TerminalUiInputSanitizer() {}

    /**
     * 剥离Leaked终端Responses。
     *
     * @param input 输入参数。
     * @return 返回strip Leaked终端Responses结果。
     */
    public static String stripLeakedTerminalResponses(String input) {
        String value = StrUtil.nullToEmpty(input);
        value = OSC_RESPONSE.matcher(value).replaceAll("");
        value = OSC11_BACKGROUND_RESPONSE.matcher(value).replaceAll("");
        value = BRACKETED_PASTE_WRAPPER.matcher(value).replaceAll("");
        value = DSR_RESPONSE.matcher(value).replaceAll("");
        if (DEGRADED_MOUSE_BURST_NOISE.matcher(value).matches()) {
            return "";
        }
        value = SGR_MOUSE_RESPONSE.matcher(value).replaceAll("");
        return value;
    }
}
