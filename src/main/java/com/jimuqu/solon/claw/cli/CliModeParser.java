package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.List;

/** Minimal argument parser for CLI/TUI startup modes. */
public final class CliModeParser {
    private CliModeParser() {}

    public static CliMode parse(String[] args) {
        if (args == null || args.length == 0) {
            return new CliMode(CliMode.Kind.SERVER, null, null);
        }

        CliMode.Kind kind = CliMode.Kind.SERVER;
        String sessionId = null;
        List<String> inputParts = new ArrayList<String>();
        boolean captureRest = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (captureRest) {
                inputParts.add(arg);
                continue;
            }
            if ("--cli".equals(arg) || "cli".equalsIgnoreCase(arg)) {
                kind = CliMode.Kind.CLI;
                continue;
            }
            if ("--tui".equals(arg) || "tui".equalsIgnoreCase(arg)) {
                kind = CliMode.Kind.TUI;
                continue;
            }
            if ("--session".equals(arg) && i + 1 < args.length) {
                sessionId = args[++i];
                continue;
            }
            if (arg != null && arg.startsWith("--session=")) {
                sessionId = arg.substring("--session=".length());
                continue;
            }
            if ("--ask".equals(arg) || "-p".equals(arg)) {
                captureRest = true;
                continue;
            }
            if (kind != CliMode.Kind.SERVER) {
                inputParts.add(arg);
            }
        }

        String input = StrUtil.join(" ", inputParts).trim();
        return new CliMode(kind, StrUtil.isBlank(input) ? null : input, sessionId);
    }
}
