package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalTips;
import org.junit.jupiter.api.Test;

public class TerminalTipsTest {
    @Test
    void shouldRecognizeTipsCommand() {
        assertThat(TerminalTips.isTipsCommand(" /tips ")).isTrue();
        assertThat(TerminalTips.isTipsCommand("/tip")).isFalse();
    }

    @Test
    void shouldRenderTipsAndCurrentTip() {
        assertThat(TerminalTips.current("session-a")).isNotBlank();
        assertThat(TerminalTips.render())
                .contains("终端提示")
                .contains("/queue")
                .contains("/steer")
                .contains("/events")
                .contains("/goal")
                .contains("/recap")
                .contains("/trajectory save")
                .contains("/reload-mcp now")
                .contains("/acp status")
                .contains("/compact")
                .contains("/attachments")
                .contains("/approve")
                .contains("/deny");
    }
}
