package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.skills.sys.ShellSkill;

/** Solon AI ShellSkill wrapper with Hermes-style terminal safeguards. */
public class HermesShellSkill extends ShellSkill {
    private static final Pattern SUDO_COMMAND_PATTERN =
            Pattern.compile(
                    "(^|(?:&&|\\|\\||;|\\n)\\s*|(?:^|\\s)(?:[A-Za-z_][A-Za-z0-9_]*=[^\\s]+\\s+)+)(sudo)(\\s+)(?!-[A-Za-z]*S\\b)(.+)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

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
        super(workDir);
        this.appConfig = appConfig;
        this.securityPolicyService = securityPolicyService;
        this.shellCmd = shellCmd;
        this.extension = extension;
    }

    @Override
    @ToolMapping(name = "execute_shell", description = "在本地系统中执行单行指令或多行脚本，并获取标准输出。")
    public String execute(
            @Param("code") String code,
            @Param(name = "timeout", required = false, defaultValue = "120000", description = "可选超时时间，单位为毫秒")
                    Integer timeout) {
        HermesCodeExecutionSkills.assertSafe(
                com.jimuqu.solon.claw.support.constants.ToolNameConstants.EXECUTE_SHELL,
                code,
                securityPolicyService);
        SudoTransform transform = transformSudoCommand(code);
        if (!transform.isChanged()) {
            return super.execute(code, timeout);
        }
        return executeWithStdin(transform.getCommand(), transform.getStdin(), timeout);
    }

    public SudoTransform transformSudoCommand(String command) {
        String raw = StrUtil.nullToEmpty(command);
        String password = resolveSudoPassword();
        if (password == null) {
            return SudoTransform.unchanged(raw);
        }
        Matcher matcher = SUDO_COMMAND_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return SudoTransform.unchanged(raw);
        }
        StringBuffer buffer = new StringBuffer();
        do {
            String replacement =
                    matcher.group(1)
                            + matcher.group(2)
                            + " -S -p ''"
                            + matcher.group(3)
                            + matcher.group(4);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        } while (matcher.find());
        matcher.appendTail(buffer);
        return new SudoTransform(buffer.toString(), password + "\n", true);
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
            if (stdin != null) {
                OutputStreamWriter writer =
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                writer.write(stdin);
                writer.flush();
                writer.close();
            }
            String output = readOutput(process);
            int timeout = timeoutMs == null || timeoutMs < 0 ? 120000 : timeoutMs;
            boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "执行超时：运行时间超过 " + timeout + " 毫秒。";
            }
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
}
