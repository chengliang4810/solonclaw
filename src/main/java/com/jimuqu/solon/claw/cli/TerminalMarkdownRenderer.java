package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;

/** Lightweight streaming Markdown renderer for the local terminal. */
public class TerminalMarkdownRenderer {
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private final StringBuilder pendingLine = new StringBuilder();
    private boolean codeBlock;

    public String render(String text) {
        if (StrUtil.isEmpty(text)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        pendingLine.append(text);
        int newlineIndex;
        while ((newlineIndex = pendingLine.indexOf("\n")) >= 0) {
            String line = pendingLine.substring(0, newlineIndex + 1);
            pendingLine.delete(0, newlineIndex + 1);
            out.append(renderLine(line));
        }
        return out.toString();
    }

    public String flush() {
        if (pendingLine.length() == 0) {
            return "";
        }
        String line = pendingLine.toString();
        pendingLine.setLength(0);
        return renderLine(line);
    }

    String renderLine(String line) {
        if (line == null || line.length() == 0) {
            return "";
        }
        String newline = line.endsWith("\n") ? "\n" : "";
        String body = newline.length() == 0 ? line : line.substring(0, line.length() - 1);
        String trimmed = body.trim();
        if (trimmed.startsWith("```")) {
            codeBlock = !codeBlock;
            String label = trimmed.length() > 3 ? " " + trimmed.substring(3).trim() : "";
            return DIM + "```" + label + RESET + newline;
        }
        if (codeBlock) {
            return GREEN + body + RESET + newline;
        }
        if (trimmed.startsWith("### ")) {
            return BOLD + CYAN + trimmed.substring(4).trim() + RESET + newline;
        }
        if (trimmed.startsWith("## ")) {
            return BOLD + CYAN + trimmed.substring(3).trim() + RESET + newline;
        }
        if (trimmed.startsWith("# ")) {
            return BOLD + CYAN + trimmed.substring(2).trim() + RESET + newline;
        }
        if (trimmed.startsWith("> ")) {
            return DIM + "│ " + trimmed.substring(2).trim() + RESET + newline;
        }
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            return YELLOW + "• " + RESET + trimmed.substring(2).trim() + newline;
        }
        int orderedPrefix = orderedListPrefixLength(trimmed);
        if (orderedPrefix > 0) {
            return YELLOW
                    + trimmed.substring(0, orderedPrefix)
                    + RESET
                    + trimmed.substring(orderedPrefix).trim()
                    + newline;
        }
        return body + newline;
    }

    private int orderedListPrefixLength(String line) {
        int index = 0;
        while (index < line.length() && Character.isDigit(line.charAt(index))) {
            index++;
        }
        if (index == 0 || index + 1 >= line.length()) {
            return 0;
        }
        return line.charAt(index) == '.' && line.charAt(index + 1) == ' ' ? index + 2 : 0;
    }
}
