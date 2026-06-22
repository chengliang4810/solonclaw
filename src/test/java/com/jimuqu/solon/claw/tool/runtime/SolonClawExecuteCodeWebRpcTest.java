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
        env.appConfig.getSecurity().setFileGuardrailMode("strict");
        env.appConfig.getSecurity().setUrlGuardrailMode("strict");

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
                .contains("websearch")
                .contains("webfetch")
                .contains("read_file")
                .contains("write_file")
                .contains("search_files")
                .contains("patch")
                .contains("terminal")
                .contains("providerBlocklistOverridesPassthrough")
                .doesNotContain("TENOR_API_KEY");
    }

    @Test
    void shouldExposeJimuquWebSearchAndFetchInsideExecuteCode() throws Exception {
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
                                "from solonclaw_tools import websearch, webfetch\n"
                                        + "search = websearch('solon ai', limit=2)\n"
                                        + "print(search['data']['web'][0]['url'])\n"
                                        + "print(search['data']['web'][0]['title'])\n"
                                        + "extract = webfetch('https://example.com/docs')\n"
                                        + "print(extract['title'])\n"
                                        + "print(extract['content'])\n",
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
    void shouldExposeCurrentHelpersInsideExecuteCode() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        java.nio.file.Path workspace =
                new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        java.nio.file.Files.write(
                workspace.resolve("current-helper-source.txt"),
                java.util.Arrays.asList("current helper input"),
                java.nio.charset.StandardCharsets.UTF_8);
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
                                "from solonclaw_tools import file_read, file_write, websearch, webfetch\n"
                                        + "print(file_read('current-helper-source.txt')['content'].splitlines()[0])\n"
                                        + "print(file_write('current-helper-output.txt', 'current helper output\\n')['status'])\n"
                                        + "print(websearch('solon ai')['data']['web'][0]['title'])\n"
                                        + "print(webfetch('https://example.com/docs')['title'])\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(4);
        assertThat(result.get("output").getString())
                .contains("current helper input")
                .contains("success")
                .contains("Solon AI")
                .contains("Example Docs");
    }

    @Test
    void shouldReturnWebfetchErrors() throws Exception {
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
                                "from solonclaw_tools import webfetch\n"
                                        + "extract = webfetch('https://example.com/fail')\n"
                                        + "print(extract['url'])\n"
                                        + "print(extract['error'])\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(1);
        assertThat(result.get("output").getString())
                .contains("https://example.com/fail")
                .contains("blocked by test");
    }

    @Test
    void shouldBlockReturnedWebfetchContentUrlsInsideExecuteCode() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(java.util.Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected java.net.InetAddress[] resolveHost(String host) throws Exception {
                        return new java.net.InetAddress[] {
                            java.net.InetAddress.getByName("93.184.216.34")
                        };
                    }
                };
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        policy,
                        env.appConfig,
                        new FakeWebsearchTool(),
                        new SolonClawWebTools.SafeWebfetchTool(
                                policy, new UnsafeReturnedContentWebfetchTool()));

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import webfetch\n"
                                        + "extract = webfetch('https://example.com/docs')\n"
                                        + "print(extract['error'])\n"
                                        + "print(extract['content'])\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(1);
        assertThat(result.get("output").getString())
                .contains("URL 安全策略")
                .contains("blocked.example")
                .doesNotContain("secret123");
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
                                "from solonclaw_tools import search_files, webfetch, _call\n"
                                        + "secret = 'ghp_' + '1234567890abcdef'\n"
                                        + "print(search_files('x', path='missing-token-' + secret)['error'])\n"
                                        + "print(_call('secret_tool_' + secret, {})['error'])\n"
                                        + "extract = webfetch('https://example.com/fail?token=' + secret)\n"
                                        + "print(extract['error'])\n",
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

    @Test
    void shouldRedactExecuteCodeRpcSearchPathErrors() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        java.io.File runtimeHome =
                new java.io.File(env.appConfig.getRuntime().getHome()).getCanonicalFile();
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
                                "from solonclaw_tools import search_files\n"
                                        + "missing = search_files('x', path='missing-token-ghp_' + 'rpcpath12345')\n"
                                        + "escape = search_files('x', path='../escape-token-ghp_' + 'rpcescape12345')\n"
                                        + "print(missing['error'])\n"
                                        + "print(escape['error'])\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("output").getString())
                .contains("path escapes workspace")
                .doesNotContain(runtimeHome.getParent())
                .doesNotContain("ghp_rpcpath12345")
                .doesNotContain("ghp_rpcescape12345");
    }

    @Test
    void shouldRedactExecuteCodeRpcToolSuccessResults() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        java.nio.file.Path workspace =
                new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        java.nio.file.Files.write(
                workspace.resolve("rpc-ghp_filepath12345.txt"),
                java.util.Arrays.asList("line token=ghp_rpcfile12345"),
                java.nio.charset.StandardCharsets.UTF_8);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig,
                        new SecretWebsearchTool(),
                        new SecretWebfetchTool());

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import search_files, websearch, webfetch\n"
                                        + "import json\n"
                                        + "search = json.dumps(search_files('rpcfile', path='.'), ensure_ascii=False)\n"
                                        + "web = json.dumps(websearch('secret'), ensure_ascii=False)\n"
                                        + "url_secret = 'ghp_' + 'rpcurlinput12345'\n"
                                        + "extract = json.dumps(webfetch('https://example.com/docs?token=' + url_secret), ensure_ascii=False)\n"
                                        + "for raw in ['ghp_' + 'filepath12345', 'ghp_' + 'rpcfile12345', 'ghp_' + 'rpcsearchurl12345', 'ghp_' + 'rpcsearchtitle12345', 'ghp_' + 'rpcsearchdesc12345', url_secret, 'ghp_' + 'rpcextracttitle12345', 'ghp_' + 'rpcextractcontent12345']:\n"
                                        + "    assert raw not in search + web + extract\n"
                                        + "print(search)\n"
                                        + "print(web)\n"
                                        + "print(extract)\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(3);
        assertThat(result.get("output").getString())
                .contains("token=***")
                .contains("ghp_***")
                .doesNotContain("ghp_filepath12345")
                .doesNotContain("ghp_rpcfile12345")
                .doesNotContain("ghp_rpcsearchurl12345")
                .doesNotContain("ghp_rpcsearchtitle12345")
                .doesNotContain("ghp_rpcsearchdesc12345")
                .doesNotContain("ghp_rpcurlinput12345")
                .doesNotContain("ghp_rpcextracttitle12345")
                .doesNotContain("ghp_rpcextractcontent12345");
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

    private static class SecretWebsearchTool extends SolonClawWebTools.SafeWebsearchTool {
        SecretWebsearchTool() {
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
            item.put("url", "https://example.com/solon?token=ghp_rpcsearchurl12345");
            item.put("title", "Solon ghp_rpcsearchtitle12345");
            item.put("description", "description token=ghp_rpcsearchdesc12345");
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("web", java.util.Collections.singletonList(item));
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("data", data);
            return new Document(ONode.serialize(root)).title("Web search: " + query);
        }
    }

    private static class SecretWebfetchTool extends SolonClawWebTools.SafeWebfetchTool {
        SecretWebfetchTool() {
            super(null, null);
        }

        @Override
        public Document webfetch(String url, String format, Integer timeoutSeconds) {
            return new Document("Fetched ghp_rpcextractcontent12345")
                    .title("Docs ghp_rpcextracttitle12345")
                    .url(url);
        }
    }

    private static class UnsafeReturnedContentWebfetchTool
            extends org.noear.solon.ai.talents.web.WebfetchTalent {
        @Override
        public String webfetch(String url, String format, Integer timeoutSeconds) {
            return "{\"download\":\"https://blocked.example/files/app.jar?token=secret123\"}";
        }
    }
}
