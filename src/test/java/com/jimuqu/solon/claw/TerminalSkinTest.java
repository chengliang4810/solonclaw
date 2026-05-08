package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalSkin;
import org.junit.jupiter.api.Test;

public class TerminalSkinTest {
    @Test
    void shouldResolveClassicSkinByDefault() {
        TerminalSkin skin = TerminalSkin.resolve("");

        assertThat(skin.name()).isEqualTo("classic");
        assertThat(skin.accent("你")).contains("\u001B[36m").contains("你");
        assertThat(skin.border()).contains("─");
    }

    @Test
    void shouldResolveMonoSkinWithoutAnsi() {
        TerminalSkin skin = TerminalSkin.resolve("mono");

        assertThat(skin.name()).isEqualTo("mono");
        assertThat(skin.accent("你")).isEqualTo("你");
        assertThat(skin.dim("提示")).isEqualTo("提示");
        assertThat(skin.border()).contains("---");
    }

    @Test
    void shouldRenderSkinHelp() {
        assertThat(TerminalSkin.resolve("contrast").renderHelp())
                .contains("当前皮肤：contrast")
                .contains("classic、mono、contrast")
                .contains("/skin <名称>");
        assertThat(TerminalSkin.isSkinCommand("/skin mono")).isTrue();
    }
}
