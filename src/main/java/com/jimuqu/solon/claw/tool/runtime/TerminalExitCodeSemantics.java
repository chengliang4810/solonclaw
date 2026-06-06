package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 承载终端退出CodeSemantics相关状态和辅助逻辑。 */
final class TerminalExitCodeSemantics {
    /** SEMANTICS的统一常量值。 */
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
        register(
                "find",
                1,
                "Some directories were inaccessible (partial results may still be valid)");
        register("test", 1, "Condition evaluated to false (expected, not an error)");
        register("[", 1, "Condition evaluated to false (expected, not an error)");
        register("curl", 6, "Could not resolve host");
        register("curl", 7, "Failed to connect to host");
        register("curl", 22, "HTTP response code indicated error (e.g. 404, 500)");
        register("curl", 28, "Operation timed out");
        register("git diff", 1, "Git diff found differences (normal for diff commands)");
    }

    /** 创建终端退出码 Semantics实例。 */
    private TerminalExitCodeSemantics() {}

    /**
     * 执行interpret相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @param exitCode 命令退出码。
     * @return 返回interpret结果。
     */
    static String interpret(String command, Integer exitCode) {
        if (exitCode == null || exitCode.intValue() == 0 || StrUtil.isBlank(command)) {
            return null;
        }
        String segment = lastCommandSegment(command);
        String baseCommand = baseCommand(segment);
        if (StrUtil.isBlank(baseCommand)) {
            return null;
        }
        if ("git".equalsIgnoreCase(baseCommand)) {
            return interpretGit(segment, exitCode);
        }
        Map<Integer, String> commandSemantics = SEMANTICS.get(baseCommand.toLowerCase(Locale.ROOT));
        if (commandSemantics == null) {
            return null;
        }
        return commandSemantics.get(exitCode);
    }

    /**
     * 构建当前策略配置摘要。
     *
     * @return 返回策略Summary结果。
     */
    static Map<String, Object> policySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("knownCommandCount", Integer.valueOf(SEMANTICS.size()));
        summary.put("grepNoMatchExitOneInformational", Boolean.TRUE);
        summary.put("diffExitOneInformational", Boolean.TRUE);
        summary.put("gitDiffExitOneInformational", Boolean.TRUE);
        summary.put("curlNetworkErrorsExplained", Boolean.TRUE);
        summary.put("testExitOneInformational", Boolean.TRUE);
        summary.put("findExitOnePartialResult", Boolean.TRUE);
        summary.put(
                "commandSamples",
                java.util.Arrays.asList("grep", "rg", "diff", "git diff", "curl", "test", "find"));
        summary.put(
                "exitCodeSamples",
                java.util.Arrays.asList("grep:1", "diff:1", "git diff:1", "curl:6/7/22/28"));
        return summary;
    }

    /**
     * 执行interpretGit相关逻辑。
     *
     * @param segment segment 参数。
     * @param exitCode 命令退出码。
     * @return 返回interpret Git结果。
     */
    private static String interpretGit(String segment, Integer exitCode) {
        if (exitCode == null || exitCode.intValue() != 1) {
            return null;
        }
        String normalized = normalizeCommandTokens(segment).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("git diff ")
                || "git diff".equals(normalized)
                || normalized.startsWith("git diff-index ")
                || normalized.startsWith("git diff-files ")
                || normalized.startsWith("git diff-tree ")) {
            Map<Integer, String> commandSemantics = SEMANTICS.get("git diff");
            return commandSemantics == null ? null : commandSemantics.get(exitCode);
        }
        return null;
    }

    /**
     * 执行register相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @param exitCode 命令退出码。
     * @param meaning meaning 参数。
     */
    private static void register(String command, int exitCode, String meaning) {
        Map<Integer, String> commandSemantics = SEMANTICS.get(command);
        if (commandSemantics == null) {
            commandSemantics = new LinkedHashMap<Integer, String>();
            SEMANTICS.put(command, commandSemantics);
        }
        commandSemantics.put(Integer.valueOf(exitCode), meaning);
    }

    /**
     * 执行last命令Segment相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回last命令Segment结果。
     */
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

    /**
     * 执行skipQuoted相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param start start 参数。
     * @return 返回skip Quoted结果。
     */
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

    /**
     * 执行基础命令相关逻辑。
     *
     * @param segment segment 参数。
     * @return 返回base命令结果。
     */
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

    /**
     * 规范化命令token。
     *
     * @param segment segment 参数。
     * @return 返回命令token结果。
     */
    private static String normalizeCommandTokens(String segment) {
        StringBuilder builder = new StringBuilder();
        int i = 0;
        boolean seenCommand = false;
        while (i < segment.length()) {
            while (i < segment.length() && Character.isWhitespace(segment.charAt(i))) {
                i++;
            }
            if (i >= segment.length()) {
                break;
            }
            int end = tokenEnd(segment, i);
            String token = stripPath(stripQuotes(segment.substring(i, end)));
            if (!seenCommand && looksLikeEnvAssignment(token)) {
                i = end;
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(token);
            seenCommand = true;
            i = end;
        }
        return builder.toString().trim();
    }

    /**
     * 执行tokenEnd相关逻辑。
     *
     * @param segment segment 参数。
     * @param start start 参数。
     * @return 返回token End结果。
     */
    private static int tokenEnd(String segment, int start) {
        int i = start;
        while (i < segment.length() && !Character.isWhitespace(segment.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * 判断是否具有环境变量Assignment特征。
     *
     * @param token token 参数。
     * @return 返回looks Like Env Assignment结果。
     */
    private static boolean looksLikeEnvAssignment(String token) {
        if (StrUtil.isBlank(token) || token.startsWith("-")) {
            return false;
        }
        int equals = token.indexOf('=');
        return equals > 0;
    }

    /**
     * 剥离路径。
     *
     * @param token token 参数。
     * @return 返回strip路径。
     */
    private static String stripPath(String token) {
        String normalized = token.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    /**
     * 剥离Quotes。
     *
     * @param token token 参数。
     * @return 返回strip Quotes结果。
     */
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
