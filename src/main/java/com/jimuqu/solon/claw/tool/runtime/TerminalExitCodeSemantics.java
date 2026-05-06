package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Hermes-style notes for commands whose non-zero exit codes are informational. */
final class TerminalExitCodeSemantics {
    private static final Map<String, Map<Integer, String>> SEMANTICS =
            new LinkedHashMap<String, Map<Integer, String>>();

    static {
        register("grep", 1, "No matches found (not an error)");
        register("egrep", 1, "No matches found (not an error)");
        register("fgrep", 1, "No matches found (not an error)");
        register("rg", 1, "No matches found (not an error)");
        register("ag", 1, "No matches found (not an error)");
        register("ack", 1, "No matches found (not an error)");
        register("diff", 1, "Files differ (expected, not an error)");
        register("colordiff", 1, "Files differ (expected, not an error)");
        register("find", 1, "Some directories were inaccessible (partial results may still be valid)");
        register("test", 1, "Condition evaluated to false (expected, not an error)");
        register("[", 1, "Condition evaluated to false (expected, not an error)");
        register("curl", 6, "Could not resolve host");
        register("curl", 7, "Failed to connect to host");
        register("curl", 22, "HTTP response code indicated error (e.g. 404, 500)");
        register("curl", 28, "Operation timed out");
        register(
                "git",
                1,
                "Non-zero exit (often normal - e.g. 'git diff' returns 1 when files differ)");
    }

    private TerminalExitCodeSemantics() {}

    static String interpret(String command, Integer exitCode) {
        if (exitCode == null || exitCode.intValue() == 0 || StrUtil.isBlank(command)) {
            return null;
        }
        String baseCommand = baseCommand(lastCommandSegment(command));
        if (StrUtil.isBlank(baseCommand)) {
            return null;
        }
        Map<Integer, String> commandSemantics =
                SEMANTICS.get(baseCommand.toLowerCase(Locale.ROOT));
        if (commandSemantics == null) {
            return null;
        }
        return commandSemantics.get(exitCode);
    }

    private static void register(String command, int exitCode, String meaning) {
        Map<Integer, String> commandSemantics = SEMANTICS.get(command);
        if (commandSemantics == null) {
            commandSemantics = new LinkedHashMap<Integer, String>();
            SEMANTICS.put(command, commandSemantics);
        }
        commandSemantics.put(Integer.valueOf(exitCode), meaning);
    }

    private static String lastCommandSegment(String command) {
        String value = StrUtil.nullToEmpty(command);
        int start = 0;
        int i = 0;
        while (i < value.length()) {
            char ch = value.charAt(i);
            if (ch == '\'' || ch == '"') {
                i = skipQuoted(value, i);
                continue;
            }
            if (ch == '\\' && i + 1 < value.length()) {
                i += 2;
                continue;
            }
            if (ch == ';' || ch == '|') {
                if (i + 1 < value.length() && value.charAt(i + 1) == ch) {
                    i++;
                }
                start = i + 1;
            } else if (ch == '&' && i + 1 < value.length() && value.charAt(i + 1) == '&') {
                start = i + 2;
                i++;
            }
            i++;
        }
        return value.substring(Math.min(start, value.length())).trim();
    }

    private static int skipQuoted(String value, int start) {
        char quote = value.charAt(start);
        int i = start + 1;
        while (i < value.length()) {
            char ch = value.charAt(i);
            if (quote == '"' && ch == '\\' && i + 1 < value.length()) {
                i += 2;
                continue;
            }
            if (ch == quote) {
                return i + 1;
            }
            i++;
        }
        return value.length();
    }

    private static String baseCommand(String segment) {
        int i = 0;
        while (i < segment.length()) {
            while (i < segment.length() && Character.isWhitespace(segment.charAt(i))) {
                i++;
            }
            if (i >= segment.length()) {
                return "";
            }
            int end = tokenEnd(segment, i);
            String token = segment.substring(i, end);
            if (!looksLikeEnvAssignment(token)) {
                return stripPath(stripQuotes(token));
            }
            i = end;
        }
        return "";
    }

    private static int tokenEnd(String segment, int start) {
        int i = start;
        while (i < segment.length() && !Character.isWhitespace(segment.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean looksLikeEnvAssignment(String token) {
        if (StrUtil.isBlank(token) || token.startsWith("-")) {
            return false;
        }
        int equals = token.indexOf('=');
        return equals > 0;
    }

    private static String stripPath(String token) {
        String normalized = token.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String stripQuotes(String token) {
        if (token.length() >= 2) {
            char first = token.charAt(0);
            char last = token.charAt(token.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return token.substring(1, token.length() - 1);
            }
        }
        return token;
    }
}
