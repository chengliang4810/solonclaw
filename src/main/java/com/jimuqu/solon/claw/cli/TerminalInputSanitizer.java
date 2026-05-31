package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.util.regex.Pattern;

/** Strips leaked terminal response sequences from local CLI/TUI input. */
public class TerminalInputSanitizer {
    private static final Pattern DSR_RESPONSE =
            Pattern.compile("(?:\\u001B\\[|\\^\\[\\[|\\u009B)\\d{1,5};\\d{1,5}R");
    private static final Pattern SGR_MOUSE_RESPONSE =
            Pattern.compile("(?:\\u001B\\[|\\^\\[\\[|\\u009B)?<\\d{1,5};\\d{1,5};\\d{1,5}[Mm]");
    private static final Pattern DEGRADED_MOUSE_BURST_NOISE =
            Pattern.compile("(?s)^(?=.*\\d)(?=.*;)(?=(?:[^Mm]*[Mm]){3})[\\d;<\\[\\]IMm \\u001B]+$");
    private static final Pattern OSC_RESPONSE =
            Pattern.compile("(?:\\u001B\\]|\\u009D)[\\s\\S]*?(?:\\u0007|\\u001B\\\\|\\u009C)");
    private static final Pattern OSC11_BACKGROUND_RESPONSE =
            Pattern.compile(
                    "(?:\\]|\\^\\])11;rgb:[0-9a-fA-F]{1,4}/[0-9a-fA-F]{1,4}/[0-9a-fA-F]{1,4}(?:\\u0007|\\u001B\\\\|\\u009C|\\^G|\\^\\\\)");
    private static final Pattern BRACKETED_PASTE_WRAPPER =
            Pattern.compile("(?:\\u001B\\[|\\^\\[\\[|\\u009B)(?:200|201)~");

    private TerminalInputSanitizer() {}

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
