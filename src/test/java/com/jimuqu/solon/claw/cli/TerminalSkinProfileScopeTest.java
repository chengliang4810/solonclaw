package com.jimuqu.solon.claw.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** 验证终端皮肤从当前 Profile 环境读取，且未进入作用域时保持进程环境语义。 */
public class TerminalSkinProfileScopeTest {
    @Test
    void shouldReadSkinFromCurrentProfileOnly() throws Exception {
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "a",
                        Files.createTempDirectory("terminal-skin-a"),
                        Collections.singletonMap("SOLONCLAW_TERMINAL_SKIN", "mono"),
                        null)) {
            assertThat(TerminalSkin.fromEnvironment().name()).isEqualTo("mono");
        }

        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "b",
                        Files.createTempDirectory("terminal-skin-b"),
                        Collections.singletonMap("SOLONCLAW_TERMINAL_SKIN", "contrast"),
                        null)) {
            assertThat(TerminalSkin.fromEnvironment().name()).isEqualTo("contrast");
        }

        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "empty",
                        Files.createTempDirectory("terminal-skin-empty"),
                        Collections.<String, String>emptyMap(),
                        null)) {
            assertThat(TerminalSkin.fromEnvironment().name()).isEqualTo("classic");
        }
    }

    @Test
    void shouldKeepUnscopedProcessEnvironmentBehavior() {
        assertThat(TerminalSkin.fromEnvironment().name())
                .isEqualTo(TerminalSkin.resolve(System.getenv("SOLONCLAW_TERMINAL_SKIN")).name());
    }
}
