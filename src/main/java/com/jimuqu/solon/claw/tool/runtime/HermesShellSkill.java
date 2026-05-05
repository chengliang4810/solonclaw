package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.skills.sys.ShellSkill;

/** Solon AI ShellSkill wrapper with Hermes-style terminal safeguards. */
public class HermesShellSkill extends ShellSkill {
    private final AppConfig appConfig;
    private final SecurityPolicyService securityPolicyService;
    private final String shellCmd;
    private final String extension;

    public HermesShellSkill(String workDir, AppConfig appConfig) {
        this(workDir, defaultShellCmd(), defaultExtension(), appConfig, null);
    }

    public HermesShellSkill(
            String workDir, AppConfig appConfig, SecurityPolicyService securityPolicyService) {
        this(workDir, defaultShellCmd(), defaultExtension(), appConfig, securityPolicyService);
    }

    public HermesShellSkill(String workDir, String shellCmd, String extension, AppConfig appConfig) {
        this(workDir, shellCmd, extension, appConfig, null);
    }

    public HermesShellSkill(
            String workDir,
            String shellCmd,
            String extension,
            AppConfig appConfig,
            SecurityPolicyService securityPolicyService) {
        super(checkedWorkDir(workDir));
        this.appConfig = appConfig;
        this.securityPolicyService = securityPolicyService;
        this.shellCmd = shellCmd;
        this.extension = extension;
    }

    @Override
    @ToolMapping(name = "execute_shell", description = "在本地系统中执行单行指令或多行脚本，并获取标准输出。")
    public String execute(
            @Param("code") String code,
            @Param(name = "timeout", required = false, defaultValue = "180000", description = "可选超时时间，单位为毫秒")
                    Integer timeout) {
        HermesCodeExecutionSkills.assertSafe(
                com.jimuqu.solon.claw.support.constants.ToolNameConstants.EXECUTE_SHELL,
                code,
                securityPolicyService);
        Integer effectiveTimeout = normalizeForegroundTimeout(timeout);
        if (effectiveTimeout == null) {
            return foregroundTimeoutExceededMessage(timeout);
        }
        SudoTransform transform = transformSudoCommand(code);
        if (!transform.isChanged()) {
            return super.execute(code, effectiveTimeout);
        }
        return executeWithStdin(transform.getCommand(), transform.getStdin(), effectiveTimeout);
    }

    public SudoTransform transformSudoCommand(String command) {
        String raw = StrUtil.nullToEmpty(command);
        String password = resolveSudoPassword();
        if (password == null) {
            return SudoTransform.unchanged(raw);
        }
        SudoRewrite rewrite = rewriteRealSudoInvocations(raw);
        if (!rewrite.isChanged()) {
            return SudoTransform.unchanged(raw);
        }
        return new SudoTransform(rewrite.getCommand(), password + "\n", true);
    }

    private SudoRewrite rewriteRealSudoInvocations(String command) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = command.length();
        boolean commandStart = true;
        boolean found = false;
        while (i < n) {
            char ch = command.charAt(i);
            if (Character.isWhitespace(ch)) {
                out.append(ch);
                if (ch == '\n') {
                    commandStart = true;
                }
                i++;
                continue;
            }
            if (ch == '#' && commandStart) {
                int commentEnd = command.indexOf('\n', i);
                if (commentEnd < 0) {
                    out.append(command.substring(i));
                    break;
                }
                out.append(command, i, commentEnd);
                i = commentEnd;
                continue;
            }
            if (startsWithAny(command, i, "&&", "||", ";;")) {
                out.append(command, i, i + 2);
                i += 2;
                commandStart = true;
                continue;
            }
            if (ch == ';' || ch == '|' || ch == '&' || ch == '(') {
                out.append(ch);
                i++;
                commandStart = true;
                continue;
            }
            if (ch == ')') {
                out.append(ch);
                i++;
                commandStart = false;
                continue;
            }

            Token token = readShellToken(command, i);
            if (commandStart && "sudo".equals(token.getValue())) {
                if (hasSudoStdinFlag(command, token.getEnd())) {
                    out.append(token.getValue());
                } else {
                    out.append("sudo -S -p ''");
                    found = true;
                }
            } else {
                out.append(token.getValue());
            }

            if (commandStart && looksLikeEnvAssignment(token.getValue())) {
                commandStart = true;
            } else {
                commandStart = false;
            }
            i = token.getEnd();
        }
        return new SudoRewrite(out.toString(), found);
    }

    private Token readShellToken(String command, int start) {
        int i = start;
        int n = command.length();
        while (i < n) {
            char ch = command.charAt(i);
            if (Character.isWhitespace(ch) || ch == ';' || ch == '|' || ch == '&' || ch == '(' || ch == ')') {
                break;
            }
            if (ch == '\'') {
                i++;
                while (i < n && command.charAt(i) != '\'') {
                    i++;
                }
                if (i < n) {
                    i++;
                }
                continue;
            }
            if (ch == '"') {
                i++;
                while (i < n) {
                    char inner = command.charAt(i);
                    if (inner == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (inner == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (ch == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            i++;
        }
        return new Token(command.substring(start, i), i);
    }

    private boolean hasSudoStdinFlag(String command, int index) {
        int i = index;
        int n = command.length();
        while (i < n && Character.isWhitespace(command.charAt(i)) && command.charAt(i) != '\n') {
            i++;
        }
        if (i >= n || command.charAt(i) != '-') {
            return false;
        }
        Token option = readShellToken(command, i);
        String value = option.getValue();
        return value.indexOf('S') >= 0;
    }

    private boolean startsWithAny(String value, int index, String first, String second, String third) {
        return value.startsWith(first, index)
                || value.startsWith(second, index)
                || value.startsWith(third, index);
    }

    private boolean looksLikeEnvAssignment(String token) {
        if (StrUtil.isBlank(token) || token.startsWith("=")) {
            return false;
        }
        int equals = token.indexOf('=');
        if (equals <= 0) {
            return false;
        }
        String name = token.substring(0, equals);
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (i == 0) {
                if (!(Character.isLetter(ch) || ch == '_')) {
                    return false;
                }
            } else if (!(Character.isLetterOrDigit(ch) || ch == '_')) {
                return false;
            }
        }
        return true;
    }

    private String resolveSudoPassword() {
        String envValue = System.getenv("SUDO_PASSWORD");
        if (envValue != null) {
            return envValue;
        }
        if (appConfig != null
                && appConfig.getTerminal() != null
                && appConfig.getTerminal().getSudoPassword() != null) {
            return appConfig.getTerminal().getSudoPassword();
        }
        return null;
    }

    private String executeWithStdin(String code, String stdin, Integer timeoutMs) {
        Path tempScript = null;
        try {
            tempScript = Files.createTempFile(workPath, "_script_", extension);
            Files.write(tempScript, StrUtil.nullToEmpty(code).getBytes(StandardCharsets.UTF_8));
            java.util.List<String> command =
                    new java.util.ArrayList<String>(
                            java.util.Arrays.asList(shellCmd.split("\\s+")));
            command.add(tempScript.toAbsolutePath().toString());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workPath.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            CompletableFuture<String> outputFuture =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return readOutput(process);
                                } catch (Exception e) {
                                    return "系统失败: " + e.getMessage();
                                }
                            });
            if (stdin != null) {
                OutputStreamWriter writer =
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                writer.write(stdin);
                writer.flush();
                writer.close();
            }
            int timeout = timeoutMs == null || timeoutMs < 0 ? 120000 : timeoutMs;
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "执行超时：运行时间超过 " + timeout + " 毫秒。";
            }
            String output = outputFuture.get(1, TimeUnit.SECONDS);
            String result = StrUtil.nullToEmpty(output).trim();
            return result.length() == 0 ? "执行成功" : result;
        } catch (Exception e) {
            return "系统失败: " + e.getMessage();
        } finally {
            if (tempScript != null) {
                try {
                    Files.deleteIfExists(tempScript);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Integer normalizeForegroundTimeout(Integer timeoutMs) {
        if (timeoutMs == null || timeoutMs < 0) {
            return 180000;
        }
        int maxSeconds = 600;
        if (appConfig != null && appConfig.getTerminal() != null) {
            maxSeconds = appConfig.getTerminal().getMaxForegroundTimeoutSeconds();
        }
        int maxMs = Math.max(1, maxSeconds) * 1000;
        if (timeoutMs > maxMs) {
            return null;
        }
        return timeoutMs;
    }

    private String foregroundTimeoutExceededMessage(Integer timeoutMs) {
        int maxSeconds = 600;
        if (appConfig != null && appConfig.getTerminal() != null) {
            maxSeconds = appConfig.getTerminal().getMaxForegroundTimeoutSeconds();
        }
        return "Foreground timeout "
                + timeoutMs
                + "ms exceeds the maximum of "
                + (Math.max(1, maxSeconds) * 1000)
                + "ms. Use background=true with notify_on_complete=true for long-running commands.";
    }

    private String readOutput(Process process) throws Exception {
        InputStreamReader reader =
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
        StringBuilder buffer = new StringBuilder();
        char[] chars = new char[4096];
        int read;
        while ((read = reader.read(chars)) != -1) {
            buffer.append(chars, 0, read);
            if (buffer.length() > 1024 * 1024) {
                process.destroyForcibly();
                buffer.append("\n... [输出已截断]");
                break;
            }
        }
        return buffer.toString();
    }

    private static String defaultShellCmd() {
        return isWindows() ? "cmd /c" : (checkCmd("bash") ? "bash" : "/bin/sh");
    }

    private static String defaultExtension() {
        return isWindows() ? ".bat" : ".sh";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static boolean checkCmd(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd + " --version");
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String checkedWorkDir(String workDir) {
        SecurityPolicyService.FileVerdict verdict = SecurityPolicyService.checkWorkdirText(workDir);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Blocked: " + verdict.getMessage() + ". Use a simple filesystem path without shell metacharacters.");
        }
        return workDir;
    }

    public static class SudoTransform {
        private final String command;
        private final String stdin;
        private final boolean changed;

        private SudoTransform(String command, String stdin, boolean changed) {
            this.command = command;
            this.stdin = stdin;
            this.changed = changed;
        }

        private static SudoTransform unchanged(String command) {
            return new SudoTransform(command, null, false);
        }

        public String getCommand() {
            return command;
        }

        public String getStdin() {
            return stdin;
        }

        public boolean isChanged() {
            return changed;
        }
    }

    private static class SudoRewrite {
        private final String command;
        private final boolean changed;

        private SudoRewrite(String command, boolean changed) {
            this.command = command;
            this.changed = changed;
        }

        public String getCommand() {
            return command;
        }

        public boolean isChanged() {
            return changed;
        }
    }

    private static class Token {
        private final String value;
        private final int end;

        private Token(String value, int end) {
            this.value = value;
            this.end = end;
        }

        public String getValue() {
            return value;
        }

        public int getEnd() {
            return end;
        }
    }
}
