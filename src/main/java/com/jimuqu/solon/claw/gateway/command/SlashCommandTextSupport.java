package com.jimuqu.solon.claw.gateway.command;

import java.util.ArrayList;
import java.util.List;

/** 提供 slash command 文本拆分等轻量工具，保证各命令处理器使用同一套引号解析语义。 */
final class SlashCommandTextSupport {
    private SlashCommandTextSupport() {}

    /**
     * 按 shell 风格拆分命令行，保留引号内空格并处理反斜杠转义。
     *
     * @param raw 原始命令文本。
     * @return 返回拆分后的 token 列表。
     */
    static List<String> splitCommandLine(String raw) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        boolean escaping = false;
        boolean tokenStarted = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (escaping) {
                current.append(ch);
                escaping = false;
                tokenStarted = true;
                continue;
            }
            if (ch == '\\') {
                escaping = true;
                tokenStarted = true;
                continue;
            }
            if (quoted) {
                if (ch == quote) {
                    quoted = false;
                } else {
                    current.append(ch);
                    tokenStarted = true;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quoted = true;
                quote = ch;
                tokenStarted = true;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(ch);
                tokenStarted = true;
            }
        }
        if (escaping) {
            current.append('\\');
        }
        if (tokenStarted) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * 格式化字节数。
     *
     * @param bytes 字节数。
     * @return 返回带单位的容量文本。
     */
    static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = new String[] {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        while (value >= 1024D && unitIndex < units.length - 1) {
            value = value / 1024D;
            unitIndex++;
        }
        return String.format(
                java.util.Locale.ROOT, "%.1f %s", Double.valueOf(value), units[unitIndex]);
    }
}
