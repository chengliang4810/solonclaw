package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ProcessRegistryTest {
    @Test
    void shouldRejectNullAndBlankBackgroundCommandsBeforeShellLaunch() {
        ProcessRegistry registry = new ProcessRegistry();

        assertThatThrownBy(() -> registry.start(null, new File(".")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected string")
                .hasMessageContaining("null");
        assertThatThrownBy(() -> registry.start("  ", new File(".")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected non-empty string");
    }

    @Test
    void shouldRedactOutputReaderFailureMessage() throws Exception {
        Process process =
                fakeProcess(
                        new InputStream() {
                            @Override
                            public int read() throws IOException {
                                throw new IOException(
                                        "stream failed token=sk-test1234567890abcdef");
                            }
                        });
        ProcessRegistry registry = new ProcessRegistry();
        String id = registry.add(process);
        registry.get(id).startReader();

        Thread.sleep(200L);

        ProcessRegistry.ManagedProcess managed = registry.get(id);
        assertThat(managed.getOutput()).contains("process output reader failed");
        assertThat(managed.getOutput()).contains("token=***");
        assertThat(managed.getOutput()).doesNotContain("sk-test1234567890abcdef");
    }

    @Test
    void shouldRedactManagedProcessDefaultMapViewWithoutMutatingRawOutput() throws Exception {
        Process process =
                fakeProcess(
                        new java.io.ByteArrayInputStream(
                                "api_key=sk-test1234567890abcdef token=secret123\n"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ProcessRegistry registry = new ProcessRegistry();
        String id = registry.add(process);
        ProcessRegistry.ManagedProcess managed = registry.get(id);
        managed.startReader();

        Thread.sleep(200L);

        Map<String, Object> view = managed.toMap();
        assertThat(String.valueOf(view.get("output")))
                .contains("api_key=***")
                .contains("token=***")
                .doesNotContain("sk-test1234567890abcdef")
                .doesNotContain("secret123");
        assertThat(String.valueOf(view.get("output_preview")))
                .contains("api_key=***")
                .contains("token=***")
                .doesNotContain("sk-test1234567890abcdef")
                .doesNotContain("secret123");
        assertThat(managed.getOutput()).contains("sk-test1234567890abcdef").contains("secret123");
    }

    /** 构造只需测试输出读取行为的轻量进程，避免每个用例重复无关的进程方法。 */
    private Process fakeProcess(InputStream stdout) {
        return new Process() {
            @Override
            public OutputStream getOutputStream() {
                return new OutputStream() {
                    @Override
                    public void write(int b) {}
                };
            }

            @Override
            public InputStream getInputStream() {
                return stdout;
            }

            @Override
            public InputStream getErrorStream() {
                return new InputStream() {
                    @Override
                    public int read() {
                        return -1;
                    }
                };
            }

            @Override
            public int waitFor() {
                return 0;
            }

            @Override
            public int exitValue() {
                return 0;
            }

            @Override
            public void destroy() {}
        };
    }

    @Test
    void shouldRedactManagedProcessCwdInMapViewWithoutMutatingRawCwd() throws Exception {
        File workDir =
                Files.createTempDirectory("process-cwd-token=ghp_processcwdmap12345").toFile();
        ProcessRegistry registry = new ProcessRegistry();
        ProcessRegistry.ManagedProcess managed = registry.start("echo cwd-test", workDir);
        managed.waitFor(5000L);

        Map<String, Object> view = managed.toMap();

        assertThat(managed.getCwd()).contains("ghp_processcwdmap12345");
        assertThat(String.valueOf(view.get("cwd")))
                .startsWith("path://")
                .contains("token=***")
                .doesNotContain(workDir.getAbsolutePath())
                .doesNotContain("ghp_processcwdmap12345");
    }

    @Test
    void shouldRewriteCompoundBackgroundTailLikeJimuqu() {
        assertThat(
                        ProcessRegistry.rewriteCompoundBackground(
                                "echo ready && python -m http.server 8000 &"))
                .isEqualTo("echo ready && { python -m http.server 8000 & }");
        assertThat(ProcessRegistry.rewriteCompoundBackground("echo ready || npm run dev &"))
                .isEqualTo("echo ready || { npm run dev & }");
    }

    @Test
    void shouldLeaveSimpleBackgroundAndAlreadyGroupedCommandsAlone() {
        assertThat(ProcessRegistry.rewriteCompoundBackground("npm run dev &"))
                .isEqualTo("npm run dev &");
        assertThat(ProcessRegistry.rewriteCompoundBackground("echo ready && { npm run dev & }"))
                .isEqualTo("echo ready && { npm run dev & }");
    }

    @Test
    void shouldSkipQuotedCommentsParensPipesAndRedirects() {
        assertThat(ProcessRegistry.rewriteCompoundBackground("printf 'a && b &'"))
                .isEqualTo("printf 'a && b &'");
        assertThat(ProcessRegistry.rewriteCompoundBackground("echo ok # npm run dev && wait &"))
                .isEqualTo("echo ok # npm run dev && wait &");
        assertThat(ProcessRegistry.rewriteCompoundBackground("(echo ready && npm run dev &)"))
                .isEqualTo("(echo ready && npm run dev &)");
        assertThat(ProcessRegistry.rewriteCompoundBackground("echo ready | npm run dev &"))
                .isEqualTo("echo ready | npm run dev &");
        assertThat(
                        ProcessRegistry.rewriteCompoundBackground(
                                "echo ready && npm run dev &> app.log"))
                .isEqualTo("echo ready && npm run dev &> app.log");
        assertThat(ProcessRegistry.rewriteCompoundBackground("echo ready && npm run dev 2>&1"))
                .isEqualTo("echo ready && npm run dev 2>&1");
    }

    @Test
    void shouldPrependShellInitFilesForPosixBackgroundCommandsLikeJimuqu() {
        List<String> command =
                ProcessRegistry.shellCommand(
                        "npm run dev", Arrays.asList("/tmp/profile.sh", "/tmp/o'malley.sh"), false);

        assertThat(command).containsExactly("/bin/sh", "-lc", command.get(2));
        assertThat(command.get(2)).startsWith("set +e\n");
        assertThat(command.get(2))
                .contains("[ -r '/tmp/profile.sh' ] && . '/tmp/profile.sh' 2>/dev/null || true");
        assertThat(command.get(2)).contains("o'\\''malley");
        assertThat(command.get(2)).endsWith("npm run dev");
    }

    /** 验证 Windows 后台命令会先切换 UTF-8 代码页，避免中文输出被错误解码。 */
    @Test
    void shouldWrapWindowsBackgroundShellCommandWithUtf8CodePage() {
        List<String> command =
                ProcessRegistry.shellCommand(
                        "npm run dev", Collections.singletonList("/tmp/profile.sh"), true);

        assertThat(command).containsExactly("cmd", "/c", command.get(2));
        assertThat(command.get(2))
                .startsWith(
                        "if exist \"%SystemRoot%\\System32\\chcp.com\" "
                                + "\"%SystemRoot%\\System32\\chcp.com\" 65001 >nul 2>nul & ");
        assertThat(command.get(2)).doesNotContain("chcp 65001 >nul & ");
        assertThat(command.get(2)).endsWith("npm run dev");
    }

    /** 验证受管后台进程的中文输出可以按 UTF-8 被注册表稳定读取。 */
    @Test
    void shouldPreserveChineseOutputFromManagedBackgroundProcess() throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry(config);
        Path home = Files.createTempDirectory("jimuqu-process-encoding");
        String marker = "长期回归 Loop 切片";

        ProcessRegistry.ManagedProcess managed =
                registry.start(chineseOutputCommand(marker), home.toFile());
        assertThat(registry.waitFor(managed.getId(), 10000L)).isTrue();

        assertThat(managed.getOutput()).contains(marker);
        assertNoMojibake(managed.getOutput());
        assertThat(managed.getOutput()).doesNotContain("chcp");
    }

    @Test
    void shouldUseOnlyActiveProfileEnvironmentForManagedProcesses() throws Exception {
        assumeTrue(!isWindows());
        Path home = Files.createTempDirectory("profile-managed-process");
        Path homeA = Files.createDirectories(home.resolve("a"));
        Path homeB = Files.createDirectories(home.resolve("b"));
        Path workA = Files.createDirectories(homeA.resolve("workspace"));
        Path workB = Files.createDirectories(homeB.resolve("workspace"));
        AppConfig config = new AppConfig();
        config.getTerminal().getEnvPassthrough().add("PROFILE_PROCESS_VALUE");
        ProcessRegistry registry = new ProcessRegistry(config);

        ProcessRegistry.ManagedProcess fromA;
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "a",
                        homeA,
                        Collections.singletonMap("PROFILE_PROCESS_VALUE", "profile-a"),
                        null)) {
            fromA =
                    registry.start(
                            "printf '<%s>|<%s>|<%s>|<%s>' "
                                    + "\"${PROFILE_PROCESS_VALUE-unset}\" "
                                    + "\"${SOLONCLAW_PROFILE-unset}\" "
                                    + "\"${SOLONCLAW_HOME-unset}\" "
                                    + "\"$(pwd)\"",
                            workA.toFile());
        }
        assertThat(registry.waitFor(fromA.getId(), 5000L)).isTrue();
        waitForOutput(fromA, "<profile-a>|<a>");
        assertThat(fromA.getOutput())
                .contains("<profile-a>|<a>")
                .contains(homeA.toAbsolutePath().normalize().toString())
                .contains(workA.toAbsolutePath().normalize().toString());

        ProcessRegistry.ManagedProcess fromB;
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "b", homeB, Collections.<String, String>emptyMap(), null)) {
            fromB =
                    registry.start(
                            "printf '<%s>|<%s>|<%s>|<%s>' "
                                    + "\"${PROFILE_PROCESS_VALUE-unset}\" "
                                    + "\"${SOLONCLAW_PROFILE-unset}\" "
                                    + "\"${SOLONCLAW_HOME-unset}\" "
                                    + "\"$(pwd)\"",
                            workB.toFile());
        }
        assertThat(registry.waitFor(fromB.getId(), 5000L)).isTrue();
        waitForOutput(fromB, "<unset>|<b>");
        assertThat(fromB.getOutput())
                .contains("<unset>|<b>")
                .contains(homeB.toAbsolutePath().normalize().toString())
                .contains(workB.toAbsolutePath().normalize().toString())
                .doesNotContain("profile-a");
    }

    /** 验证显式配置的 shell 初始化文件按配置加载，不按凭据文件名实施额外硬阻断。 */
    @Test
    void shouldPrependAllExplicitConfiguredShellInitFilesAtRuntime() throws Exception {
        assumeTrue(
                !System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"));
        Path home = Files.createTempDirectory("jimuqu-shell-init-runtime");
        Path safe = home.resolve("safe.sh");
        Path envFile = home.resolve(".env");
        Path credentials = home.resolve("credentials.json");
        Files.write(safe, Collections.singletonList("export SAFE_INIT=1"));
        Files.write(envFile, Collections.singletonList("TOKEN=secret"));
        Files.write(credentials, Collections.singletonList("{\"token\":\"secret\"}"));
        AppConfig config = new AppConfig();
        config.getTerminal()
                .setShellInitFiles(
                        Arrays.asList(safe.toString(), envFile.toString(), credentials.toString()));
        SolonClawShellSkill skill =
                new SolonClawShellSkill(home.toString(), config, new SecurityPolicyService(config));

        String wrapped = skill.prependShellInit("echo hi");

        assertThat(wrapped).contains(safe.toString());
        assertThat(wrapped).contains(envFile.toString());
        assertThat(wrapped).contains(credentials.toString());
    }

    /** 构建跨平台中文输出命令，用于覆盖后台进程输出读取链路。 */
    private String chineseOutputCommand(String text) {
        if (isWindows()) {
            return "powershell -NoProfile -Command \"Write-Output '" + text + "'\"";
        }
        return "printf '%s\\n' '" + text + "'";
    }

    /** 校验输出中不存在替换字符或典型 UTF-8/GBK 误读片段。 */
    private void assertNoMojibake(String output) {
        assertThat(output).doesNotContain("\uFFFD").doesNotContain("闀").doesNotContain("鍥炲綊");
    }

    /** 等待后台读取线程收齐短命进程输出。 */
    private void waitForOutput(ProcessRegistry.ManagedProcess managed, String expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (!managed.getOutput().contains(expected) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }

    /** 判断当前测试运行环境是否为 Windows。 */
    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
