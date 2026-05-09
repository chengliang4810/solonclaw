package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TirithSecurityServiceTest {
    @Test
    void shouldMapTirithExitCodesLikeJimuqu() throws Exception {
        TirithSecurityService.ScanResult allow =
                scan(script("printf '%s\\n' '{\"findings\":[],\"summary\":\"\"}'", 0));
        TirithSecurityService.ScanResult block =
                scan(
                        script(
                                "printf '%s\\n' '{\"findings\":[{\"rule_id\":\"homograph_url\",\"severity\":\"high\",\"title\":\"Homograph\",\"description\":\"unicode host\"}],\"summary\":\"homograph detected\"}'",
                                1));
        TirithSecurityService.ScanResult warn =
                scan(
                        script(
                                "printf '%s\\n' '{\"findings\":[{\"rule_id\":\"shortened_url\"}],\"summary\":\"shortened URL\"}'",
                                2));

        assertThat(allow.getAction()).isEqualTo("allow");
        assertThat(allow.requiresApproval()).isFalse();
        assertThat(block.getAction()).isEqualTo("block");
        assertThat(block.requiresApproval()).isTrue();
        assertThat(block.getFindings()).hasSize(1);
        assertThat(block.getFindings().get(0).getRuleId()).isEqualTo("homograph_url");
        assertThat(block.getSummary()).isEqualTo("homograph detected");
        assertThat(warn.getAction()).isEqualTo("warn");
        assertThat(warn.requiresApproval()).isTrue();
        assertThat(warn.getFindings().get(0).getRuleId()).isEqualTo("shortened_url");
    }

    @Test
    void shouldKeepExitCodeDecisionWhenTirithJsonIsInvalidLikeJimuqu() throws Exception {
        TirithSecurityService.ScanResult block = scan(script("printf '%s\\n' 'NOT JSON'", 1));
        TirithSecurityService.ScanResult warn = scan(script("printf '%s\\n' '{broken'", 2));
        TirithSecurityService.ScanResult allow = scan(script("printf '%s\\n' 'NOT JSON'", 0));

        assertThat(block.getAction()).isEqualTo("block");
        assertThat(block.getSummary()).contains("details unavailable");
        assertThat(warn.getAction()).isEqualTo("warn");
        assertThat(warn.getSummary()).contains("details unavailable");
        assertThat(allow.getAction()).isEqualTo("allow");
        assertThat(allow.getSummary()).isEmpty();
    }

    @Test
    void shouldApplyJimuquTirithFindingAndSummaryCaps() throws Exception {
        TirithSecurityService.ScanResult result = scan(script(printJson(manyFindingsJson(60)), 2));

        assertThat(result.getAction()).isEqualTo("warn");
        assertThat(result.getFindings()).hasSize(50);
        assertThat(result.getFindings().get(0).getRuleId()).isEqualTo("rule_0");
        assertThat(result.getFindings().get(49).getRuleId()).isEqualTo("rule_49");
        assertThat(result.getSummary()).hasSize(500);
    }

    @Test
    void shouldFailOpenOrClosedForUnknownTirithExitCodeLikeJimuqu() throws Exception {
        Path binary = script("", 99);
        AppConfig openConfig = config(binary);
        openConfig.getSecurity().setTirithFailOpen(true);
        AppConfig closedConfig = config(binary);
        closedConfig.getSecurity().setTirithFailOpen(false);

        TirithSecurityService.ScanResult open =
                new TirithSecurityService(openConfig).checkCommandSecurity("echo hello");
        TirithSecurityService.ScanResult closed =
                new TirithSecurityService(closedConfig).checkCommandSecurity("echo hello");

        assertThat(open.getAction()).isEqualTo("allow");
        assertThat(open.getSummary()).contains("exit code 99");
        assertThat(closed.getAction()).isEqualTo("block");
        assertThat(closed.getSummary()).contains("exit code 99").contains("fail-closed");
    }

    @Test
    void shouldUseCappedStderrSummaryWhenTirithFindingsAreMissing() throws Exception {
        TirithSecurityService.ScanResult result =
                scan(script("printf '%s' '" + repeat("x", 700) + "' >&2", 1));

        assertThat(result.getAction()).isEqualTo("block");
        assertThat(result.getSummary()).hasSize(500);
    }

    @Test
    void shouldRedactSecretsFromTirithSummaryFindingsAndStderr() throws Exception {
        String token = "sk-1234567890abcdef";
        TirithSecurityService.ScanResult json =
                scan(
                        script(
                                "printf '%s\\n' '{\"findings\":[{\"rule_id\":\"rule_"
                                        + token
                                        + "\",\"severity\":\"high_"
                                        + token
                                        + "\",\"title\":\"blocked "
                                        + token
                                        + "\",\"description\":\"description "
                                        + token
                                        + "\"}],\"summary\":\"summary "
                                        + token
                                        + "\"}'",
                                1));
        TirithSecurityService.ScanResult stderr =
                scan(script("printf '%s' 'stderr " + token + "' >&2", 1));

        assertThat(json.getSummary()).contains("***").doesNotContain(token);
        assertThat(json.getFindings().get(0).getRuleId()).doesNotContain(token);
        assertThat(json.getFindings().get(0).getSeverity()).doesNotContain(token);
        assertThat(json.getFindings().get(0).getTitle()).doesNotContain(token);
        assertThat(json.getFindings().get(0).getDescription()).doesNotContain(token);
        assertThat(stderr.getSummary()).contains("***").doesNotContain(token);
    }

    @Test
    void shouldAllowImmediatelyWhenTirithIsDisabled() {
        AppConfig config = new AppConfig();
        config.getSecurity().setTirithEnabled(false);
        config.getSecurity().setTirithPath("Z:\\missing\\tirith.exe");

        TirithSecurityService.ScanResult result =
                new TirithSecurityService(config).checkCommandSecurity("rm -rf /");

        assertThat(result.getAction()).isEqualTo("allow");
        assertThat(result.requiresApproval()).isFalse();
    }

    @Test
    void shouldApplyFailOpenAndFailClosedWhenTirithCannotStart() {
        String token = "sk-1234567890abcdef";
        String missingPath = missingAbsolutePath(token);
        AppConfig openConfig = config(missingPath);
        openConfig.getSecurity().setTirithFailOpen(true);
        AppConfig closedConfig = config(missingPath);
        closedConfig.getSecurity().setTirithFailOpen(false);

        TirithSecurityService.ScanResult open =
                new TirithSecurityService(openConfig).checkCommandSecurity("echo hello");
        TirithSecurityService.ScanResult closed =
                new TirithSecurityService(closedConfig).checkCommandSecurity("echo hello");

        assertThat(open.getAction()).isEqualTo("allow");
        assertThat(open.getSummary()).contains("tirith unavailable");
        assertThat(open.getSummary()).contains("***").doesNotContain(token);
        assertThat(closed.getAction()).isEqualTo("block");
        assertThat(closed.getSummary()).contains("fail-closed");
        assertThat(closed.getSummary()).contains("***").doesNotContain(token);
    }

    @Test
    void shouldDiagnoseMissingTirithPathWithoutExecutingItAndRedactSecrets() {
        String token = "sk-1234567890abcdef";
        AppConfig config = config(missingAbsolutePath(token));
        config.getSecurity().setTirithFailOpen(false);

        TirithSecurityService.Diagnostic diagnostic =
                new TirithSecurityService(config).diagnose();

        assertThat(diagnostic.isEnabled()).isTrue();
        assertThat(diagnostic.isConfigured()).isTrue();
        assertThat(diagnostic.isAvailable()).isFalse();
        assertThat(diagnostic.isFailOpen()).isFalse();
        assertThat(diagnostic.getSummary()).contains("unavailable").contains("fail-closed");
        assertThat(diagnostic.getConfiguredPath()).contains("***").doesNotContain(token);
        assertThat(diagnostic.getResolvedPath()).contains("***").doesNotContain(token);
        assertThat(String.valueOf(diagnostic.toMap())).contains("***").doesNotContain(token);
    }

    @Test
    void shouldDiagnoseAvailableExplicitTirithPath() throws Exception {
        Path binary = script("printf '%s\\n' '{\"findings\":[],\"summary\":\"\"}'", 0);

        TirithSecurityService.Diagnostic diagnostic =
                new TirithSecurityService(config(binary)).diagnose();

        assertThat(diagnostic.isEnabled()).isTrue();
        assertThat(diagnostic.isAvailable()).isTrue();
        assertThat(diagnostic.getResolvedPath()).contains(binary.getFileName().toString());
        assertThat(diagnostic.getSummary()).contains("available");
    }

    @Test
    void shouldApplyFailOpenAndFailClosedWhenTirithTimesOut() throws Exception {
        Path binary = script(sleepBody(3), 0);
        AppConfig openConfig = config(binary);
        openConfig.getSecurity().setTirithTimeoutSeconds(1);
        openConfig.getSecurity().setTirithFailOpen(true);
        AppConfig closedConfig = config(binary);
        closedConfig.getSecurity().setTirithTimeoutSeconds(1);
        closedConfig.getSecurity().setTirithFailOpen(false);

        TirithSecurityService.ScanResult open =
                new TirithSecurityService(openConfig).checkCommandSecurity("echo hello");
        TirithSecurityService.ScanResult closed =
                new TirithSecurityService(closedConfig).checkCommandSecurity("echo hello");

        assertThat(open.getAction()).isEqualTo("allow");
        assertThat(open.getSummary()).contains("timed out");
        assertThat(closed.getAction()).isEqualTo("block");
        assertThat(closed.getSummary()).contains("timed out").contains("fail-closed");
    }

    @Test
    void shouldExpandTildeInConfiguredTirithPath() throws Exception {
        String oldHome = System.getProperty("user.home");
        Path fakeHome = Files.createTempDirectory("jimuqu-tirith-home");
        Path binary = scriptIn(fakeHome, "tirith", "printf '%s\\n' '{\"findings\":[],\"summary\":\"tilde ok\"}'", 0);
        try {
            System.setProperty("user.home", fakeHome.toString());
            TirithSecurityService.ScanResult result =
                    new TirithSecurityService(config("~/" + binary.getFileName().toString()))
                            .checkCommandSecurity("echo hello");

            assertThat(result.getAction()).isEqualTo("allow");
            assertThat(result.getSummary()).isEqualTo("tilde ok");
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void shouldSelectShellForTerminalToolCommands() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-tirith-argv");
        Path argsFile = dir.resolve("args.txt");
        Path binary = captureArgsScript(dir, argsFile);
        TirithSecurityService service = new TirithSecurityService(config(binary));

        service.checkCommandSecurityForTool("execute_shell", "powershell -NoProfile -Command Get-Process");
        assertThat(Files.readAllBytes(argsFile))
                .asString(StandardCharsets.UTF_8)
                .contains("--shell", "powershell");

        service.checkCommandSecurityForTool("terminal", "cmd /c dir");
        assertThat(Files.readAllBytes(argsFile))
                .asString(StandardCharsets.UTF_8)
                .contains("--shell", "cmd");

        service.checkCommandSecurityForTool("executeShell", "echo hello");
        assertThat(Files.readAllBytes(argsFile))
                .asString(StandardCharsets.UTF_8)
                .contains("--shell", "posix");

        service.checkCommandSecurityForTool("config_get", "powershell -NoProfile -Command Get-Process");
        assertThat(Files.readAllBytes(argsFile))
                .asString(StandardCharsets.UTF_8)
                .contains("--shell", "posix");
    }

    private TirithSecurityService.ScanResult scan(Path binary) {
        return new TirithSecurityService(config(binary)).checkCommandSecurity("echo hello");
    }

    private AppConfig config(Path binary) {
        return config(binary.toString());
    }

    private AppConfig config(String binary) {
        AppConfig config = new AppConfig();
        config.getSecurity().setTirithEnabled(true);
        config.getSecurity().setTirithPath(binary);
        config.getSecurity().setTirithTimeoutSeconds(5);
        config.getSecurity().setTirithFailOpen(true);
        return config;
    }

    private Path script(String body, int exitCode) throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-tirith");
        return scriptIn(dir, "tirith", body, exitCode);
    }

    private Path scriptIn(Path dir, String basename, String body, int exitCode) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path file = dir.resolve(windows ? basename + ".cmd" : basename);
        String content;
        if (windows) {
            content = "@echo off\r\n" + windowsBody(body) + "\r\nexit /b " + exitCode + "\r\n";
        } else {
            content = "#!/bin/sh\n" + body + "\nexit " + exitCode + "\n";
        }
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        file.toFile().setExecutable(true);
        return file;
    }

    private Path captureArgsScript(Path dir, Path argsFile) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path file = dir.resolve(windows ? "tirith.cmd" : "tirith");
        String argsPath = argsFile.toAbsolutePath().toString();
        String content;
        if (windows) {
            content =
                    "@echo off\r\n"
                            + "echo %* > \""
                            + argsPath
                            + "\"\r\n"
                            + "echo {\"findings\":[],\"summary\":\"\"}\r\n"
                            + "exit /b 0\r\n";
        } else {
            content =
                    "#!/bin/sh\n"
                            + "printf '%s\\n' \"$*\" > '"
                            + argsPath
                            + "'\n"
                            + "printf '%s\\n' '{\"findings\":[],\"summary\":\"\"}'\n"
                            + "exit 0\n";
        }
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        file.toFile().setExecutable(true);
        return file;
    }

    private String windowsBody(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("printf '%s\\n' '") && trimmed.endsWith("'")) {
            String value = trimmed.substring("printf '%s\\n' '".length(), trimmed.length() - 1);
            return "echo " + value;
        }
        if (trimmed.startsWith("printf '%s' '") && trimmed.endsWith("' >&2")) {
            String value = trimmed.substring("printf '%s' '".length(), trimmed.length() - "' >&2".length());
            return "echo " + value + " 1>&2";
        }
        if (trimmed.startsWith("powershell ")) {
            return trimmed;
        }
        return "";
    }

    private String sleepBody(int seconds) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (windows) {
            return "powershell -NoProfile -Command \"Start-Sleep -Seconds " + seconds + "\"";
        }
        return "sleep " + seconds;
    }

    private String missingAbsolutePath() {
        return missingAbsolutePath(null);
    }

    private String missingAbsolutePath(String suffix) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String name = suffix == null ? "tirith" : "tirith-" + suffix;
        if (windows) {
            return "Z:\\jimuqu-missing-tirith\\" + name + ".exe";
        }
        return "/tmp/jimuqu-missing-tirith/" + name;
    }

    private String printJson(String json) {
        return "printf '%s\\n' '" + json + "'";
    }

    private String manyFindingsJson(int count) {
        List<String> findings = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            findings.add("{\"rule_id\":\"rule_" + i + "\"}");
        }
        return "{\"findings\":["
                + join(findings)
                + "],\"summary\":\""
                + repeat("x", 700)
                + "\"}";
    }

    private String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
