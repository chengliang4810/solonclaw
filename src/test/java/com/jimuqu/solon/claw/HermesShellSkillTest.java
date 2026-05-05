package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.HermesShellSkill;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

public class HermesShellSkillTest {
    @Test
    void shouldNotRewriteSudoMentionInArgumentsOrStrings() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("secret");
        HermesShellSkill skill =
                new HermesShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(skill.transformSudoCommand("grep -n sudo README.md").isChanged()).isFalse();
        assertThat(skill.transformSudoCommand("printf '%s\\n' sudo").isChanged()).isFalse();
    }

    @Test
    void shouldRewriteActualSudoCommandWithConfiguredPassword() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("testpass");
        HermesShellSkill skill =
                new HermesShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        HermesShellSkill.SudoTransform transform =
                skill.transformSudoCommand("sudo apt install -y ripgrep");

        assertThat(transform.isChanged()).isTrue();
        assertThat(transform.getCommand()).isEqualTo("sudo -S -p '' apt install -y ripgrep");
        assertThat(transform.getStdin()).isEqualTo("testpass\n");
    }

    @Test
    void shouldRewriteSudoAfterLeadingEnvAssignment() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("testpass");
        HermesShellSkill skill =
                new HermesShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        HermesShellSkill.SudoTransform transform =
                skill.transformSudoCommand("DEBUG=1 sudo whoami");

        assertThat(transform.isChanged()).isTrue();
        assertThat(transform.getCommand()).isEqualTo("DEBUG=1 sudo -S -p '' whoami");
    }

    @Test
    void shouldNotRewriteWhenPasswordIsUnset() throws Exception {
        AppConfig config = new AppConfig();
        HermesShellSkill skill =
                new HermesShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        HermesShellSkill.SudoTransform transform = skill.transformSudoCommand("sudo true");

        assertThat(transform.isChanged()).isFalse();
    }

    @Test
    void shouldRejectWorkdirWithShellMetacharactersLikeHermesTerminalGuardrail() {
        AppConfig config = new AppConfig();

        assertThatThrownBy(
                        () ->
                                new HermesShellSkill(
                                        "C:\\workspace; rm -rf runtime",
                                        config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Blocked")
                .hasMessageContaining("disallowed character");
    }
}
