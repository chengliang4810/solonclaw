package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 验证容器启动前的本地 setup/model 命令遵循命令行指定的工作区。 */
class SolonClawAppLocalSetupCommandTest {
    @Test
    void consoleModeUsesUtf8PrintStreams() throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        CapturingOutputStream out = new CapturingOutputStream();
        CapturingOutputStream err = new CapturingOutputStream();
        try {
            System.setOut(new PrintStream(out, true, "US-ASCII"));
            System.setErr(new PrintStream(err, true, "US-ASCII"));

            SolonClawApp.configureConsoleLogging(
                    new CliMode(CliMode.Kind.CLI, "/help", "console-utf8-test"));

            System.out.print("中文");
            System.err.print("错误");

            assertThat(out.text()).isEqualTo("中文");
            assertThat(err.text()).isEqualTo("错误");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    void localModelSetUsesSystemWorkspaceProperty() throws Exception {
        Path workspaceHome = Files.createTempDirectory("solonclaw-local-setup-workspace");
        String previousWorkspace = System.getProperty("solonclaw.workspace");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setProperty("solonclaw.workspace", workspaceHome.toString());
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8.name()));

            boolean handled =
                    SolonClawApp.runLocalSetupCommand(
                            new CliMode(
                                    CliMode.Kind.CLI,
                                    "model set --provider audit-openai --base-url https://api.example.com/v1 "
                                            + "--api-key Sk-Test-LocalSetupSecret123 --model audit-model --dialect openai",
                                    "local-setup-test"));

            assertThat(handled).isTrue();
            assertThat(out.toString(StandardCharsets.UTF_8.name()))
                    .contains("provider=audit-openai")
                    .contains("model=audit-model")
                    .doesNotContain("Sk-Test-LocalSetupSecret123");
            assertThat(Files.readString(workspaceHome.resolve("config.yml")))
                    .contains("audit-openai:")
                    .contains("apiKey: Sk-Test-LocalSetupSecret123")
                    .contains("default: audit-model");
        } finally {
            if (previousWorkspace == null) {
                System.clearProperty("solonclaw.workspace");
            } else {
                System.setProperty("solonclaw.workspace", previousWorkspace);
            }
            System.setOut(originalOut);
        }
    }

    @Test
    void localAuthAddUsesSystemWorkspaceProperty() throws Exception {
        Path workspaceHome = Files.createTempDirectory("solonclaw-local-auth-workspace");
        String previousWorkspace = System.getProperty("solonclaw.workspace");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setProperty("solonclaw.workspace", workspaceHome.toString());
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8.name()));

            boolean handled =
                    SolonClawApp.runLocalSetupCommand(
                            new CliMode(
                                    CliMode.Kind.CLI,
                                    "auth add audit-auth --base-url https://api.example.com/v1 "
                                            + "--api-key Sk-Test-LocalAuthSecret123 --model audit-model --dialect openai",
                                    "local-auth-test"));

            assertThat(handled).isTrue();
            assertThat(out.toString(StandardCharsets.UTF_8.name()))
                    .contains("provider=audit-auth")
                    .contains("model=audit-model")
                    .doesNotContain("Sk-Test-LocalAuthSecret123");
            assertThat(Files.readString(workspaceHome.resolve("config.yml")))
                    .contains("audit-auth:")
                    .contains("apiKey: Sk-Test-LocalAuthSecret123")
                    .contains("defaultModel: audit-model");
        } finally {
            if (previousWorkspace == null) {
                System.clearProperty("solonclaw.workspace");
            } else {
                System.setProperty("solonclaw.workspace", previousWorkspace);
            }
            System.setOut(originalOut);
        }
    }

    /** 捕获字节并按 UTF-8 解码，模拟真实终端期待的输出编码。 */
    private static class CapturingOutputStream extends OutputStream {
        /** 保存写入的原始字节，便于验证 PrintStream 实际编码。 */
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        @Override
        public void write(int value) {
            delegate.write(value);
        }

        /** 返回按 UTF-8 解码后的输出内容。 */
        private String text() {
            return new String(delegate.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
