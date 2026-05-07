package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
    void shouldRewriteCompoundBackgroundTailLikeHermes() {
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
        assertThat(ProcessRegistry.rewriteCompoundBackground("echo ready && npm run dev &> app.log"))
                .isEqualTo("echo ready && npm run dev &> app.log");
        assertThat(ProcessRegistry.rewriteCompoundBackground("echo ready && npm run dev 2>&1"))
                .isEqualTo("echo ready && npm run dev 2>&1");
    }

    @Test
    void shouldPrependShellInitFilesForPosixBackgroundCommandsLikeHermes() {
        List<String> command =
                ProcessRegistry.shellCommand(
                        "npm run dev",
                        Arrays.asList("/tmp/profile.sh", "/tmp/o'malley.sh"),
                        false);

        assertThat(command).containsExactly("/bin/sh", "-lc", command.get(2));
        assertThat(command.get(2)).startsWith("set +e\n");
        assertThat(command.get(2))
                .contains("[ -r '/tmp/profile.sh' ] && . '/tmp/profile.sh' 2>/dev/null || true");
        assertThat(command.get(2)).contains("o'\\''malley");
        assertThat(command.get(2)).endsWith("npm run dev");
    }

    @Test
    void shouldKeepWindowsBackgroundShellCommandUnwrapped() {
        List<String> command =
                ProcessRegistry.shellCommand(
                        "npm run dev",
                        Collections.singletonList("/tmp/profile.sh"),
                        true);

        assertThat(command).containsExactly("cmd", "/c", "npm run dev");
    }
}
