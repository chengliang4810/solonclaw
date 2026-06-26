package com.jimuqu.solon.claw.tool.runtime;

import static com.jimuqu.solon.claw.tool.runtime.DangerousCommandRuleCatalog.PYTHON_SHELL_EXEC_CALL;

import cn.hutool.core.util.StrUtil;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 提供危险命令文本规范化和代码内 shell 调用提取能力。 */
final class DangerousCommandTextSupport {
    private DangerousCommandTextSupport() {}

    /** 规范化命令文本，去掉 ANSI、NUL 和续行。 */
    static String normalizeCommand(String code) {
        String normalized = StrUtil.nullToEmpty(code).replace("\u0000", "");
        normalized = TerminalAnsiSanitizer.stripAnsi(normalized);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
        normalized = normalized.replaceAll("\\\\\\r?\\n", " ");
        return normalized.trim();
    }

    /** 提取 Python 代码中的 shell 命令调用。 */
    static List<String> extractPythonShellCommands(String code) {
        if (StrUtil.isBlank(code)) {
            return Collections.emptyList();
        }
        List<String> commands = new ArrayList<String>();
        Matcher matcher = PYTHON_SHELL_EXEC_CALL.matcher(code);
        while (matcher.find()) {
            String command = readFirstShellCommandArgument(code, matcher.end());
            if (StrUtil.isNotBlank(command)) {
                commands.add(command);
            }
        }
        return commands;
    }

    /** 提取 JavaScript child_process 调用中的 shell 命令。 */
    static String extractJavaScriptChildProcessCommand(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        Pattern callPattern =
                Pattern.compile(
                        "\\b(?:child_process\\.)?(?:exec|execSync|spawn|spawnSync|execFile|execFileSync)\\s*\\(",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = callPattern.matcher(code);
        if (!matcher.find()) {
            return null;
        }
        String firstArgument = readFirstShellCommandArgument(code, matcher.end());
        if (StrUtil.isBlank(firstArgument)) {
            return null;
        }
        String listArguments = readSecondJavaScriptArgumentList(code, matcher.end());
        if (StrUtil.isBlank(listArguments)) {
            return firstArgument;
        }
        return firstArgument + " " + listArguments;
    }

    /** 判断命令是否只是帮助或版本查询。 */
    static boolean looksLikeHelpOrVersionCommand(String command) {
        String normalized = StrUtil.nullToEmpty(command).toLowerCase(Locale.ROOT).trim();
        while (normalized.contains("  ")) {
            normalized = normalized.replace("  ", " ");
        }
        return normalized.contains(" --help")
                || normalized.endsWith(" -h")
                || normalized.contains(" --version")
                || normalized.endsWith(" -v");
    }

    /** 读取 JavaScript 调用的第二个数组参数并拼接为命令参数。 */
    private static String readSecondJavaScriptArgumentList(String code, int offset) {
        int index = skipWhitespace(code, offset);
        if (index < 0 || index >= code.length()) {
            return null;
        }
        index = skipFirstArgument(code, index);
        index = skipWhitespace(code, index);
        if (index < 0 || index >= code.length() || code.charAt(index) != ',') {
            return null;
        }
        index = skipWhitespace(code, index + 1);
        if (index < 0 || index >= code.length() || code.charAt(index) != '[') {
            return null;
        }
        return readQuotedStringListCommand(code, index + 1);
    }

    /** 跳过第一个字符串或数组参数。 */
    private static int skipFirstArgument(String code, int offset) {
        int index = skipWhitespace(code, offset);
        if (index < 0 || index >= code.length()) {
            return -1;
        }
        char current = code.charAt(index);
        if (current == '\'' || current == '"') {
            return skipQuotedString(code, index);
        }
        if (current == '[') {
            return skipBracketedList(code, index);
        }
        return -1;
    }

    /** 跳过中括号数组参数。 */
    private static int skipBracketedList(String code, int offset) {
        if (code == null || offset < 0 || offset >= code.length() || code.charAt(offset) != '[') {
            return -1;
        }
        int depth = 0;
        for (int i = offset; i < code.length(); i++) {
            char current = code.charAt(i);
            if (current == '\'' || current == '"') {
                i = skipQuotedString(code, i) - 1;
                if (i < 0) {
                    return -1;
                }
                continue;
            }
            if (current == '[') {
                depth++;
            } else if (current == ']') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    /** 读取第一个 shell 命令参数。 */
    private static String readFirstShellCommandArgument(String code, int offset) {
        int index = skipWhitespace(code, offset);
        if (index < 0 || index >= code.length()) {
            return null;
        }
        char current = code.charAt(index);
        if (current == '\'' || current == '"') {
            return readQuotedString(code, index);
        }
        if (current == '[') {
            return readQuotedStringListCommand(code, index + 1);
        }
        return null;
    }

    /** 读取字符串数组形式的命令。 */
    private static String readQuotedStringListCommand(String code, int offset) {
        List<String> parts = new ArrayList<String>();
        int index = offset;
        while (index >= 0 && index < code.length()) {
            index = skipWhitespace(code, index);
            if (index < 0 || index >= code.length() || code.charAt(index) == ']') {
                break;
            }
            char quote = code.charAt(index);
            if (quote != '\'' && quote != '"') {
                return null;
            }
            String value = readQuotedString(code, index);
            if (value == null) {
                return null;
            }
            parts.add(value);
            index = skipQuotedString(code, index);
            index = skipWhitespace(code, index);
            if (index < 0 || index >= code.length() || code.charAt(index) == ']') {
                break;
            }
            if (code.charAt(index) != ',') {
                return null;
            }
            index++;
        }
        if (parts.isEmpty()) {
            return null;
        }
        StringBuilder command = new StringBuilder();
        for (String part : parts) {
            if (command.length() > 0) {
                command.append(' ');
            }
            command.append(part);
        }
        return command.toString();
    }

    /** 读取引号字符串内容。 */
    private static String readQuotedString(String code, int offset) {
        if (code == null || offset < 0 || offset >= code.length()) {
            return null;
        }
        char quote = code.charAt(offset);
        if (quote != '\'' && quote != '"') {
            return null;
        }
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = offset + 1; i < code.length(); i++) {
            char current = code.charAt(i);
            if (escaped) {
                value.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == quote) {
                return value.toString();
            }
            value.append(current);
        }
        return null;
    }

    /** 跳过引号字符串。 */
    private static int skipQuotedString(String code, int offset) {
        if (code == null || offset < 0 || offset >= code.length()) {
            return -1;
        }
        char quote = code.charAt(offset);
        if (quote != '\'' && quote != '"') {
            return -1;
        }
        boolean escaped = false;
        for (int i = offset + 1; i < code.length(); i++) {
            char current = code.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == quote) {
                return i + 1;
            }
        }
        return -1;
    }

    /** 跳过空白字符。 */
    private static int skipWhitespace(String code, int offset) {
        if (code == null || offset < 0 || offset > code.length()) {
            return -1;
        }
        int index = offset;
        while (index < code.length() && Character.isWhitespace(code.charAt(index))) {
            index++;
        }
        return index;
    }
}
