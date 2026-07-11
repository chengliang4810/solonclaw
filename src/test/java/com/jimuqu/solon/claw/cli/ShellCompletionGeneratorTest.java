package com.jimuqu.solon.claw.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** 验证三个受支持 shell 均公开完整的 Profile 命令和参数。 */
class ShellCompletionGeneratorTest {
    /** Profile 的全部公开选项必须出现在每种补全脚本中。 */
    @Test
    void exposesEveryProfileOptionForEveryShell() throws Exception {
        for (String shell : new String[] {"bash", "zsh", "fish"}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int exitCode =
                    new ShellCompletionGenerator()
                            .write(
                                    shell,
                                    new PrintStream(output, true, StandardCharsets.UTF_8.name()),
                                    System.err);
            String script = output.toString(StandardCharsets.UTF_8.name());

            assertThat(exitCode).isZero();
            assertThat(script)
                    .contains(
                            "list use create describe show rename delete alias export import"
                                    + " install update info")
                    .contains("clone-from")
                    .contains("clone-all")
                    .contains("no-alias")
                    .contains("no-skills")
                    .contains("description")
                    .contains("text")
                    .contains("auto")
                    .contains("overwrite")
                    .contains("all")
                    .contains("remove")
                    .contains("name")
                    .contains("output")
                    .contains("force")
                    .contains("force-config")
                    .contains("help")
                    .contains("yes");
            if (!"fish".equals(shell)) {
                assertThat(script).contains("--help");
            } else {
                assertThat(script).contains("-F -n").contains("import install");
                assertThat(
                                Arrays.stream(script.split("\\R"))
                                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                                        .allMatch(line -> line.startsWith("complete ")))
                        .isTrue();
            }
        }
    }
}
