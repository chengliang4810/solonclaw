package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 提供危险命令规范化、命令位置识别和有限 shell 混淆还原能力。 */
final class DangerousCommandTextSupport {
    /** shell 续行写法，shell 实际执行前会移除反斜杠和换行。 */
    private static final Pattern SHELL_LINE_CONTINUATION = Pattern.compile("\\\\\\r?\\n");

    /** 默认 IFS 展开会产生空白，需在规则匹配前折叠为空格。 */
    private static final Pattern IFS_EXPANSION = Pattern.compile("\\$\\{IFS\\b[^}]*\\}|\\$IFS\\b");

    /** 简单参数替换表达式，例如 ${0/x/r}。 */
    private static final Pattern PARAMETER_REPLACEMENT =
            Pattern.compile("\\$\\{[^}/\\s]+/[^}/]*/(?<replacement>[^}]*)\\}");

    /** 简单默认值表达式，例如 ${unset:-rm}。 */
    private static final Pattern PARAMETER_DEFAULT =
            Pattern.compile("\\$\\{[^}:}\\s]+:-(?<default>[^}]*)\\}");

    /** 允许在不执行 shell 的前提下解析的简单字面量。 */
    private static final Pattern SIMPLE_SHELL_LITERAL = Pattern.compile("^[A-Za-z0-9_./:@%+=,-]+$");

    /** Python 中可能启动 shell 或子进程的调用入口。 */
    private static final Pattern PYTHON_SHELL_EXEC_CALL =
            Pattern.compile(
                    "\\b(?:os\\.system|subprocess\\.(?:run|Popen|call|check_call|check_output))\\s*\\(",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** JavaScript 中可能启动 shell 或子进程的调用入口。 */
    private static final Pattern JAVASCRIPT_CHILD_PROCESS_CALL =
            Pattern.compile(
                    "\\b(?:child_process\\s*\\.\\s*)?(?:exec|execSync|spawn|spawnSync|execFile|execFileSync)\\s*\\(",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** shell 环境变量赋值词，例如 FOO=1。 */
    private static final Pattern ENV_ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");

    /** 对标实现识别的命令包装器，仅用于继续定位真正的可执行命令词。 */
    private static final Set<String> COMMAND_WRAPPER_WORDS =
            Collections.unmodifiableSet(
                    new LinkedHashSet<String>(
                            Arrays.asList(
                                    "sudo", "doas", "pkexec", "runas", "env", "exec", "nohup",
                                    "setsid", "time", "command", "builtin")));

    /** sudo 中会消费下一个参数的选项，避免把选项值误识别为命令。 */
    private static final Set<String> SUDO_OPTIONS_WITH_ARGUMENT =
            Collections.unmodifiableSet(
                    new LinkedHashSet<String>(
                            Arrays.asList(
                                    "-c",
                                    "--close-from",
                                    "-C",
                                    "-g",
                                    "--group",
                                    "-h",
                                    "--host",
                                    "-p",
                                    "--prompt",
                                    "-r",
                                    "--role",
                                    "-t",
                                    "--type",
                                    "-T",
                                    "--command-timeout",
                                    "-u",
                                    "--user")));

    /** 禁止实例化纯静态文本处理工具。 */
    private DangerousCommandTextSupport() {}

    /**
     * 规范化命令文本，去掉 ANSI、NUL、续行和默认 IFS 混淆。
     *
     * @param code 原始命令文本。
     * @return 返回用于安全检测的基础命令文本。
     */
    static String normalizeCommand(String code) {
        String normalized = StrUtil.nullToEmpty(code).replace("\u0000", "");
        normalized = TerminalAnsiSanitizer.stripAnsi(normalized);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
        normalized = SHELL_LINE_CONTINUATION.matcher(normalized).replaceAll("");
        return normalized.trim();
    }

    /**
     * 构建与外部对标实现一致的检测候选，包括真实命令位置和命令词混淆还原。
     *
     * @param code 原始命令文本。
     * @return 返回去重且保持优先顺序的检测候选。
     */
    static List<String> detectionVariants(String code) {
        String normalized = normalizeCommand(code);
        if (StrUtil.isBlank(normalized)) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> variants = new LinkedHashSet<String>();
        variants.add(normalized);
        String shellExpanded = normalizeShellExpansions(normalized);
        if (!normalized.equals(shellExpanded)) {
            variants.add(shellExpanded);
        }

        List<String> bases = new ArrayList<String>(variants);
        for (String base : bases) {
            String unescaped = stripBackslashEscapes(base);
            if (!base.equals(unescaped)) {
                variants.add(unescaped);
            }
            String marked = markCommandStarts(base);
            if (!base.equals(marked)) {
                variants.add(marked);
            }
            for (CommandWordSpan span : commandWordSpans(base)) {
                String deobfuscated = deobfuscateShellWord(span.word);
                if (StrUtil.isBlank(deobfuscated) || deobfuscated.equals(span.word)) {
                    continue;
                }
                variants.add(
                        base.substring(0, span.start) + deobfuscated + base.substring(span.end));
            }
        }
        return new ArrayList<String>(variants);
    }

    /**
     * 提取 Python 代码中以字符串或字符串数组直接传入的 shell 命令。
     *
     * @param code Python 源码。
     * @return 返回按调用顺序提取的命令；动态表达式不会被静态推断。
     */
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

    /**
     * 提取 JavaScript child_process 调用中以字符串或字符串数组传入的命令。
     *
     * @param code JavaScript 源码。
     * @return 返回按调用顺序提取的命令；动态表达式不会被静态推断。
     */
    static List<String> extractJavaScriptChildProcessCommands(String code) {
        if (StrUtil.isBlank(code)) {
            return Collections.emptyList();
        }
        List<String> commands = new ArrayList<String>();
        Matcher matcher = JAVASCRIPT_CHILD_PROCESS_CALL.matcher(code);
        while (matcher.find()) {
            String firstArgument = readFirstShellCommandArgument(code, matcher.end());
            if (StrUtil.isBlank(firstArgument)) {
                continue;
            }
            String listArguments = readSecondJavaScriptArgumentList(code, matcher.end());
            commands.add(
                    StrUtil.isBlank(listArguments)
                            ? firstArgument
                            : firstArgument + " " + listArguments);
        }
        return commands;
    }

    /**
     * 读取 JavaScript 调用的第二个字符串数组参数并拼接为命令参数。
     *
     * @param code JavaScript 源码。
     * @param offset 左括号之后的偏移量。
     * @return 返回拼接后的参数文本；参数不是静态数组时返回 null。
     */
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

    /** 跳过第一个静态字符串或字符串数组参数。 */
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

    /** 跳过中括号包围的静态数组参数。 */
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

    /** 读取调用的第一个静态字符串或字符串数组命令参数。 */
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

    /** 读取静态字符串数组并拼接为 shell 命令。 */
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
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** 读取单引号或双引号包围的静态字符串内容。 */
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

    /** 跳过单引号或双引号包围的静态字符串。 */
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

    /** 跳过源码中的空白字符。 */
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

    /**
     * 解析命令包装器之后首个真正的可执行程序名称。
     *
     * @param command 受管进程启动命令。
     * @return 返回不含路径的可执行程序名称，无法确定时返回空字符串。
     */
    static String firstExecutableName(String command) {
        String normalized = normalizeCommand(command);
        if (StrUtil.isBlank(normalized)) {
            return "";
        }
        for (CommandWordSpan span : commandWordSpans(normalized)) {
            String word = deobfuscateShellWord(span.word);
            if (ENV_ASSIGNMENT.matcher(word).matches()) {
                continue;
            }
            String executable = executableName(word);
            if (!COMMAND_WRAPPER_WORDS.contains(wrapperKey(executable))) {
                return executable;
            }
        }
        return "";
    }

    /**
     * 折叠 shell 默认 IFS 和空字符串拼接写法，不影响 Python、JavaScript 等代码检测。
     *
     * @param command 已完成通用规范化的 shell 命令。
     * @return 返回 shell 展开后的检测候选。
     */
    private static String normalizeShellExpansions(String command) {
        String expanded = command.replace("''", "").replace("\"\"", "");
        return IFS_EXPANSION.matcher(expanded).replaceAll(" ");
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

    /**
     * 移除普通反斜杠转义，生成 shell 实际命令词可能呈现的候选文本。
     *
     * @param command 已规范化命令。
     * @return 返回移除普通反斜杠转义后的文本。
     */
    private static String stripBackslashEscapes(String command) {
        StringBuilder buffer = new StringBuilder(command.length());
        int index = 0;
        while (index < command.length()) {
            char current = command.charAt(index);
            if (current == '\\' && index + 1 < command.length()) {
                char next = command.charAt(index + 1);
                if (next != '\r' && next != '\n') {
                    buffer.append(next);
                    index += 2;
                    continue;
                }
            }
            buffer.append(current);
            index++;
        }
        return buffer.toString();
    }

    /**
     * 在引号外的真实命令起始位置插入换行，供已有命令位置正则统一识别。
     *
     * @param command 已规范化命令。
     * @return 返回标记命令起始位置后的文本。
     */
    private static String markCommandStarts(String command) {
        List<Integer> offsets = commandStarts(command);
        Collections.sort(offsets, Comparator.reverseOrder());
        String marked = command;
        for (Integer offset : offsets) {
            if (offset == null || offset.intValue() <= 0 || offset.intValue() >= marked.length()) {
                continue;
            }
            int value = offset.intValue();
            marked = marked.substring(0, value) + "\n" + marked.substring(value);
        }
        return marked;
    }

    /**
     * 解析引号外的命令起始位置，避免把参数中的示例文本当成真实命令。
     *
     * @param command 已规范化命令。
     * @return 返回去重后的命令起始偏移。
     */
    private static List<Integer> commandStarts(String command) {
        LinkedHashSet<Integer> starts = new LinkedHashSet<Integer>();
        starts.add(Integer.valueOf(0));
        Character quote = null;
        int index = 0;
        while (index < command.length()) {
            char current = command.charAt(index);
            if (quote != null && quote.charValue() == '\'') {
                if (current == '\'') {
                    quote = null;
                }
                index++;
                continue;
            }
            if (quote != null && quote.charValue() == '"') {
                if (current == '\\' && index + 1 < command.length()) {
                    index += 2;
                    continue;
                }
                if (current == '"') {
                    quote = null;
                    index++;
                    continue;
                }
                if (command.startsWith("$(", index)) {
                    starts.add(Integer.valueOf(index + 2));
                    index += 2;
                    continue;
                }
                index++;
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = Character.valueOf(current);
                index++;
                continue;
            }
            if (current == '\\' && index + 1 < command.length()) {
                index += 2;
                continue;
            }
            if (command.startsWith("$(", index)) {
                starts.add(Integer.valueOf(index + 2));
                index += 2;
                continue;
            }
            if (current == '(' || current == '{' || current == ';') {
                starts.add(Integer.valueOf(index + 1));
                index++;
                continue;
            }
            if (current == '&' || current == '|') {
                if (index + 1 < command.length() && command.charAt(index + 1) == current) {
                    starts.add(Integer.valueOf(index + 2));
                    index += 2;
                } else {
                    starts.add(Integer.valueOf(index + 1));
                    index++;
                }
                continue;
            }
            if (current == '\n') {
                starts.add(Integer.valueOf(index + 1));
            }
            index++;
        }

        LinkedHashSet<Integer> normalizedStarts = new LinkedHashSet<Integer>();
        for (Integer start : starts) {
            int value = skipShellWhitespace(command, start.intValue());
            if (value < command.length()) {
                normalizedStarts.add(Integer.valueOf(value));
            }
        }
        return new ArrayList<Integer>(normalizedStarts);
    }

    /**
     * 解析每个命令位置的包装器和真实命令词，用于限定混淆还原范围。
     *
     * @param command 已规范化命令。
     * @return 返回可能的命令词片段。
     */
    private static List<CommandWordSpan> commandWordSpans(String command) {
        List<CommandWordSpan> spans = new ArrayList<CommandWordSpan>();
        for (Integer start : commandStarts(command)) {
            int position = start.intValue();
            int prefixWords = 0;
            boolean skipWrapperOptions = false;
            boolean skipNextWrapperArgument = false;
            String wrapper = "";
            while (prefixWords < 12) {
                CommandWordSpan span = readShellWord(command, position);
                if (span.start == span.end) {
                    break;
                }
                String deobfuscated = deobfuscateShellWord(span.word);
                String lowerWord = deobfuscated.toLowerCase(Locale.ROOT);
                if (skipNextWrapperArgument) {
                    skipNextWrapperArgument = false;
                    position = span.end;
                    prefixWords++;
                    continue;
                }
                if (skipWrapperOptions && isWrapperOption(wrapper, lowerWord)) {
                    String optionName =
                            lowerWord.contains("=")
                                    ? lowerWord.substring(0, lowerWord.indexOf('='))
                                    : lowerWord;
                    skipNextWrapperArgument =
                            !lowerWord.contains("=")
                                    && wrapperOptionConsumesArgument(wrapper, optionName);
                    position = span.end;
                    prefixWords++;
                    continue;
                }

                spans.add(span);
                prefixWords++;
                String wrapperKey = wrapperKey(executableName(deobfuscated));
                if (COMMAND_WRAPPER_WORDS.contains(wrapperKey)) {
                    wrapper = wrapperKey;
                    skipWrapperOptions = true;
                    position = span.end;
                    continue;
                }
                if (ENV_ASSIGNMENT.matcher(deobfuscated).matches()) {
                    wrapper = "";
                    skipWrapperOptions = false;
                    position = span.end;
                    continue;
                }
                break;
            }
        }
        return spans;
    }

    /** 判断当前词是否为包装器选项，Windows runas 同时接受斜杠选项。 */
    private static boolean isWrapperOption(String wrapper, String word) {
        return word.startsWith("-") || ("runas".equals(wrapper) && word.startsWith("/"));
    }

    /** 判断包装器选项是否会消费下一个 shell 词。 */
    private static boolean wrapperOptionConsumesArgument(String wrapper, String option) {
        if ("env".equals(wrapper)) {
            return "-u".equals(option)
                    || "--unset".equals(option)
                    || "-C".equals(option)
                    || "--chdir".equals(option)
                    || "--argv0".equals(option)
                    || "-S".equals(option)
                    || "--split-string".equals(option);
        }
        if ("exec".equals(wrapper)) {
            return "-a".equals(option);
        }
        if ("time".equals(wrapper)) {
            return "-f".equals(option) || "--format".equals(option) || "-o".equals(option);
        }
        return ("sudo".equals(wrapper) || "doas".equals(wrapper) || "pkexec".equals(wrapper))
                && SUDO_OPTIONS_WITH_ARGUMENT.contains(option);
    }

    /** 提取 shell 词中的可执行程序文件名，同时兼容 Unix 与 Windows 路径分隔符。 */
    private static String executableName(String word) {
        String value = StrUtil.nullToEmpty(word).trim();
        int separator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return separator < 0 ? value : value.substring(separator + 1);
    }

    /** 将包装器名称统一为小写且忽略 Windows 可执行文件后缀。 */
    private static String wrapperKey(String executable) {
        String normalized = StrUtil.nullToEmpty(executable).toLowerCase(Locale.ROOT);
        return normalized.endsWith(".exe")
                ? normalized.substring(0, normalized.length() - 4)
                : normalized;
    }

    /**
     * 跳过 shell 空白字符。
     *
     * @param command 命令文本。
     * @param position 起始偏移。
     * @return 返回首个非空白字符偏移。
     */
    private static int skipShellWhitespace(String command, int position) {
        int index = Math.max(0, position);
        while (index < command.length() && Character.isWhitespace(command.charAt(index))) {
            index++;
        }
        return index;
    }

    /**
     * 跳过引号或转义字符，返回新的索引位置；当前字符不属于引号/转义时返回 -1。
     *
     * @param command 命令文本。
     * @param index 当前偏移。
     * @param quote 当前引号状态（可为 null）。
     * @return 更新后的引号状态和索引，-1 表示未消费。
     */
    private static int skipQuoteOrEscape(String command, int index, Character[] quoteHolder) {
        if (index >= command.length()) {
            return -1;
        }
        char current = command.charAt(index);
        if (quoteHolder[0] != null) {
            if (current == '\\'
                    && quoteHolder[0].charValue() == '"'
                    && index + 1 < command.length()) {
                return index + 2;
            }
            if (current == quoteHolder[0].charValue()) {
                quoteHolder[0] = null;
            }
            return index + 1;
        }
        if (current == '\'' || current == '"') {
            quoteHolder[0] = Character.valueOf(current);
            return index + 1;
        }
        if (current == '\\' && index + 1 < command.length()) {
            return index + 2;
        }
        return -1;
    }

    /**
     * 从指定位置读取一个 shell 词，不执行任何展开。
     *
     * @param command 命令文本。
     * @param position 起始偏移。
     * @return 返回 shell 词片段。
     */
    private static CommandWordSpan readShellWord(String command, int position) {
        int start = skipShellWhitespace(command, position);
        int index = start;
        Character[] quoteHolder = new Character[] {null};
        while (index < command.length()) {
            int skipped = skipQuoteOrEscape(command, index, quoteHolder);
            if (skipped >= 0) {
                index = skipped;
                continue;
            }
            char current = command.charAt(index);
            if (command.startsWith("$(", index)) {
                int end = scanDollarParenEnd(command, index);
                index = end < 0 ? index + 2 : end;
                continue;
            }
            if (command.startsWith("${", index)) {
                int end = command.indexOf('}', index + 2);
                index = end < 0 ? index + 2 : end + 1;
                continue;
            }
            if (current == '`') {
                int end = scanBacktickEnd(command, index);
                index = end < 0 ? index + 1 : end;
                continue;
            }
            if (Character.isWhitespace(current)
                    || current == ';'
                    || current == '&'
                    || current == '|') {
                break;
            }
            index++;
        }
        return new CommandWordSpan(start, index, command.substring(start, index));
    }

    /**
     * 定位平衡的 $(...) 结束位置。
     *
     * @param command 命令文本。
     * @param start "$(`` 起始位置。
     * @return 返回右括号后的偏移，未闭合时返回 -1。
     */
    private static int scanDollarParenEnd(String command, int start) {
        int depth = 1;
        int index = start + 2;
        Character[] quoteHolder = new Character[] {null};
        while (index < command.length()) {
            int skipped = skipQuoteOrEscape(command, index, quoteHolder);
            if (skipped >= 0) {
                index = skipped;
                continue;
            }
            char current = command.charAt(index);
            if (command.startsWith("$(", index)) {
                depth++;
                index += 2;
                continue;
            }
            if (current == ')') {
                depth--;
                index++;
                if (depth == 0) {
                    return index;
                }
                continue;
            }
            index++;
        }
        return -1;
    }

    /**
     * 定位反引号命令替换的结束位置。
     *
     * @param command 命令文本。
     * @param start 起始反引号偏移。
     * @return 返回结束反引号后的偏移，未闭合时返回 -1。
     */
    private static int scanBacktickEnd(String command, int start) {
        int index = start + 1;
        while (index < command.length()) {
            if (command.charAt(index) == '\\' && index + 1 < command.length()) {
                index += 2;
                continue;
            }
            if (command.charAt(index) == '`') {
                return index + 1;
            }
            index++;
        }
        return -1;
    }

    /**
     * 还原命令词中的简单引号、转义、参数替换和字面量命令替换。
     *
     * @param word 原始 shell 词。
     * @return 返回有限还原后的命令词。
     */
    private static String deobfuscateShellWord(String word) {
        String deobfuscated = word;
        for (int attempt = 0; attempt < 2; attempt++) {
            String previous = deobfuscated;
            deobfuscated = replaceSimpleShellExpansions(deobfuscated);
            deobfuscated = stripShellWordSyntax(deobfuscated);
            if (previous.equals(deobfuscated)) {
                break;
            }
        }
        return deobfuscated;
    }

    /**
     * 替换无需执行 shell 即可确定结果的简单展开。
     *
     * @param word 原始 shell 词。
     * @return 返回替换后的文本。
     */
    private static String replaceSimpleShellExpansions(String word) {
        String expanded = replaceSimpleCommandSubstitutions(word);
        expanded = replaceNamedGroup(PARAMETER_REPLACEMENT, expanded, "replacement");
        return replaceNamedGroup(PARAMETER_DEFAULT, expanded, "default");
    }

    /**
     * 替换 echo/printf 产生简单字面量的命令替换。
     *
     * @param word 原始 shell 词。
     * @return 返回替换后的文本。
     */
    private static String replaceSimpleCommandSubstitutions(String word) {
        StringBuilder buffer = new StringBuilder();
        int index = 0;
        while (index < word.length()) {
            if (word.startsWith("$(", index)) {
                int end = scanDollarParenEnd(word, index);
                if (end > 0) {
                    String replacement =
                            literalCommandSubstitutionOutput(word.substring(index + 2, end - 1));
                    if (replacement != null) {
                        buffer.append(replacement);
                        index = end;
                        continue;
                    }
                }
            }
            if (word.charAt(index) == '`') {
                int end = scanBacktickEnd(word, index);
                if (end > 0) {
                    String replacement =
                            literalCommandSubstitutionOutput(word.substring(index + 1, end - 1));
                    if (replacement != null) {
                        buffer.append(replacement);
                        index = end;
                        continue;
                    }
                }
            }
            buffer.append(word.charAt(index));
            index++;
        }
        return buffer.toString();
    }

    /**
     * 解析简单 echo/printf 命令替换的确定性输出。
     *
     * @param script 命令替换内部文本。
     * @return 可静态确定时返回字面量，否则返回 null。
     */
    private static String literalCommandSubstitutionOutput(String script) {
        List<String> words = splitShellWords(script);
        if (words.isEmpty()) {
            return null;
        }
        String command = words.get(0).toLowerCase(Locale.ROOT);
        List<String> args = new ArrayList<String>(words.subList(1, words.size()));
        if ("echo".equals(command)) {
            while (!args.isEmpty() && args.get(0).matches("-[nEe]+")) {
                args.remove(0);
            }
            return args.size() == 1 && SIMPLE_SHELL_LITERAL.matcher(args.get(0)).matches()
                    ? args.get(0)
                    : null;
        }
        if ("printf".equals(command)) {
            if (args.size() == 1 && SIMPLE_SHELL_LITERAL.matcher(args.get(0)).matches()) {
                return args.get(0);
            }
            if (args.size() == 2
                    && "%s".equals(args.get(0))
                    && SIMPLE_SHELL_LITERAL.matcher(args.get(1)).matches()) {
                return args.get(1);
            }
        }
        return null;
    }

    /**
     * 使用现有 shell 词读取器切分简单命令替换文本。
     *
     * @param script 命令替换内部文本。
     * @return 返回去除 shell 引号后的词列表。
     */
    private static List<String> splitShellWords(String script) {
        List<String> words = new ArrayList<String>();
        int position = 0;
        while (position < script.length()) {
            CommandWordSpan span = readShellWord(script, position);
            if (span.start == span.end) {
                break;
            }
            words.add(stripShellWordSyntax(span.word));
            position = span.end;
        }
        return words;
    }

    /**
     * 去除 shell 词中的引号和普通反斜杠转义。
     *
     * @param word 原始 shell 词。
     * @return 返回 shell 语法折叠后的字面文本。
     */
    private static String stripShellWordSyntax(String word) {
        StringBuilder buffer = new StringBuilder(word.length());
        Character quote = null;
        int index = 0;
        while (index < word.length()) {
            char current = word.charAt(index);
            if (quote != null) {
                if (current == '\\' && quote.charValue() == '"' && index + 1 < word.length()) {
                    buffer.append(word.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (current == quote.charValue()) {
                    quote = null;
                    index++;
                    continue;
                }
                buffer.append(current);
                index++;
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = Character.valueOf(current);
                index++;
                continue;
            }
            if (current == '\\' && index + 1 < word.length()) {
                buffer.append(word.charAt(index + 1));
                index += 2;
                continue;
            }
            buffer.append(current);
            index++;
        }
        return buffer.toString();
    }

    /**
     * 用指定命名分组替换正则命中内容。
     *
     * @param pattern 替换正则。
     * @param text 待处理文本。
     * @param groupName 命名分组名称。
     * @return 返回替换后的文本。
     */
    private static String replaceNamedGroup(Pattern pattern, String text, String groupName) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(groupName)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /** 承载一个命令词在原始命令中的起止位置和文本。 */
    private static final class CommandWordSpan {
        /** 命令词起始偏移。 */
        private final int start;

        /** 命令词结束偏移。 */
        private final int end;

        /** 命令词原始文本。 */
        private final String word;

        /**
         * 创建命令词片段。
         *
         * @param start 起始偏移。
         * @param end 结束偏移。
         * @param word 原始文本。
         */
        private CommandWordSpan(int start, int end, String word) {
            this.start = start;
            this.end = end;
            this.word = word;
        }
    }
}
