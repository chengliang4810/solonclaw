package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.io.File;
import java.util.Locale;

/** Shared terminal path helpers for foreground and managed background commands. */
final class TerminalPathSupport {
    private TerminalPathSupport() {}

    static String toProcessCwd(String cwd) {
        return toProcessCwd(cwd, isWindows());
    }

    static String toProcessCwd(String cwd, boolean windows) {
        String value = StrUtil.nullToEmpty(cwd);
        if (!windows || value.length() < 2) {
            return value;
        }
        if (value.charAt(0) != '/' || !isAsciiLetter(value.charAt(1))) {
            return value;
        }
        if (value.length() > 2 && value.charAt(2) != '/') {
            return value;
        }
        StringBuilder converted = new StringBuilder();
        converted.append(Character.toUpperCase(value.charAt(1))).append(':');
        if (value.length() == 2) {
            converted.append('\\');
            return converted.toString();
        }
        for (int i = 2; i < value.length(); i++) {
            char ch = value.charAt(i);
            converted.append(ch == '/' ? '\\' : ch);
        }
        return converted.toString();
    }

    static File resolveSafeCwd(String cwd, File fallback) {
        return resolveSafeCwd(cwd, fallback, isWindows());
    }

    static File resolveSafeCwd(String cwd, File fallback, boolean windows) {
        File fallbackDir = fallback == null ? new File(".") : fallback;
        if (StrUtil.isBlank(cwd)) {
            return fallbackDir.getAbsoluteFile();
        }
        File candidate = new File(toProcessCwd(cwd, windows)).getAbsoluteFile();
        if (candidate.isDirectory()) {
            return candidate;
        }
        File parent = candidate.getParentFile();
        while (parent != null) {
            if (parent.isDirectory()) {
                return parent.getAbsoluteFile();
            }
            File next = parent.getParentFile();
            if (next == null || next.equals(parent)) {
                break;
            }
            parent = next;
        }
        return fallbackDir.getAbsoluteFile();
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isAsciiLetter(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }
}
