package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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

    // shouldFilterSensitiveExplicitShellInitFilesWithPolicyLikeJimuqu 已删除：
    // resolveShellInitFiles 经 isSafeConfiguredShellInit -> checkPath(path, false) 读路径过滤，
    // 凭据文件读已放宽（对齐 外部对标仓库"读非安全边界"），.env/credentials.json/id_rsa 不再被过滤。

    @Test
    void shouldNotPrependSensitiveConfiguredShellInitFilesAtRuntimeLikeJimuqu() throws Exception {
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
        assertThat(wrapped).doesNotContain(envFile.toString());
        assertThat(wrapped).doesNotContain(credentials.toString());
    }

    // shouldExpandAndFilterConfiguredShellInitEnvVarsLikeJimuqu 已删除：
    // 同上，resolveShellInitFiles 经读路径过滤；凭据文件读已放宽，id_ed25519 不再被过滤。

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

    /** 判断当前测试运行环境是否为 Windows。 */
    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
