package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SolonClawShellSkill;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class SolonClawShellSkillTest {
    @Test
    void shouldReturnCleanTerminalErrorForNullCommandLikeHermes() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                null,
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                null,
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("success").getBoolean()).isFalse();
        assertThat(result.get("error").getString().toLowerCase(java.util.Locale.ROOT))
                .contains("expected string")
                .contains("nonetype");
    }

    @Test
    void shouldReturnCleanTerminalErrorForBlankCommandLikeHermes() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "  ",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                null,
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("error").getString()).contains("expected non-empty string");
    }

    @Test
    void shouldHandleNullSudoTransformLikeHermes() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("secret");
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        SolonClawShellSkill.SudoTransform transform = skill.transformSudoCommand(null);

        assertThat(transform.isChanged()).isFalse();
        assertThat(transform.getCommand()).isNull();
        assertThat(transform.getStdin()).isNull();
    }

    @Test
    void shouldNotRewriteSudoMentionInArgumentsOrStrings() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("secret");
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

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
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        SolonClawShellSkill.SudoTransform transform =
                skill.transformSudoCommand("sudo apt install -y ripgrep");

        assertThat(transform.isChanged()).isTrue();
        assertThat(transform.getCommand()).isEqualTo("sudo -S -p '' apt install -y ripgrep");
        assertThat(transform.getStdin()).isEqualTo("testpass\n");
    }

    @Test
    void shouldRewriteSudoAfterLeadingEnvAssignment() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("testpass");
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        SolonClawShellSkill.SudoTransform transform =
                skill.transformSudoCommand("DEBUG=1 sudo whoami");

        assertThat(transform.isChanged()).isTrue();
        assertThat(transform.getCommand()).isEqualTo("DEBUG=1 sudo -S -p '' whoami");
    }

    @Test
    void shouldRewriteOnlyRealCompoundSudoInvocationsLikeHermes() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("testpass");
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        SolonClawShellSkill.SudoTransform transform =
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
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        SolonClawShellSkill.SudoTransform transform =
                skill.transformSudoCommand("sudo -S -p '' whoami");

        assertThat(transform.isChanged()).isFalse();
        assertThat(transform.getCommand()).isEqualTo("sudo -S -p '' whoami");
    }

    @Test
    void shouldNotRewriteWhenPasswordIsUnset() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        SolonClawShellSkill.SudoTransform transform = skill.transformSudoCommand("sudo true");

        assertThat(transform.isChanged()).isFalse();
    }

    @Test
    void shouldRejectWorkdirWithShellMetacharactersLikeHermesTerminalGuardrail() {
        AppConfig config = new AppConfig();

        assertThatThrownBy(
                        () ->
                                new SolonClawShellSkill(
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
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String result = skill.execute("echo should_not_run", 2000);

        assertThat(result)
                .contains("Foreground timeout 2000ms exceeds the maximum of 1000ms")
                .contains("background=true");
    }

    @Test
    void shouldApplyHermesCompoundBackgroundRewriteBeforeForegroundExecution() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(skill.rewriteCompoundBackground("echo ready && python -m http.server 8000 &"))
                .isEqualTo("echo ready && { python -m http.server 8000 & }");
        assertThat(skill.rewriteCompoundBackground("echo ready && { python -m http.server 8000 & }"))
                .isEqualTo("echo ready && { python -m http.server 8000 & }");
    }

    @Test
    void shouldInterpretHermesTerminalExitCodeSemantics() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(skill.interpretExitCode("grep 'pattern' file.txt", Integer.valueOf(1)))
                .isEqualTo("No matches found (not an error)");
        assertThat(skill.interpretExitCode("rg 'foo' .", Integer.valueOf(2))).isNull();
        assertThat(skill.interpretExitCode("cat file; diff a b", Integer.valueOf(1)))
                .isEqualTo("Files differ (expected, not an error)");
        assertThat(skill.interpretExitCode("false || /usr/bin/grep foo bar", Integer.valueOf(1)))
                .isEqualTo("No matches found (not an error)");
        assertThat(skill.interpretExitCode("LANG=C test -f /nonexistent", Integer.valueOf(1)))
                .isEqualTo("Condition evaluated to false (expected, not an error)");
        assertThat(skill.interpretExitCode("[ -f /nonexistent ]", Integer.valueOf(1)))
                .isEqualTo("Condition evaluated to false (expected, not an error)");
        assertThat(skill.interpretExitCode("curl https://example.com", Integer.valueOf(28)))
                .isEqualTo("Operation timed out");
        assertThat(skill.interpretExitCode("python3 script.py", Integer.valueOf(1))).isNull();
        assertThat(skill.interpretExitCode("", Integer.valueOf(1))).isNull();
        assertThat(skill.interpretExitCode("FOO=bar", Integer.valueOf(1))).isNull();
        assertThat(skill.interpretExitCode("grep 'pattern' file.txt", Integer.valueOf(0))).isNull();
    }

    @Test
    void shouldStartHermesTerminalBackgroundProcessInRegistry() throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config, null, registry);

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
    void shouldReturnHermesJsonForForegroundTerminalCommands() throws Exception {
        AppConfig config = new AppConfig();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config);

        ONode success =
                ONode.ofJson(
                        skill.terminal(
                                "echo terminal-json-ok",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));
        ONode failed =
                ONode.ofJson(
                        skill.terminal(
                                terminalExitCommand(7),
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));

        assertThat(success.get("output").getString()).contains("terminal-json-ok");
        assertThat(success.get("exit_code").getInt()).isEqualTo(0);
        assertThat(success.get("error").isNull()).isTrue();
        assertThat(success.get("exit_code_meaning").isNull()).isTrue();
        assertThat(failed.get("exit_code").getInt()).isEqualTo(7);
        assertThat(failed.get("error").isNull()).isTrue();
    }

    @Test
    void shouldRedactSecretsFromForegroundTerminalOutputAndBackgroundMetadata()
            throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config, null, registry);

        ONode foreground =
                ONode.ofJson(
                        skill.terminal(
                                echoSecretCommand(),
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));
        ONode background =
                ONode.ofJson(
                        skill.terminal(
                                sleepWithSecretCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));

        assertThat(foreground.get("output").getString())
                .contains("api_key=***")
                .contains("token=***")
                .doesNotContain("sk-test-secret")
                .doesNotContain("secret123")
                .doesNotContain("user:pass");
        assertThat(background.get("command").getString())
                .contains("TOKEN=***")
                .doesNotContain("secret123");
        assertThat(registry.stop(background.get("session_id").getString())).isTrue();
    }

    @Test
    void shouldRedactSecretsFromExecuteShellTextOutput() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String result = skill.execute(echoSecretCommand(), Integer.valueOf(10000));

        assertThat(result)
                .contains("api_key=***")
                .contains("token=***")
                .doesNotContain("sk-test-secret")
                .doesNotContain("secret123");
    }

    @Test
    void shouldReturnExitCodeMeaningForForegroundTerminalCommands() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        AppConfig config = new AppConfig();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "test -f /definitely-not-a-jimuqu-file",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(1);
        assertThat(result.get("error").isNull()).isTrue();
        assertThat(result.get("exit_code_meaning").getString())
                .isEqualTo("Condition evaluated to false (expected, not an error)");
    }

    @Test
    void shouldRejectForegroundBackgroundWrappersOnDirectShellExecution() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThatThrownBy(() -> skill.execute("nohup npm run dev > app.log 2>&1", 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nohup")
                .hasMessageContaining("受管的后台进程能力");
    }

    @Test
    void shouldTimeoutSudoRewriteBranchWithoutWaitingForOutputEof() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("testpass");
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String result = skill.execute("sudo ping 127.0.0.1", 10);

        assertThat(result).contains("执行超时").contains("Command timed out");
    }

    @Test
    void shouldPreservePartialForegroundOutputOnTimeoutLikeHermes() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String result = skill.execute(partialOutputThenSleepCommand(), Integer.valueOf(200));

        assertThat(result)
                .contains("hello from timeout")
                .contains("执行超时")
                .contains("Command timed out after 200 ms");
    }

    @Test
    void shouldPreservePartialTerminalJsonOutputOnTimeoutLikeHermes() throws Exception {
        AppConfig config = new AppConfig();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                partialOutputThenSleepCommand(),
                                Boolean.FALSE,
                                Integer.valueOf(1),
                                workdir,
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(result.get("error").getString()).contains("Command timed out");
        assertThat(result.get("output").getString())
                .contains("hello from timeout")
                .contains("Command timed out after 1000 ms");
    }

    @Test
    void shouldCloseForegroundStdinWhenNoInputIsProvidedLikeHermesPipedStdinGuardrail()
            throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String result = skill.execute(waitForStdinEofCommand(), Integer.valueOf(10000));

        assertThat(result).contains("stdin-closed");
    }

    @Test
    void shouldApplyHermesForegroundOutputByteLimit() throws Exception {
        AppConfig config = new AppConfig();
        config.getTask().setToolOutputInlineLimit(300);
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

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

    private String partialOutputThenSleepCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo hello from timeout && ping -n 30 127.0.0.1 > nul";
        }
        return "printf 'hello from timeout\\n'; sleep 30";
    }

    private String javaLongOutputCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "powershell -NoProfile -Command \"$s='head-' + ('A' * 600) + 'middle-' + ('B' * 600) + 'tail-'; [Console]::Write($s)\"";
        }
        return "printf 'head-'; head -c 600 /dev/zero | tr '\\0' 'A'; printf 'middle-'; head -c 600 /dev/zero | tr '\\0' 'B'; printf 'tail-'";
    }

    private String terminalExitCommand(int exitCode) {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "exit /b " + exitCode;
        }
        return "exit " + exitCode;
    }

    private String echoSecretCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo api_key=sk-test-secret token=secret123 https://user:pass@example.com/?token=secret123";
        }
        return "printf '%s\\n' 'api_key=sk-test-secret token=secret123 https://user:pass@example.com/?token=secret123'";
    }

    private String sleepWithSecretCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "set TOKEN=secret123 && ping -n 30 127.0.0.1 > nul";
        }
        return "TOKEN=secret123 sleep 30";
    }

    private String waitForStdinEofCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "powershell -NoProfile -Command \"$null=[Console]::In.ReadToEnd(); Write-Output stdin-closed\"";
        }
        return "cat >/dev/null; printf 'stdin-closed\\n'";
    }
}

