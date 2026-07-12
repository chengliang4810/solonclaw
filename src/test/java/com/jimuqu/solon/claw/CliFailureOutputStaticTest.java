package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** 一次性 CLI 顶层失败不得向用户暴露 Java 堆栈。 */
class CliFailureOutputStaticTest {
    /** 启动入口必须复用统一脱敏摘要并禁止直接打印堆栈。 */
    @Test
    void consoleFailureUsesSafeSummary() throws Exception {
        String source =
                new String(
                        Files.readAllBytes(
                                Paths.get("src/main/java/com/jimuqu/solon/claw/SolonClawApp.java")),
                        StandardCharsets.UTF_8);
        assertThat(source).contains("ErrorTextSupport.safeError(e)");
        assertThat(source).doesNotContain("printStackTrace(System.err)");
        assertThat(source.indexOf("try {\n            if (runLocalSetupCommand(cliMode))"))
                .isLessThan(
                        source.indexOf("Solon.context().getBean(CliRunner.class).run(cliMode)"));
    }
}
