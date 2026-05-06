package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
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
}
