package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.HermesShellSkill;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class HermesShellSkillTest {
    @Test
    void shouldNotRewriteSudoMentionInArgumentsOrStrings() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("secret");
        HermesShellSkill skill =
                new HermesShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(skill.transformSudoCommand("grep -n sudo README.md").isChanged()).isFalse();
        assertThat(skill.transformSudoCommand("printf '%s\\n' sudo").isChanged()).isFalse();
        assertThat(skill.transformSudoCommand("# sudo apt update").isChanged()).isFalse();
        assertThat(skill.transformSudoCommand("echo sudo && grep sudo README.md").isChanged())
                .isFalse();
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
    void shouldRewriteOnlyRealCompoundSudoInvocationsLikeHermes() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("testpass");
        HermesShellSkill skill =
                new HermesShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        HermesShellSkill.SudoTransform transform =
                skill.transformSudoCommand(
                        "echo sudo && sudo apt update\n# sudo ignored\n( sudo whoami )");

        assertThat(transform.isChanged()).isTrue();
        assertThat(transform.getCommand())
                .isEqualTo(
                        "echo sudo && sudo -S -p '' apt update\n"
                                + "# sudo ignored\n"
                                + "( sudo -S -p '' whoami )");
    }

    @Test
    void shouldNotRewriteAlreadyStdinEnabledSudo() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("testpass");
        HermesShellSkill skill =
                new HermesShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        HermesShellSkill.SudoTransform transform =
                skill.transformSudoCommand("sudo -S -p '' whoami");

        assertThat(transform.isChanged()).isFalse();
        assertThat(transform.getCommand()).isEqualTo("sudo -S -p '' whoami");
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

    @Test
    void shouldRejectForegroundTimeoutAboveHermesCap() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setMaxForegroundTimeoutSeconds(1);
        HermesShellSkill skill =
                new HermesShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String result = skill.execute("echo should_not_run", 2000);

        assertThat(result)
                .contains("Foreground timeout 2000ms exceeds the maximum of 1000ms")
                .contains("background=true");
    }

    @Test
    void shouldApplyHermesCompoundBackgroundRewriteBeforeForegroundExecution() throws Exception {
        AppConfig config = new AppConfig();
        HermesShellSkill skill =
                new HermesShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(skill.rewriteCompoundBackground("echo ready && python -m http.server 8000 &"))
                .isEqualTo("echo ready && { python -m http.server 8000 & }");
        assertThat(skill.rewriteCompoundBackground("echo ready && { python -m http.server 8000 & }"))
                .isEqualTo("echo ready && { python -m http.server 8000 & }");
    }

    @Test
    void shouldStartHermesTerminalBackgroundProcessInRegistry() throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        HermesShellSkill skill = new HermesShellSkill(workdir, config, null, registry);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                javaSleepCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(1),
                                workdir,
                                Boolean.TRUE));

        String sessionId = result.get("session_id").getString();
        assertThat(result.get("success").getBoolean()).isTrue();
        assertThat(result.get("status").getString()).isEqualTo("running");
        assertThat(result.get("background").getBoolean()).isTrue();
        assertThat(result.get("notify_on_complete").getBoolean()).isTrue();
        assertThat(sessionId).startsWith("proc_");
        assertThat(registry.get(sessionId)).isNotNull();

        assertThat(registry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldRejectForegroundBackgroundWrappersOnDirectShellExecution() throws Exception {
        AppConfig config = new AppConfig();
        HermesShellSkill skill =
                new HermesShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThatThrownBy(() -> skill.execute("nohup npm run dev > app.log 2>&1", 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nohup")
                .hasMessageContaining("受管的后台进程能力");
    }

    @Test
    void shouldTimeoutSudoRewriteBranchWithoutWaitingForOutputEof() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("testpass");
        HermesShellSkill skill =
                new HermesShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String result = skill.execute("sudo ping 127.0.0.1", 10);

        assertThat(result).contains("执行超时");
    }

    @Test
    void shouldApplyHermesForegroundOutputByteLimit() throws Exception {
        AppConfig config = new AppConfig();
        config.getTask().setToolOutputInlineLimit(300);
        HermesShellSkill skill =
                new HermesShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String result = skill.execute(javaLongOutputCommand(), 10000);

        assertThat(result)
                .contains("head-")
                .contains("tail-")
                .contains("OUTPUT TRUNCATED")
                .contains("chars omitted");
        assertThat(result).doesNotContain("middle-");
        assertThat(result.length()).isLessThan(500);
    }

    private String javaSleepCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "ping -n 30 127.0.0.1 > nul";
        }
        return "sleep 30";
    }

    private String javaLongOutputCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "powershell -NoProfile -Command \"$s='head-' + ('A' * 600) + 'middle-' + ('B' * 600) + 'tail-'; [Console]::Write($s)\"";
        }
        return "printf 'head-'; head -c 600 /dev/zero | tr '\\0' 'A'; printf 'middle-'; head -c 600 /dev/zero | tr '\\0' 'B'; printf 'tail-'";
    }
}
