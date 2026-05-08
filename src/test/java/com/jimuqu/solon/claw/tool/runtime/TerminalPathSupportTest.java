package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalPathSupportTest {
    @Test
    void shouldConvertGitBashCwdToNativeWindowsPathForProcessBuilderLikeJimuqu() {
        assertThat(TerminalPathSupport.toProcessCwd("/c/Users/alice/project", true))
                .isEqualTo("C:\\Users\\alice\\project");
        assertThat(TerminalPathSupport.toProcessCwd("/d", true)).isEqualTo("D:\\");
        assertThat(TerminalPathSupport.toProcessCwd("/not-drive/project", true))
                .isEqualTo("/not-drive/project");
        assertThat(TerminalPathSupport.toProcessCwd("/c/Users/alice/project", false))
                .isEqualTo("/c/Users/alice/project");
    }
}
