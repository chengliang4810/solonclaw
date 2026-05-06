package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.util.regex.Pattern;

/** Strips leaked terminal response sequences from local CLI/TUI input. */
public class TerminalInputSanitizer {
    private static final Pattern DSR_RESPONSE =
            Pattern.compile("(?:\\u001B\\[|\\^\\[\\[)\\d{1,5};\\d{1,5}R");
    private static final Pattern SGR_MOUSE_RESPONSE =
            Pattern.compile("(?:\\u001B\\[|\\^\\[\\[)?<\\d{1,5};\\d{1,5};\\d{1,5}[Mm]");

    private TerminalInputSanitizer() {}

    public static String stripLeakedTerminalResponses(String input) {
        String value = StrUtil.nullToEmpty(input);
        value = DSR_RESPONSE.matcher(value).replaceAll("");
        value = SGR_MOUSE_RESPONSE.matcher(value).replaceAll("");
        return value;
    }
}
