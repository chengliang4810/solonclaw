package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.util.Locale;

/** ANSI skin for the local TUI. */
public final class TerminalSkin {
    private static final String RESET = "\u001B[0m";

    private final String name;
    private final String bold;
    private final String dim;
    private final String accent;
    private final String border;

    private TerminalSkin(String name, String bold, String dim, String accent, String border) {
        this.name = name;
        this.bold = bold;
        this.dim = dim;
        this.accent = accent;
        this.border = border;
    }

    public static TerminalSkin fromEnvironment() {
        return resolve(System.getenv("SOLONCLAW_TERMINAL_SKIN"));
    }

    public static TerminalSkin resolve(String value) {
        String normalized = StrUtil.blankToDefault(value, "classic").trim().toLowerCase(Locale.ROOT);
        if ("mono".equals(normalized) || "plain".equals(normalized)) {
            return new TerminalSkin(
                    "mono",
                    "",
                    "",
                    "",
                    "------------------------------------------------------------");
        }
        if ("contrast".equals(normalized) || "high-contrast".equals(normalized)) {
            return new TerminalSkin(
                    "contrast",
                    "\u001B[1m",
                    "\u001B[37m",
                    "\u001B[96m",
                    "============================================================");
        }
        return new TerminalSkin(
                "classic",
                "\u001B[1m",
                "\u001B[2m",
                "\u001B[36m",
                "────────────────────────────────────────────────────────");
    }

    public static boolean isSkinCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim().toLowerCase(Locale.ROOT);
        return "/skin".equals(value) || value.startsWith("/skin ");
    }

    public String renderHelp() {
        return "当前皮肤：" + name + "\n可选：classic、mono、contrast\n使用：/skin <名称>";
    }

    public String name() {
        return name;
    }

    public String bold(String text) {
        return wrap(bold, text);
    }

    public String dim(String text) {
        return wrap(dim, text);
    }

    public String accent(String text) {
        return wrap(accent, text);
    }

    public String border() {
        return border;
    }

    public String prompt(String text) {
        return bold(accent(text)) + " > ";
    }

    private String wrap(String code, String text) {
        String value = StrUtil.nullToEmpty(text);
        return StrUtil.isBlank(code) ? value : code + value + RESET;
    }
}
