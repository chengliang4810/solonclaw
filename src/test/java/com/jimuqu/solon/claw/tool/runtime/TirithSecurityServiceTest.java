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
    void shouldMapTirithExitCodesLikeHermes() throws Exception {
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
    void shouldKeepExitCodeDecisionWhenTirithJsonIsInvalidLikeHermes() throws Exception {
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
    void shouldApplyHermesTirithFindingAndSummaryCaps() throws Exception {
        TirithSecurityService.ScanResult result = scan(script(printJson(manyFindingsJson(60)), 2));

        assertThat(result.getAction()).isEqualTo("warn");
        assertThat(result.getFindings()).hasSize(50);
        assertThat(result.getFindings().get(0).getRuleId()).isEqualTo("rule_0");
        assertThat(result.getFindings().get(49).getRuleId()).isEqualTo("rule_49");
        assertThat(result.getSummary()).hasSize(500);
    }

    @Test
    void shouldFailOpenOrClosedForUnknownTirithExitCodeLikeHermes() throws Exception {
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

    private TirithSecurityService.ScanResult scan(Path binary) {
        return new TirithSecurityService(config(binary)).checkCommandSecurity("echo hello");
    }

    private AppConfig config(Path binary) {
        AppConfig config = new AppConfig();
        config.getSecurity().setTirithEnabled(true);
        config.getSecurity().setTirithPath(binary.toString());
        config.getSecurity().setTirithTimeoutSeconds(5);
        config.getSecurity().setTirithFailOpen(true);
        return config;
    }

    private Path script(String body, int exitCode) throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-tirith");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path file = dir.resolve(windows ? "tirith.cmd" : "tirith");
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
        return "";
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
