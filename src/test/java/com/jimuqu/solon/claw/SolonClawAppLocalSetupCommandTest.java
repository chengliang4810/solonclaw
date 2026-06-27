package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 验证容器启动前的本地 setup/model 命令遵循命令行指定的工作区。 */
class SolonClawAppLocalSetupCommandTest {
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
}
