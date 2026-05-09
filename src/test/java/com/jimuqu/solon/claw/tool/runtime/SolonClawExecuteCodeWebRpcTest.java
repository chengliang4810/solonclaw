package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.rag.Document;

public class SolonClawExecuteCodeWebRpcTest {
    @Test
    void shouldExposeCodeExecutionPolicySummaryWithoutSecrets() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTerminal().setEnvPassthrough(java.util.Arrays.asList("TENOR_API_KEY"));

        Map<String, Object> summary =
                SolonClawCodeExecutionSkills.codeExecutionPolicySummary(env.appConfig);

        assertThat(summary.get("executeCodeSupported")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("executePythonSupported")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("executeJsSupported")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("scriptPreflightPathPolicy")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("scriptPreflightUrlPolicy")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("dangerousCommandRulesApplied")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("managedFileToolPathLiteralsIgnoredForPreflight"))
                .isEqualTo(Boolean.TRUE);
        assertThat(summary.get("stagingDirectoryPerRun")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("sandboxEnvironmentSanitized")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("pythonPathPrependsStaging")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("pythonIoEncodingUtf8")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("pythonDontWriteBytecode")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("rpcToolBridgeEnabled")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("defaultTimeoutSeconds")).isEqualTo(Integer.valueOf(300));
        assertThat(summary.get("stdoutLimitChars"))
                .isEqualTo(Integer.valueOf(env.appConfig.getTask().getToolOutputInlineLimit()));
        assertThat(summary.get("stderrLimitChars")).isEqualTo(Integer.valueOf(10000));
        assertThat(summary.get("timeoutKillsProcess")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("stagingCleanup")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("outputRedacted")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(summary))
                .contains("web_search")
                .contains("web_extract")
                .contains("read_file")
                .contains("write_file")
                .contains("search_files")
                .contains("patch")
                .contains("terminal")
                .contains("providerBlocklistOverridesPassthrough")
                .doesNotContain("TENOR_API_KEY");
    }

    @Test
    void shouldExposeJimuquWebSearchAndExtractInsideExecuteCode() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig,
                        new FakeWebsearchTool(),
                        new FakeWebfetchTool());

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import web_search, web_extract\n"
                                        + "search = web_search('solon ai', limit=2)\n"
                                        + "print(search['data']['web'][0]['url'])\n"
                                        + "print(search['data']['web'][0]['title'])\n"
                                        + "extract = web_extract(['https://example.com/docs'])\n"
                                        + "print(extract['results'][0]['title'])\n"
                                        + "print(extract['results'][0]['content'])\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(2);
        assertThat(result.get("output").getString())
                .contains("https://example.com/solon")
                .contains("Solon AI")
                .contains("Example Docs")
                .contains("Fetched markdown");
    }

    @Test
    void shouldReturnWebExtractErrorsPerUrl() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig,
                        new FakeWebsearchTool(),
                        new FakeWebfetchTool());

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import web_extract\n"
                                        + "extract = web_extract(['https://example.com/fail'])\n"
                                        + "print(extract['results'][0]['url'])\n"
                                        + "print(extract['results'][0]['error'])\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(1);
        assertThat(result.get("output").getString())
                .contains("https://example.com/fail")
                .contains("blocked by test");
    }

    @Test
    void shouldRedactExecuteCodeRpcToolErrors() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig,
                        new FakeWebsearchTool(),
                        new FakeWebfetchTool());

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import search_files, web_extract, _call\n"
                                        + "secret = 'ghp_' + '1234567890abcdef'\n"
                                        + "print(search_files('x', path='missing-token-' + secret)['error'])\n"
                                        + "print(_call('secret_tool_' + secret, {})['error'])\n"
                                        + "extract = web_extract(['https://example.com/fail?token=' + secret])\n"
                                        + "print(extract['results'][0]['error'])\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(3);
        assertThat(result.get("output").getString())
                .contains("missing-token-ghp_***")
                .contains("Tool 'secret_tool_ghp_***'")
                .contains("blocked by test")
                .doesNotContain("ghp_1234567890abcdef")
                .doesNotContain("token=ghp_1234567890abcdef");
    }

    private static boolean commandExists(String command) {
        try {
            Process process =
                    new ProcessBuilder(
                                    System.getProperty("os.name", "").toLowerCase().contains("win")
                                            ? "where"
                                            : "which",
                                    command)
                            .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static class FakeWebsearchTool extends SolonClawWebTools.SafeWebsearchTool {
        FakeWebsearchTool() {
            super(null, null);
        }

        @Override
        public Document websearch(
                String query,
                Integer numResults,
                String livecrawl,
                String type,
                Integer contextMaxCharacters) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("url", "https://example.com/solon");
            item.put("title", "Solon AI");
            item.put("description", "Solon AI search result");
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("web", java.util.Collections.singletonList(item));
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("data", data);
            return new Document(ONode.serialize(root)).title("Web search: " + query);
        }
    }

    private static class FakeWebfetchTool extends SolonClawWebTools.SafeWebfetchTool {
        FakeWebfetchTool() {
            super(null, null);
        }

        @Override
        public Document webfetch(String url, String format, Integer timeoutSeconds) {
            if (url.contains("/fail")) {
                throw new IllegalArgumentException("blocked by test: " + url);
            }
            return new Document("Fetched markdown").title("Example Docs").url(url);
        }
    }
}

