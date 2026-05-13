package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SolonClawShellSkill;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.TerminalAnsiSanitizer;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Param;

public class SolonClawShellSkillTest {
    @Test
    void shouldReturnCleanTerminalErrorForNullCommandLikeJimuqu() throws Exception {
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
    void shouldReturnCleanTerminalErrorForBlankCommandLikeJimuqu() throws Exception {
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
    void shouldRedactSecretsFromTerminalSystemErrors() throws Exception {
        AppConfig config = new AppConfig();
        String leakedToken = "sk-1234567890abcdef";
        SolonClawShellSkill skill =
                new SolonClawShellSkill(
                        Files.createTempDirectory("jimuqu-shell").toString(),
                        "missing-shell-" + leakedToken,
                        ".sh",
                        config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "echo should-not-run",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                null,
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(result.get("error").getString())
                .contains("***")
                .doesNotContain(leakedToken)
                .doesNotContain("1234567890abcdef");
    }

    @Test
    void shouldHandleNullSudoTransformLikeJimuqu() throws Exception {
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
    void shouldExposeSudoRewritePolicySummaryWithoutSecrets() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("testpass");
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        Map<String, Object> summary = skill.sudoRewritePolicySummary();

        assertThat(summary.get("configured")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("envKey")).isEqualTo("SUDO_PASSWORD");
        assertThat(summary.get("configKey")).isEqualTo("terminal.sudoPassword");
        assertThat(summary.get("rewritesRealSudoInvocations")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("stdinPasswordInjection")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("passwordRedacted")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("existingStdinFlagPreserved")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("commentsIgnored")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("quotedSudoIgnored")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("envAssignmentPrefixSupported")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("compoundCommandSupported")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("ptyDisabledForStdinPipe")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("missingPasswordHint")).isEqualTo(Boolean.TRUE);
        assertThat(summary.toString()).doesNotContain("testpass");

        assertThat(SolonClawShellSkill.sudoRewritePolicySummary(false).get("configured"))
                .isEqualTo(Boolean.FALSE);
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
    void shouldRewriteOnlyRealCompoundSudoInvocationsLikeJimuqu() throws Exception {
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
    void shouldTreatExplicitEmptySudoPasswordAsConfiguredLikeJimuqu() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("");
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        SolonClawShellSkill.SudoTransform transform = skill.transformSudoCommand("sudo true");

        assertThat(transform.isChanged()).isTrue();
        assertThat(transform.getCommand()).isEqualTo("sudo -S -p '' true");
        assertThat(transform.getStdin()).isEqualTo("\n");
    }

    @Test
    void shouldValidateWindowsWorkdirTextLikeJimuquTerminalGuardrail() {
        assertThat(SecurityPolicyService.checkWorkdirText("C:\\Users\\Alice\\project").isAllowed())
                .isTrue();
        assertThat(SecurityPolicyService.checkWorkdirText("C:/Users/Alice/project").isAllowed())
                .isTrue();
        assertThat(SecurityPolicyService.checkWorkdirText("\\\\server\\share\\project").isAllowed())
                .isTrue();

        SecurityPolicyService.FileVerdict semicolon =
                SecurityPolicyService.checkWorkdirText("C:\\Users\\Alice\\project; rm -rf /");
        SecurityPolicyService.FileVerdict subshell =
                SecurityPolicyService.checkWorkdirText("C:\\Users\\Alice\\project$(whoami)");
        SecurityPolicyService.FileVerdict newline =
                SecurityPolicyService.checkWorkdirText("C:\\Users\\Alice\\project\nwhoami");
        SecurityPolicyService.FileVerdict tab =
                SecurityPolicyService.checkWorkdirText("C:\\Users\\Alice\\project\tchild");
        SecurityPolicyService.FileVerdict nullByte =
                SecurityPolicyService.checkWorkdirText("C:\\Users\\Alice\\project\u0000child");

        assertThat(semicolon.isAllowed()).isFalse();
        assertThat(semicolon.getMessage()).contains(";");
        assertThat(subshell.isAllowed()).isFalse();
        assertThat(subshell.getMessage()).contains("$");
        assertThat(newline.isAllowed()).isFalse();
        assertThat(newline.getMessage()).contains("\\n");
        assertThat(tab.isAllowed()).isFalse();
        assertThat(tab.getMessage()).contains("\\t");
        assertThat(nullByte.isAllowed()).isFalse();
        assertThat(nullByte.getMessage()).contains("\\u0000");
    }

    @Test
    void shouldRejectForegroundAndBackgroundCredentialWorkdirs() throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry();
        Path workdir = Files.createTempDirectory("jimuqu-shell");
        Path credentialDir = workdir.resolve(".ssh");
        Files.createDirectories(credentialDir);
        SolonClawShellSkill skill =
                new SolonClawShellSkill(
                        workdir.toString(), config, new SecurityPolicyService(config), registry);

        ONode foreground =
                ONode.ofJson(
                        skill.terminal(
                                "echo blocked",
                                Boolean.FALSE,
                                Integer.valueOf(1),
                                credentialDir.toString(),
                                Boolean.FALSE));
        ONode background =
                ONode.ofJson(
                        skill.terminal(
                                javaSleepCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(1),
                                credentialDir.toString(),
                                Boolean.FALSE));

        assertThat(foreground.get("success").getBoolean()).isFalse();
        assertThat(foreground.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(foreground.get("error").getString())
                .contains("workdir path")
                .contains("敏感系统/凭据文件")
                .doesNotContain(".ssh")
                .doesNotContain(workdir.toString());
        assertThat(background.get("success").getBoolean()).isFalse();
        assertThat(background.get("error").getString())
                .contains("workdir path")
                .contains("敏感系统/凭据文件")
                .doesNotContain(".ssh")
                .doesNotContain(workdir.toString());
    }

    @Test
    void shouldStripEightBitAnsiFromTerminalOutputLikeJimuqu() throws Exception {
        assertThat(TerminalAnsiSanitizer.stripAnsi("\u009B31mred\u009B0m"))
                .isEqualTo("red");
        assertThat(TerminalAnsiSanitizer.stripAnsi("\u009D52;c;clipboard\u009Cvisible"))
                .isEqualTo("visible");
    }

    @Test
    void shouldStripSevenBitOsc52ClipboardSequencesLikeJimuqu() {
        assertThat(TerminalAnsiSanitizer.stripAnsi("\u001B]52;c;c2VjcmV0\u0007visible"))
                .isEqualTo("visible");
        assertThat(TerminalAnsiSanitizer.stripAnsi("\u001B]52;c;c2VjcmV0\u001B\\visible"))
                .isEqualTo("visible");
    }

    @Test
    void shouldStripEcma48AnsiSequencesLikeJimuqu() {
        assertThat(TerminalAnsiSanitizer.stripAnsi("\u001B[38:2:255:0:0mred\u001B[0m"))
                .isEqualTo("red");
        assertThat(TerminalAnsiSanitizer.stripAnsi("\u001B[?1049halt\u001B[?1049l"))
                .isEqualTo("alt");
        assertThat(
                        TerminalAnsiSanitizer.stripAnsi(
                                "\u001B]8;;https://example.com\u001B\\click\u001B]8;;\u001B\\"))
                .isEqualTo("click");
        assertThat(TerminalAnsiSanitizer.stripAnsi("\u001BP+q\u001B\\done"))
                .isEqualTo("done");
        assertThat(TerminalAnsiSanitizer.stripAnsi("\u001B(Achars\u001B(B"))
                .isEqualTo("chars");
        assertThat(TerminalAnsiSanitizer.stripAnsi("arr[0] = arr[31]"))
                .isEqualTo("arr[0] = arr[31]");
    }

    @Test
    void shouldStripDisplayControlCharactersFromTerminalOutputLikeJimuqu() {
        assertThat(TerminalAnsiSanitizer.stripAnsi("safe\u0007\u0008hidden\rnext\u007F"))
                .isEqualTo("safehiddennext");
        assertThat(TerminalAnsiSanitizer.stripAnsi("line1\n\tline2"))
                .isEqualTo("line1\n\tline2");
        assertThat(TerminalAnsiSanitizer.stripAnsi("rm -rf safe\u202Ecod.exe\u2069"))
                .isEqualTo("rm -rf safecod.exe");
    }

    @Test
    void shouldResolveDefaultShellInitFilesInJimuquOrder() throws Exception {
        Path home = Files.createTempDirectory("jimuqu-shell-init-home");
        Path profile = home.resolve(".profile");
        Path bashProfile = home.resolve(".bash_profile");
        Path bashrc = home.resolve(".bashrc");
        Files.write(profile, Collections.singletonList("export FROM_PROFILE=1"));
        Files.write(bashProfile, Collections.singletonList("export FROM_BASH_PROFILE=1"));
        Files.write(bashrc, Collections.singletonList("export FROM_BASHRC=1"));

        List<String> resolved =
                SolonClawShellSkill.resolveShellInitFiles(
                        Collections.<String>emptyList(),
                        true,
                        false,
                        home.toString(),
                        Collections.<String, String>emptyMap());

        assertThat(resolved)
                .containsExactly(
                        profile.toString(),
                        bashProfile.toString(),
                        bashrc.toString());
    }

    @Test
    void shouldLetExplicitShellInitFilesWinOverAutoLikeJimuqu() throws Exception {
        Path home = Files.createTempDirectory("jimuqu-shell-init-explicit");
        Path bashrc = home.resolve(".bashrc");
        Path custom = home.resolve("custom.sh");
        Files.write(bashrc, Collections.singletonList("export FROM_BASHRC=1"));
        Files.write(custom, Collections.singletonList("export FROM_CUSTOM=1"));

        List<String> resolved =
                SolonClawShellSkill.resolveShellInitFiles(
                        Collections.singletonList("~/custom.sh"),
                        true,
                        false,
                        home.toString(),
                        Collections.<String, String>emptyMap());

        assertThat(resolved).containsExactly(custom.toString());
    }

    @Test
    void shouldExpandShellInitEnvVarsAndSkipMissingFilesLikeJimuqu() throws Exception {
        Path home = Files.createTempDirectory("jimuqu-shell-init-env");
        Path rc = home.resolve("rc");
        Files.createDirectories(rc);
        Path custom = rc.resolve("custom.sh");
        Files.write(custom, Collections.singletonList("export FROM_CUSTOM=1"));
        Map<String, String> env = new HashMap<String, String>();
        env.put("CUSTOM_RC_DIR", rc.toString());

        List<String> resolved =
                SolonClawShellSkill.resolveShellInitFiles(
                        Arrays.asList(
                                "${CUSTOM_RC_DIR}/custom.sh",
                                home.resolve("missing.sh").toString()),
                        false,
                        false,
                        home.toString(),
                        env);

        assertThat(resolved).containsExactly(custom.toString());
    }

    @Test
    void shouldNotResolveShellInitFilesOnWindows() throws Exception {
        Path home = Files.createTempDirectory("jimuqu-shell-init-windows");
        Path profile = home.resolve(".profile");
        Files.write(profile, Collections.singletonList("export FROM_PROFILE=1"));

        List<String> resolved =
                SolonClawShellSkill.resolveShellInitFiles(
                        Collections.singletonList(profile.toString()),
                        true,
                        true,
                        home.toString(),
                        Collections.<String, String>emptyMap());

        assertThat(resolved).isEmpty();
    }

    @Test
    void shouldPrependGuardedShellInitSourceLinesLikeJimuqu() {
        String wrapped =
                SolonClawShellSkill.prependShellInit(
                        "echo hi",
                        Arrays.asList("/tmp/a.sh", "/tmp/o'malley.sh"),
                        false);

        assertThat(wrapped).startsWith("set +e\n");
        assertThat(wrapped).contains("[ -r '/tmp/a.sh' ] && . '/tmp/a.sh' 2>/dev/null || true");
        assertThat(wrapped).contains("o'\\''malley");
        assertThat(wrapped).endsWith("echo hi");
    }

    @Test
    void shouldRejectWorkdirWithShellMetacharactersLikeJimuquTerminalGuardrail() {
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
    void shouldResolveSafeCwdToNearestExistingAncestorLikeJimuqu() throws Exception {
        Path root = Files.createTempDirectory("jimuqu-safe-cwd");
        Path nested = root.resolve("child").resolve("grandchild");
        Files.createDirectories(nested);
        deleteRecursively(root.resolve("child"));

        File safe = SolonClawShellSkill.resolveSafeCwd(nested.toString());

        assertThat(safe).isEqualTo(root.toFile().getAbsoluteFile());
    }

    @Test
    void shouldResolveSafeCwdToFallbackWhenPathIsBlankLikeJimuqu() throws Exception {
        Path fallback = Files.createTempDirectory("jimuqu-safe-cwd-fallback");

        File safe = SolonClawShellSkill.resolveSafeCwd("", fallback.toFile());

        assertThat(safe).isEqualTo(fallback.toFile().getAbsoluteFile());
    }

    @Test
    void shouldRecoverForegroundTerminalWhenRequestedWorkdirWasDeletedLikeJimuqu()
            throws Exception {
        AppConfig config = new AppConfig();
        Path root = Files.createTempDirectory("jimuqu-shell-cwd");
        Path nested = root.resolve("child").resolve("grandchild");
        Files.createDirectories(nested);
        SolonClawShellSkill skill = new SolonClawShellSkill(root.toString(), config);
        deleteRecursively(root.resolve("child"));

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                printWorkingDirectoryCommand(),
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                nested.toString(),
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(0);
        assertThat(Paths.get(lastOutputLine(result.get("output").getString())).toRealPath())
                .isEqualTo(root.toRealPath());
    }

    @Test
    void shouldRunForegroundTerminalInWorkdirWithSpacesLikeJimuqu() throws Exception {
        AppConfig config = new AppConfig();
        Path root = Files.createTempDirectory("jimuqu-shell-cwd");
        Path spaced = root.resolve("project with spaces");
        Files.createDirectories(spaced);
        SolonClawShellSkill skill = new SolonClawShellSkill(root.toString(), config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                printWorkingDirectoryCommand(),
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                spaced.toString(),
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(0);
        assertThat(Paths.get(lastOutputLine(result.get("output").getString())).toRealPath())
                .isEqualTo(spaced.toRealPath());
    }

    @Test
    void shouldRejectForegroundAndBackgroundWorkdirsWithShellMetacharactersLikeJimuqu()
            throws Exception {
        AppConfig config = new AppConfig();
        Path root = Files.createTempDirectory("jimuqu-shell-cwd");
        SolonClawShellSkill skill = new SolonClawShellSkill(root.toString(), config);
        String unsafeWorkdir = root.toString() + "; rm -rf runtime";

        ONode foreground =
                ONode.ofJson(
                        skill.terminal(
                                "echo should-not-run",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                unsafeWorkdir,
                                Boolean.FALSE));
        ONode background =
                ONode.ofJson(
                        skill.terminal(
                                "echo should-not-run",
                                Boolean.TRUE,
                                Integer.valueOf(5),
                                unsafeWorkdir,
                                Boolean.FALSE));

        assertThat(foreground.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(foreground.get("error").getString())
                .contains("Blocked")
                .contains("disallowed character")
                .contains("shell metacharacters");
        assertThat(background.get("success").getBoolean()).isFalse();
        assertThat(background.get("error").getString())
                .contains("Blocked")
                .contains("disallowed character")
                .contains("shell metacharacters");
    }

    @Test
    void shouldTreatUnreachableUncWorkdirAsRecoverablePathButStillRejectUncMetacharacters()
            throws Exception {
        AppConfig config = new AppConfig();
        Path fallback = Files.createTempDirectory("jimuqu-shell-cwd");
        SolonClawShellSkill skill = new SolonClawShellSkill(fallback.toString(), config);

        ONode unc =
                ONode.ofJson(
                        skill.terminal(
                                printWorkingDirectoryCommand(),
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                "\\\\server\\share\\missing-project",
                                Boolean.FALSE));
        ONode unsafeUnc =
                ONode.ofJson(
                        skill.terminal(
                                "echo should-not-run",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                "\\\\server\\share\\project; rm -rf runtime",
                                Boolean.FALSE));

        assertThat(unc.get("exit_code").getInt()).isEqualTo(0);
        assertThat(Paths.get(lastOutputLine(unc.get("output").getString())).toRealPath())
                .isEqualTo(fallback.toRealPath());
        assertThat(unsafeUnc.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(unsafeUnc.get("error").getString())
                .contains("Blocked")
                .contains("disallowed character");
    }

    @Test
    void shouldRejectForegroundTimeoutAboveJimuquCap() throws Exception {
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
    void shouldExposeForegroundTimeoutCapInToolParameterDescriptionsLikeJimuqu()
            throws Exception {
        Method execute = SolonClawShellSkill.class.getMethod("execute", String.class, Integer.class);
        Method terminal =
                SolonClawShellSkill.class.getMethod(
                        "terminal",
                        String.class,
                        Boolean.class,
                        Integer.class,
                        String.class,
                        Boolean.class,
                        Boolean.class,
                        List.class);

        String executeTimeoutDescription = paramDescription(execute, "timeout");
        String terminalTimeoutDescription = paramDescription(terminal, "timeout");

        assertThat(executeTimeoutDescription).contains("600000ms").contains("background=true");
        assertThat(terminalTimeoutDescription)
                .contains("600 seconds")
                .contains("background=true");
    }

    @Test
    void shouldNotRejectDefaultTerminalTimeoutAboveCapLikeJimuqu() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setMaxForegroundTimeoutSeconds(1);
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "echo default-timeout-ok",
                                Boolean.FALSE,
                                null,
                                workdir,
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(0);
        assertThat(result.get("error").isNull()).isTrue();
        assertThat(result.get("output").getString()).contains("default-timeout-ok");
    }

    @Test
    void shouldApplyJimuquCompoundBackgroundRewriteBeforeForegroundExecution() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(skill.rewriteCompoundBackground("echo ready && python -m http.server 8000 &"))
                .isEqualTo("echo ready && { python -m http.server 8000 & }");
        assertThat(skill.rewriteCompoundBackground("echo ready && { python -m http.server 8000 & }"))
                .isEqualTo("echo ready && { python -m http.server 8000 & }");
    }

    @Test
    void shouldRewriteCompoundBackgroundShellPatterns() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(skill.rewriteCompoundBackground("A && B &")).isEqualTo("A && { B & }");
        assertThat(skill.rewriteCompoundBackground("A || B &")).isEqualTo("A || { B & }");
        assertThat(skill.rewriteCompoundBackground("A && B && C &"))
                .isEqualTo("A && B && { C & }");
        assertThat(skill.rewriteCompoundBackground("A || B || C &"))
                .isEqualTo("A || B || { C & }");
        assertThat(skill.rewriteCompoundBackground("A && B || C &"))
                .isEqualTo("A && B || { C & }");
        assertThat(skill.rewriteCompoundBackground("A && B &\nfalse || C &"))
                .isEqualTo("A && { B & }\nfalse || { C & }");
        assertThat(
                        skill.rewriteCompoundBackground(
                                "cd /home/exedev && python3 -m http.server 8000 &>/dev/null &\n"
                                        + "sleep 1\n"
                                        + "curl -s -o /dev/null -w \"%{http_code}\" http://localhost:8000/"))
                .isEqualTo(
                        "cd /home/exedev && { python3 -m http.server 8000 &>/dev/null & }\n"
                                + "sleep 1\n"
                                + "curl -s -o /dev/null -w \"%{http_code}\" http://localhost:8000/");
        assertThat(skill.rewriteCompoundBackground("   A && B &"))
                .isEqualTo("   A && { B & }");
        assertThat(skill.rewriteCompoundBackground("A\t&&\tB\t&"))
                .isEqualTo("A\t&&\t{ B\t& }");
    }

    @Test
    void shouldPreserveNonCompoundBackgroundShellPatterns() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(skill.rewriteCompoundBackground("sleep 5 &")).isEqualTo("sleep 5 &");
        assertThat(skill.rewriteCompoundBackground("python3 -m http.server 0 &"))
                .isEqualTo("python3 -m http.server 0 &");
        assertThat(skill.rewriteCompoundBackground("A && B")).isEqualTo("A && B");
        assertThat(skill.rewriteCompoundBackground("A && B\nC &")).isEqualTo("A && B\nC &");
        assertThat(skill.rewriteCompoundBackground("A && B; C &")).isEqualTo("A && B; C &");
        assertThat(skill.rewriteCompoundBackground("cd /tmp; start-server &"))
                .isEqualTo("cd /tmp; start-server &");
        assertThat(skill.rewriteCompoundBackground("A && B | C &")).isEqualTo("A && B | C &");
        assertThat(skill.rewriteCompoundBackground("&")).isEqualTo("&");
        assertThat(skill.rewriteCompoundBackground("")).isEqualTo("");
        assertThat(skill.rewriteCompoundBackground("   \n\t")).isEqualTo("   \n\t");
    }

    @Test
    void shouldNotCrashOnMalformedCompoundBackgroundInput() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(skill.rewriteCompoundBackground("A && &")).isNotNull();
    }

    @Test
    void shouldNotMistakeRedirectsQuotesOrSubshellsForCompoundBackgrounds() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(skill.rewriteCompoundBackground("echo hi &>/dev/null"))
                .isEqualTo("echo hi &>/dev/null");
        assertThat(skill.rewriteCompoundBackground("cmd 2>&1")).isEqualTo("cmd 2>&1");
        assertThat(skill.rewriteCompoundBackground("cmd 2>&1 &")).isEqualTo("cmd 2>&1 &");
        assertThat(skill.rewriteCompoundBackground("A && B &>/dev/null &"))
                .isEqualTo("A && { B &>/dev/null & }");
        assertThat(skill.rewriteCompoundBackground("A && B 2>&1 &"))
                .isEqualTo("A && { B 2>&1 & }");
        assertThat(skill.rewriteCompoundBackground("echo 'A && B &'"))
                .isEqualTo("echo 'A && B &'");
        assertThat(skill.rewriteCompoundBackground("echo \"A && B &\""))
                .isEqualTo("echo \"A && B &\"");
        assertThat(skill.rewriteCompoundBackground("(A && B) &")).isEqualTo("(A && B) &");
        assertThat(skill.rewriteCompoundBackground("echo \"$(A && B)\" &"))
                .isEqualTo("echo \"$(A && B)\" &");
        assertThat(skill.rewriteCompoundBackground("echo A \\&\\& B"))
                .isEqualTo("echo A \\&\\& B");
        assertThat(skill.rewriteCompoundBackground("# A && B &\nC")).isEqualTo("# A && B &\nC");
    }

    @Test
    void shouldKeepCompoundBackgroundRewriteIdempotent() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String once = skill.rewriteCompoundBackground("A && B &");
        assertThat(skill.rewriteCompoundBackground(once)).isEqualTo(once);

        String multiline = skill.rewriteCompoundBackground("cd /tmp && server &\nsleep 1");
        assertThat(skill.rewriteCompoundBackground(multiline)).isEqualTo(multiline);
    }

    @Test
    void shouldDetectJimuquPipeStdinCommandsThatCannotUsePty() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(
                        skill.commandRequiresPipeStdin(
                                "gh auth login --hostname github.com --git-protocol https --with-token"))
                .isTrue();
        assertThat(skill.commandRequiresPipeStdin("gh auth login --web")).isFalse();
    }

    @Test
    void shouldReturnPtyDisabledNoteForJimuquPipeStdinBackgroundCommands() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(
                        skill.ptyDisabledNote(
                                "gh auth login --hostname github.com --git-protocol https --with-token",
                                Boolean.TRUE))
                .contains("PTY disabled")
                .contains("gh auth login --with-token");
        assertThat(skill.ptyDisabledNote("gh auth login --web", Boolean.TRUE)).isNull();
        assertThat(
                        skill.ptyDisabledNote(
                                "gh auth login --hostname github.com --git-protocol https --with-token",
                                Boolean.FALSE))
                .isNull();
    }

    @Test
    void shouldInterpretJimuquTerminalExitCodeSemantics() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        assertThat(skill.interpretExitCode("grep 'pattern' file.txt", Integer.valueOf(1)))
                .isEqualTo("No matches found (not an error)");
        assertThat(skill.interpretExitCode("egrep 'pattern' file.txt", Integer.valueOf(1)))
                .isEqualTo("No matches found (not an error)");
        assertThat(skill.interpretExitCode("fgrep 'pattern' file.txt", Integer.valueOf(1)))
                .isEqualTo("No matches found (not an error)");
        assertThat(skill.interpretExitCode("rg 'foo' .", Integer.valueOf(2))).isNull();
        assertThat(skill.interpretExitCode("ag 'foo' .", Integer.valueOf(1)))
                .isEqualTo("No matches found (not an error)");
        assertThat(skill.interpretExitCode("ack 'foo' .", Integer.valueOf(1)))
                .isEqualTo("No matches found (not an error)");
        assertThat(skill.interpretExitCode("ls -la | grep 'pattern'", Integer.valueOf(1)))
                .isEqualTo("No matches found (not an error)");
        assertThat(skill.interpretExitCode("cd /tmp && grep foo bar", Integer.valueOf(1)))
                .isEqualTo("No matches found (not an error)");
        assertThat(skill.interpretExitCode("cat file; diff a b", Integer.valueOf(1)))
                .isEqualTo("Files differ (expected, not an error)");
        assertThat(skill.interpretExitCode("colordiff a b", Integer.valueOf(1)))
                .isEqualTo("Files differ (expected, not an error)");
        assertThat(skill.interpretExitCode("false || /usr/bin/grep foo bar", Integer.valueOf(1)))
                .isEqualTo("No matches found (not an error)");
        assertThat(skill.interpretExitCode("/usr/bin/grep 'foo' bar", Integer.valueOf(1)))
                .isEqualTo("No matches found (not an error)");
        assertThat(skill.interpretExitCode("FOO=1 BAR=2 grep 'foo' bar", Integer.valueOf(1)))
                .isEqualTo("No matches found (not an error)");
        assertThat(skill.interpretExitCode("find . -name '*.java'", Integer.valueOf(1)))
                .isEqualTo("Some directories were inaccessible (partial results may still be valid)");
        assertThat(skill.interpretExitCode("LANG=C test -f /nonexistent", Integer.valueOf(1)))
                .isEqualTo("Condition evaluated to false (expected, not an error)");
        assertThat(skill.interpretExitCode("[ -f /nonexistent ]", Integer.valueOf(1)))
                .isEqualTo("Condition evaluated to false (expected, not an error)");
        assertThat(skill.interpretExitCode("curl https://example.com", Integer.valueOf(28)))
                .isEqualTo("Operation timed out");
        assertThat(skill.interpretExitCode("curl http://localhost:99999", Integer.valueOf(7)))
                .isEqualTo("Failed to connect to host");
        assertThat(skill.interpretExitCode("git diff HEAD~1", Integer.valueOf(1)))
                .isEqualTo("Git diff found differences (normal for diff commands)");
        assertThat(skill.interpretExitCode("LANG=C git diff --exit-code", Integer.valueOf(1)))
                .isEqualTo("Git diff found differences (normal for diff commands)");
        assertThat(skill.interpretExitCode("git status", Integer.valueOf(1))).isNull();
        assertThat(skill.interpretExitCode("git rev-parse --verify missing", Integer.valueOf(1)))
                .isNull();
        assertThat(skill.interpretExitCode("python3 script.py", Integer.valueOf(1))).isNull();
        assertThat(skill.interpretExitCode("", Integer.valueOf(1))).isNull();
        assertThat(skill.interpretExitCode("FOO=bar", Integer.valueOf(1))).isNull();
        assertThat(skill.interpretExitCode("grep 'pattern' file.txt", Integer.valueOf(0))).isNull();
    }

    @Test
    void shouldExposeTerminalExitCodePolicySummary() throws Exception {
        AppConfig config = new AppConfig();

        Map<String, Object> summary = SolonClawShellSkill.terminalOutputPolicySummary(config);

        assertThat(summary.get("exitCodeSemanticsAvailable")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("ecma48SequencesStripped")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("oscSequencesStripped")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("eightBitC1ControlsStripped")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("displayControlCharsStripped")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("bidiControlsStripped")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("exitCodeMeaningReturned")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("executeShellExitMeaningNotice")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(summary.get("exitCodeSemantics")))
                .contains("grepNoMatchExitOneInformational")
                .contains("gitDiffExitOneInformational")
                .contains("curlNetworkErrorsExplained")
                .contains("grep:1")
                .contains("curl:6/7/22/28");
    }

    @Test
    void shouldStartJimuquTerminalBackgroundProcessInRegistry() throws Exception {
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
    void shouldStoreJimuquWatchPatternsForManagedBackgroundTerminalProcesses()
            throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config, null, registry);
        List<String> watchPatterns = Arrays.asList("Application startup complete", "", "ready");

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                javaSleepCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(1),
                                workdir,
                                Boolean.FALSE,
                                Boolean.FALSE,
                                watchPatterns));

        String sessionId = result.get("session_id").getString();
        assertThat(result.get("success").getBoolean()).isTrue();
        assertThat(result.get("notify_on_complete").getBoolean()).isFalse();
        assertThat(result.get("watch_patterns").get(0).getString())
                .isEqualTo("Application startup complete");
        assertThat(result.get("watch_patterns").get(1).getString()).isEqualTo("ready");
        assertThat(registry.get(sessionId).getWatchPatterns())
                .containsExactly("Application startup complete", "ready");
        assertThat(registry.get(sessionId).isNotifyOnComplete()).isFalse();

        assertThat(registry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldRedactSensitiveWatchPatternsInTerminalResponsesLikeJimuqu()
            throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config, null, registry);
        String sensitivePattern = "token=secret123";

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                javaSleepCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(1),
                                workdir,
                                Boolean.FALSE,
                                Boolean.FALSE,
                                Collections.singletonList(sensitivePattern)));

        String sessionId = result.get("session_id").getString();
        assertThat(result.get("watch_patterns").get(0).getString()).isEqualTo("token=***");
        assertThat(registry.get(sessionId).getWatchPatterns()).containsExactly(sensitivePattern);

        assertThat(registry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldDropWatchPatternsWhenNotifyOnCompleteIsSetLikeJimuqu() throws Exception {
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
                                Boolean.TRUE,
                                Boolean.FALSE,
                                Arrays.asList("ready")));

        String sessionId = result.get("session_id").getString();
        assertThat(result.get("notify_on_complete").getBoolean()).isTrue();
        assertThat(result.get("watch_patterns").isNull()).isTrue();
        assertThat(result.get("watch_patterns_ignored").getString())
                .contains("watch_patterns ignored")
                .contains("duplicate notifications");
        assertThat(registry.get(sessionId).getWatchPatterns()).isEmpty();
        assertThat(registry.get(sessionId).isNotifyOnComplete()).isTrue();

        assertThat(registry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldIncludeBackgroundNotificationMetadataInProcessSnapshot() throws Exception {
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
                                Boolean.FALSE,
                                Boolean.FALSE,
                                Arrays.asList("ready")));

        String sessionId = result.get("session_id").getString();
        ONode process = ONode.ofJson(ONode.serialize(registry.get(sessionId).toRedactedMap()));
        assertThat(process.get("notify_on_complete").getBoolean()).isFalse();
        assertThat(process.get("watch_patterns").get(0).getString()).isEqualTo("ready");

        assertThat(registry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldRedactSensitiveWatchPatternsInProcessSnapshotLikeJimuqu()
            throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config, null, registry);
        String sensitivePattern = "https://example.com/callback?token=secret123";

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                javaSleepCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(1),
                                workdir,
                                Boolean.FALSE,
                                Boolean.FALSE,
                                Collections.singletonList(sensitivePattern)));

        String sessionId = result.get("session_id").getString();
        ONode process = ONode.ofJson(ONode.serialize(registry.get(sessionId).toRedactedMap()));
        assertThat(process.get("watch_patterns").get(0).getString())
                .contains("token=***")
                .doesNotContain("secret123");
        assertThat(registry.get(sessionId).getWatchPatterns()).containsExactly(sensitivePattern);

        assertThat(registry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldQueueWatchMatchEventsForManagedBackgroundProcesses() throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry(null, 1000L, 3, 100, 1000L, 1000L);
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config, null, registry);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                watchReadyCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(1),
                                workdir,
                                Boolean.FALSE,
                                Boolean.FALSE,
                                Collections.singletonList("ready")));

        String sessionId = result.get("session_id").getString();
        assertThat(registry.waitFor(sessionId, 5000L)).isTrue();

        List<Map<String, Object>> events = registry.drainEvents();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("type")).isEqualTo("watch_match");
        assertThat(events.get(0).get("session_id")).isEqualTo(sessionId);
        assertThat(events.get(0).get("pattern")).isEqualTo("ready");
        assertThat(String.valueOf(events.get(0).get("output"))).contains("ready");
        assertThat(registry.get(sessionId).getWatchHits()).isEqualTo(1);
    }

    @Test
    void shouldRedactSensitiveWatchPatternInEventsLikeJimuqu() throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry(null, 1000L, 3, 100, 1000L, 1000L);
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config, null, registry);
        String sensitivePattern = "token=secret123";

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                printTokenCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(1),
                                workdir,
                                Boolean.FALSE,
                                Boolean.FALSE,
                                Collections.singletonList(sensitivePattern)));

        String sessionId = result.get("session_id").getString();
        assertThat(registry.waitFor(sessionId, 5000L)).isTrue();

        List<Map<String, Object>> events = registry.drainEvents();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("type")).isEqualTo("watch_match");
        assertThat(events.get(0).get("pattern")).isEqualTo("token=***");
        assertThat(String.valueOf(events.get(0).get("output"))).contains("token=***");
        assertThat(String.valueOf(events.get(0).get("output"))).doesNotContain("secret123");
        assertThat(registry.get(sessionId).getWatchPatterns()).containsExactly(sensitivePattern);
    }

    @Test
    void shouldDisableNoisyWatchPatternsAndFallBackToCompletionLikeJimuqu() throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry(null, 250L, 2, 100, 1000L, 1000L);
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config, null, registry);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                repeatedReadyCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(1),
                                workdir,
                                Boolean.FALSE,
                                Boolean.FALSE,
                                Collections.singletonList("ready")));

        String sessionId = result.get("session_id").getString();
        assertThat(registry.waitFor(sessionId, 10000L)).isTrue();

        List<Map<String, Object>> events = registry.drainEvents(10);

        assertThat(eventTypes(events))
                .containsExactly("watch_match", "watch_match", "watch_disabled", "completion");
        assertThat(registry.get(sessionId).isWatchDisabled()).isTrue();
        assertThat(registry.get(sessionId).isNotifyOnComplete()).isTrue();
        assertThat(String.valueOf(events.get(2).get("message"))).contains("Falling back");
        assertThat(String.valueOf(events.get(3).get("output"))).contains("done");
    }

    @Test
    void shouldSkipCompletionEventWhenWaitAlreadyConsumedItLikeJimuqu() throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry(null, 1000L, 3, 100, 1000L, 1000L);
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config, null, registry);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                watchReadyCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(1),
                                workdir,
                                Boolean.TRUE,
                                Boolean.FALSE,
                                null));

        String sessionId = result.get("session_id").getString();
        assertThat(registry.waitFor(sessionId, 5000L)).isTrue();
        registry.markCompletionConsumed(sessionId);

        assertThat(registry.drainEvents()).isEmpty();
    }

    @Test
    void shouldReturnJimuquJsonForForegroundTerminalCommands() throws Exception {
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
    void shouldRetryForegroundTerminalExecutionFailuresLikeJimuqu() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setForegroundMaxRetries(3);
        config.getTerminal().setForegroundRetryBaseDelaySeconds(0);
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(
                        workdir,
                        new File(workdir, "missing-shell").getAbsolutePath(),
                        ".sh",
                        config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "echo never-runs",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(result.get("retry_count").getInt()).isEqualTo(3);
        assertThat(result.get("error").getString()).contains("系统失败");
    }

    @Test
    void shouldNotRetryForegroundTerminalCommandExitCodes() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setForegroundMaxRetries(3);
        config.getTerminal().setForegroundRetryBaseDelaySeconds(0);
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                terminalExitCommand(7),
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(7);
        assertThat(result.get("retry_count").isNull()).isTrue();
    }

    @Test
    void shouldLeaveTerminalOutputUnchangedWhenNoTransformerIsRegistered() throws Exception {
        AppConfig config = new AppConfig();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "echo plain-output",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));

        assertThat(result.get("output").getString()).contains("plain-output");
        assertThat(result.get("exit_code").getInt()).isEqualTo(0);
        assertThat(result.get("error").isNull()).isTrue();
    }

    @Test
    void shouldIgnoreNullTerminalOutputTransformerResultLikeJimuquHook() throws Exception {
        AppConfig config = new AppConfig();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config);
        skill.addOutputTransformer(
                context -> {
                    assertThat(context.getOutput()).contains("plain-output");
                    return null;
                });

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "echo plain-output",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));

        assertThat(result.get("output").getString()).contains("plain-output");
    }

    @Test
    void shouldUseFirstValidTerminalOutputTransformerResult() throws Exception {
        AppConfig config = new AppConfig();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config);
        skill.addOutputTransformer(context -> null);
        skill.addOutputTransformer(context -> "first-replacement");
        skill.addOutputTransformer(context -> "second-replacement");

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "echo plain-output",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));

        assertThat(result.get("output").getString()).isEqualTo("first-replacement");
    }

    @Test
    void shouldFallbackToOriginalTerminalOutputWhenTransformerThrows() throws Exception {
        AppConfig config = new AppConfig();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config);
        skill.addOutputTransformer(
                context -> {
                    throw new RuntimeException("boom");
                });

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "echo plain-output",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));

        assertThat(result.get("output").getString()).contains("plain-output");
        assertThat(result.get("exit_code").getInt()).isEqualTo(0);
        assertThat(result.get("error").isNull()).isTrue();
    }

    @Test
    void shouldStillNormalizeTransformedTerminalOutputLikeJimuqu() throws Exception {
        AppConfig config = new AppConfig();
        config.getTask().setToolOutputInlineLimit(300);
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config);
        skill.addOutputTransformer(
                context ->
                        "PLUGIN-HEAD\n"
                                + repeat("A", 600)
                                + "\napi_key=sk-test-secret \u001B[31mPLUGIN-TAIL\u001B[0m");

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "echo plain-output",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));

        String output = result.get("output").getString();
        assertThat(output)
                .contains("PLUGIN-HEAD")
                .contains("PLUGIN-TAIL")
                .contains("OUTPUT TRUNCATED")
                .contains("api_key=***")
                .doesNotContain("sk-test-secret")
                .doesNotContain("\u001B");
    }

    @Test
    void shouldKeepExitCodeWhenTerminalOutputIsTransformed() throws Exception {
        AppConfig config = new AppConfig();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config);
        skill.addOutputTransformer(context -> "replaced-output");

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                terminalExitCommand(7),
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));

        assertThat(result.get("output").getString()).isEqualTo("replaced-output");
        assertThat(result.get("exit_code").getInt()).isEqualTo(7);
    }

    @Test
    void shouldApplyTerminalOutputTransformerToExecuteShellCompatibilityTool()
            throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);
        skill.addOutputTransformer(context -> "execute-shell-replacement");

        String result = skill.execute("echo plain-output", Integer.valueOf(10000));

        assertThat(result).isEqualTo("execute-shell-replacement");
    }

    @Test
    void shouldAppendExitCodeMeaningToExecuteShellCompatibilityOutput()
            throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String result =
                skill.execute(
                        "test -f /definitely-not-a-jimuqu-file",
                        Integer.valueOf(10000));

        assertThat(result)
                .contains("退出码说明")
                .contains("Condition evaluated to false");
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
    void shouldRejectLongLivedServerCommandsOnForegroundTerminalLikeJimuqu() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "pnpm dev",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                null,
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("success").getBoolean()).isFalse();
        assertThat(result.get("error").getString())
                .contains("长驻服务")
                .contains("受管的后台进程能力");
    }

    @Test
    void shouldRejectShellLevelBackgroundWrappersOnForegroundTerminalLikeJimuqu()
            throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "nohup pnpm dev > app.log 2>&1 &",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                null,
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(result.get("error").getString())
                .contains("nohup")
                .contains("受管的后台进程能力");
    }

    @Test
    void shouldRejectAmpersandBackgroundingOnForegroundTerminalLikeJimuqu()
            throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                "sleep 30 &",
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                null,
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(result.get("error").getString())
                .contains("&")
                .contains("受管的后台进程能力");
    }

    @Test
    void shouldAllowHelpVariantForLongLivedForegroundCommandLikeJimuqu() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                safeHelpVariantCommand(),
                                Boolean.FALSE,
                                Integer.valueOf(5),
                                null,
                                Boolean.FALSE));

        assertThat(result.get("exit_code").getInt()).isEqualTo(0);
        assertThat(result.get("error").isNull()).isTrue();
        assertThat(result.get("output").getString()).contains("usage");
    }

    @Test
    void shouldAllowLongLivedCommandWhenTerminalBackgroundIsManaged() throws Exception {
        AppConfig config = new AppConfig();
        ProcessRegistry registry = new ProcessRegistry();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config, null, registry);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                javaSleepWithLongLivedCommentCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(9999),
                                workdir,
                                Boolean.TRUE));

        assertThat(result.get("success").getBoolean()).isTrue();
        assertThat(result.get("background").getBoolean()).isTrue();
        assertThat(result.get("status").getString()).isEqualTo("running");
        assertThat(registry.stop(result.get("session_id").getString())).isTrue();
    }

    @Test
    void shouldApplySudoRewriteBeforeStartingManagedBackgroundProcessLikeJimuqu()
            throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("testpass");
        ProcessRegistry registry = new ProcessRegistry();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        Path fakeSudo = createFakeSudoCommand(workdir);
        fakeSudo.toFile().setExecutable(true);
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config, null, registry);
        String command =
                isWindows()
                        ? "set \"PATH=" + workdir + ";%PATH%\" && sudo cmd /c exit 0"
                        : "PATH=" + workdir + ":$PATH sudo sh -c 'exit 0'";

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                command,
                                Boolean.TRUE,
                                Integer.valueOf(5),
                                workdir,
                                Boolean.FALSE));

        String sessionId = result.get("session_id").getString();
        ProcessRegistry.ManagedProcess managed = registry.get(sessionId);

        assertThat(result.get("success").getBoolean()).isTrue();
        assertThat(result.get("command").getString())
                .contains("sudo -S -p ''")
                .doesNotContain("testpass");
        assertThat(managed.getCommand()).contains("sudo -S -p ''").doesNotContain("testpass");
        assertThat(registry.waitFor(sessionId, 5000L)).isTrue();
        assertThat(managed.getExitCode()).isEqualTo(0);
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
    void shouldPreservePartialForegroundOutputOnTimeoutLikeJimuqu() throws Exception {
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
    void shouldPreservePartialTerminalJsonOutputOnTimeoutLikeJimuqu() throws Exception {
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
    void shouldCloseForegroundStdinWhenNoInputIsProvidedLikeJimuquPipedStdinGuardrail()
            throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String result = skill.execute(waitForStdinEofCommand(), Integer.valueOf(10000));

        assertThat(result).contains("stdin-closed");
    }

    @Test
    void shouldApplyJimuquForegroundOutputByteLimit() throws Exception {
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

    @Test
    void shouldPreserveSafePathForForegroundTerminalEnvLikeJimuqu() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String result = skill.terminal(printEnvCommand("PATH"), false, 5, null, false);
        ONode parsed = ONode.ofJson(result);

        assertThat(parsed.get("output").getString()).isNotBlank();
        assertThat(parsed.get("output").getString()).doesNotContain("missing");
    }

    @Test
    void shouldAppendSudoFailureHintForExecuteLikeJimuquMessagingGuardrail() throws Exception {
        AppConfig config = new AppConfig();
        SolonClawShellSkill skill =
                new SolonClawShellSkill(Files.createTempDirectory("jimuqu-shell").toString(), config);

        String result = skill.execute(echoSudoFailureCommand(), Integer.valueOf(10000));

        assertThat(result)
                .contains("sudo: a password is required")
                .contains("SUDO_PASSWORD")
                .contains("terminal.sudoPassword");
    }

    @Test
    void shouldAppendSudoFailureHintForTerminalJsonOutputLikeJimuqu() throws Exception {
        AppConfig config = new AppConfig();
        String workdir = Files.createTempDirectory("jimuqu-shell").toString();
        SolonClawShellSkill skill = new SolonClawShellSkill(workdir, config);

        ONode result =
                ONode.ofJson(
                        skill.terminal(
                                echoNoTtySudoFailureCommand(),
                                Boolean.FALSE,
                                Integer.valueOf(10),
                                workdir,
                                Boolean.FALSE));

        assertThat(result.get("output").getString())
                .contains("sudo: no tty present")
                .contains("SUDO_PASSWORD")
                .contains("terminal.sudoPassword");
    }

    private String javaSleepCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "ping -n 30 127.0.0.1 > nul";
        }
        return "sleep 30";
    }

    private Path createFakeSudoCommand(String workdir) throws Exception {
        Path fakeSudo = Paths.get(workdir).resolve(isWindows() ? "sudo.bat" : "sudo");
        if (isWindows()) {
            Files.write(
                    fakeSudo,
                    Arrays.asList(
                            "@echo off",
                            "set /p _sudo_password=",
                            "if not \"%_sudo_password%\"==\"testpass\" exit /b 7",
                            ":parse",
                            "if \"%~1\"==\"-S\" shift & goto parse",
                            "if \"%~1\"==\"-p\" shift & shift & goto parse",
                            "if \"%~1\"==\"--\" shift & goto run",
                            "goto run",
                            ":run",
                            "%1 %2 %3 %4 %5 %6 %7 %8 %9"));
        } else {
            Files.write(
                    fakeSudo,
                    Arrays.asList(
                            "#!/bin/sh",
                            "read -r _sudo_password",
                            "if [ \"$_sudo_password\" != \"testpass\" ]; then exit 7; fi",
                            "while [ \"$#\" -gt 0 ]; do",
                            "  case \"$1\" in",
                            "    -S) shift ;;",
                            "    -p) shift 2 ;;",
                            "    --) shift; break ;;",
                            "    *) break ;;",
                            "  esac",
                            "done",
                            "exec \"$@\""));
        }
        return fakeSudo;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private String watchReadyCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo ready";
        }
        return "printf 'ready\\n'";
    }

    private String printTokenCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo token=secret123";
        }
        return "printf 'token=secret123\\n'";
    }

    private String repeatedReadyCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "powershell -NoProfile -Command \"Write-Output 'ready-1'; Start-Sleep -Milliseconds 100; Write-Output 'ready-2'; Start-Sleep -Milliseconds 500; Write-Output 'ready-3'; Start-Sleep -Milliseconds 100; Write-Output 'ready-4'; Start-Sleep -Milliseconds 100; Write-Output 'done'\"";
        }
        return "printf 'ready-1\\n'; sleep 0.1; printf 'ready-2\\n'; sleep 0.5; printf 'ready-3\\n'; sleep 0.1; printf 'ready-4\\n'; sleep 0.1; printf 'done\\n'";
    }

    private List<String> eventTypes(List<Map<String, Object>> events) {
        List<String> types = new java.util.ArrayList<String>();
        for (Map<String, Object> event : events) {
            types.add(String.valueOf(event.get("type")));
        }
        return types;
    }

    private String javaSleepWithLongLivedCommentCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "ping -n 30 127.0.0.1 > nul && rem pnpm dev";
        }
        return "sleep 30 # pnpm dev";
    }

    private String safeHelpVariantCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo usage && rem pnpm dev --help";
        }
        return "printf 'usage\\n' # pnpm dev --help";
    }

    private String partialOutputThenSleepCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo hello from timeout && ping -n 30 127.0.0.1 > nul";
        }
        return "printf 'hello from timeout\\n'; sleep 30";
    }

    private String javaLongOutputCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "@powershell -NoProfile -Command \"$s='head-' + ('A' * 600) + 'middle-' + ('B' * 600) + 'tail-'; [Console]::Write($s)\"";
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

    private String echoSudoFailureCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo sudo: a password is required";
        }
        return "printf '%s\\n' 'sudo: a password is required'";
    }

    private String echoNoTtySudoFailureCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo sudo: no tty present and no askpass program specified";
        }
        return "printf '%s\\n' 'sudo: no tty present and no askpass program specified'";
    }

    private String printEnvCommand(String name) {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "@echo off\r\npowershell -NoProfile -Command \"$v=[Environment]::GetEnvironmentVariable('"
                    + name
                    + "'); if ([string]::IsNullOrEmpty($v)) { 'missing' } else { $v }\"";
        }
        return "if [ -z \"${"
                + name
                + "+x}\" ]; then printf 'missing\\n'; else printf '%s\\n' \"$"
                + name
                + "\"; fi";
    }

    private String printWorkingDirectoryCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "cd";
        }
        return "pwd -P";
    }

    private String lastOutputLine(String output) {
        String[] lines = output.replace("\r\n", "\n").trim().split("\n");
        return lines[lines.length - 1].trim();
    }

    private void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(
                        item -> {
                            try {
                                Files.deleteIfExists(item);
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
    }

    private String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private String paramDescription(Method method, String name) {
        for (Parameter parameter : method.getParameters()) {
            Param annotation = parameter.getAnnotation(Param.class);
            if (annotation == null) {
                continue;
            }
            if (name.equals(annotation.name()) || name.equals(annotation.value())) {
                return annotation.description();
            }
        }
        return "";
    }
}

