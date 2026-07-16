package com.jimuqu.solon.claw;

import static com.jimuqu.solon.claw.support.TestToolSupport.createDirectoryLink;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jimuqu.solon.claw.provider.WebSearchProvider;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DefaultToolRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SolonClawCodeExecutionSkills;
import com.jimuqu.solon.claw.tool.runtime.SolonClawFileReadWriteSkill;
import com.jimuqu.solon.claw.tool.runtime.SolonClawFileStateTracker;
import com.jimuqu.solon.claw.tool.runtime.SolonClawPatchTools;
import com.jimuqu.solon.claw.tool.runtime.SolonClawWebTools;
import com.jimuqu.solon.claw.tool.runtime.ToolCallLoopGuardrailService;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.talents.web.CodeSearchTalent;
import org.noear.solon.ai.talents.web.WebfetchTalent;
import org.noear.solon.ai.talents.web.WebsearchTalent;

/** Web、代码执行和文件工具安全测试，从工具注册测试中拆出以控制单文件行数。 */
class ToolRegistryWebAndCodeToolsTest {
    /** 断言工具结果为当前成功状态，避免测试重新依赖已删除的 success 布尔字段。 */
    private static void assertToolSuccess(ONode result) {
        assertThat(result.get("status").getString()).as(result.toJson()).isNotEqualTo("error");
    }

    /** 断言工具结果为当前错误状态，避免测试重新依赖已删除的 success 布尔字段。 */
    private static void assertToolError(ONode result) {
        assertThat(result.get("status").getString()).isEqualTo("error");
    }

    /** 构造固定公网解析结果的安全策略，避免测试受本机 DNS 或私网判断影响。 */
    private static SecurityPolicyService fixedPublicDnsPolicy(TestEnvironment env) {
        return new SecurityPolicyService(env.appConfig) {
            @Override
            protected InetAddress[] resolveHost(String host) throws Exception {
                return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
            }
        };
    }

    /** 返回不再执行网站硬阻断的固定公网策略。 */
    private static SecurityPolicyService blockedPolicy(TestEnvironment env, String domain) {
        return fixedPublicDnsPolicy(env);
    }

    @Test
    void shouldRedactSecretsFromWebfetchSuccessDocument() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawWebTools.SafeWebfetchTool webfetch =
                new SolonClawWebTools.SafeWebfetchTool(
                        new SecurityPolicyService(env.appConfig),
                        new WebfetchTalent() {
                            @Override
                            public String webfetch(
                                    String url, String format, Integer timeoutSeconds) {
                                return "Fetched api_key=sk-webfetch-secret token=ghp_webfetchcontent12345 doc-ghp_webfetchid12345 title ghp_webfetchtitle12345 https://example.com/docs api_key=sk-webfetch-meta";
                            }
                        });

        Document document =
                webfetch.webfetch("https://example.com/docs", "markdown", Integer.valueOf(1));
        String text = document.toString();

        assertThat(text)
                .contains("api_key=***")
                .contains("token=***")
                .contains("ghp_***")
                .doesNotContain("sk-webfetch-secret")
                .doesNotContain("ghp_webfetchcontent12345")
                .doesNotContain("ghp_webfetchid12345")
                .doesNotContain("ghp_webfetchtitle12345")
                .doesNotContain("sk-webfetch-meta");
    }

    /** webfetch 的非 HTTP(S) 入参必须在 URL 安全策略和网页后端前直接拒绝。 */
    @Test
    void shouldRejectNonHttpWebfetchUrlBeforeSecurityPolicyAndDelegate() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AtomicInteger policyChecks = new AtomicInteger();
        AtomicInteger delegateCalls = new AtomicInteger();
        SolonClawWebTools.SafeWebfetchTool webfetch =
                new SolonClawWebTools.SafeWebfetchTool(
                        new SecurityPolicyService(env.appConfig) {
                            @Override
                            public UrlVerdict checkToolArgs(
                                    String toolName, Map<String, Object> args) {
                                policyChecks.incrementAndGet();
                                return UrlVerdict.allow();
                            }
                        },
                        new WebfetchTalent() {
                            @Override
                            public String webfetch(
                                    String url, String format, Integer timeoutSeconds) {
                                delegateCalls.incrementAndGet();
                                return "unexpected";
                            }
                        });

        for (String invalidUrl :
                Arrays.asList("5", "http://", "ftp://example.com/file", "file:///tmp/a")) {
            assertThatThrownBy(() -> webfetch.webfetch(invalidUrl, "markdown", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("URL 格式错误")
                    .hasMessageNotContaining("SSRF");
        }

        assertThat(policyChecks.get()).isZero();
        assertThat(delegateCalls.get()).isZero();
    }

    /** 返回内容中的展示型 URI 模板不得阻断已完成的公开网页请求。 */
    @Test
    void shouldIgnoreMalformedDisplayUrlsInWebfetchResponse() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawWebTools.SafeWebfetchTool webfetch =
                new SolonClawWebTools.SafeWebfetchTool(
                        new SecurityPolicyService(env.appConfig),
                        new WebfetchTalent() {
                            @Override
                            public String webfetch(
                                    String url, String format, Integer timeoutSeconds) {
                                return "Asset https://uploads.github.com/repos/example/releases/1/assets{?name,label}";
                            }
                        });

        Document document = webfetch.webfetch("https://example.com/docs", "markdown", 10);

        assertThat(document.getContent()).contains("uploads.github.com");
    }

    @Test
    void shouldRedactSecretsFromWebsearchSuccessDocument() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        new SecurityPolicyService(env.appConfig),
                        new WebsearchTalent() {
                            @Override
                            public String websearch(
                                    String query,
                                    Integer numResults,
                                    String livecrawl,
                                    String type,
                                    Integer contextMaxCharacters) {
                                return "Search api_key=sk-websearch-secret token=ghp_websearchcontent12345 doc-ghp_websearchid12345 title ghp_websearchtitle12345 https://example.com/search api_key=sk-websearch-meta";
                            }
                        });

        Document document =
                websearch.websearch(
                        "allowed search",
                        Integer.valueOf(1),
                        "fallback",
                        "auto",
                        Integer.valueOf(1000));
        String text = document.toString();

        assertThat(text)
                .contains("api_key=***")
                .contains("token=***")
                .contains("ghp_***")
                .doesNotContain("sk-websearch-secret")
                .doesNotContain("ghp_websearchcontent12345")
                .doesNotContain("ghp_websearchid12345")
                .doesNotContain("ghp_websearchtitle12345")
                .doesNotContain("sk-websearch-meta");
    }

    /** 搜索正文中的编号和展示名不能被误判为 URL 并触发 SSRF 阻断。 */
    @Test
    void shouldIgnoreNonUrlTextInWebsearchResults() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected java.net.InetAddress[] resolveHost(String host) throws Exception {
                        return new java.net.InetAddress[] {
                            java.net.InetAddress.getByName("0.0.1.199")
                        };
                    }
                };
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        policy,
                        new WebsearchTalent() {
                            @Override
                            public String websearch(
                                    String query,
                                    Integer numResults,
                                    String livecrawl,
                                    String type,
                                    Integer contextMaxCharacters) {
                                return "status 455, actor github-actions[bot]";
                            }
                        });

        Document document =
                websearch.websearch(
                        "allowed search",
                        Integer.valueOf(1),
                        "fallback",
                        "auto",
                        Integer.valueOf(1000));

        assertThat(document.getContent()).contains("455", "github-actions[bot]");
    }

    @Test
    void shouldRedactSecretsFromCodesearchSuccessContainers() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(
                        new SecurityPolicyService(env.appConfig),
                        new CodeSearchTalent() {
                            @Override
                            public String codesearch(String query, Integer tokensNum)
                                    throws Throwable {
                                Map<String, Object> hit =
                                        new java.util.LinkedHashMap<String, Object>();
                                hit.put("title", "code ghp_codesearchtitle12345");
                                hit.put(
                                        "document",
                                        new Document("code api_key=sk-codesearch-secret")
                                                .id("doc-ghp_codesearchid12345")
                                                .metadata("note", "token=ghp_codesearchnote12345"));
                                Map<String, Object> result =
                                        new java.util.LinkedHashMap<String, Object>();
                                result.put("results", Arrays.asList(hit));
                                return ONode.serialize(result);
                            }
                        });

        Object result = codesearch.codesearch("allowed code query", Integer.valueOf(5000));
        String text = String.valueOf(result);

        assertThat(text)
                .contains("api_key=***")
                .contains("token=***")
                .contains("ghp_***")
                .doesNotContain("sk-codesearch-secret")
                .doesNotContain("ghp_codesearchtitle12345")
                .doesNotContain("ghp_codesearchid12345")
                .doesNotContain("ghp_codesearchnote12345");
    }

    @Test
    void shouldUseAdditionalWebSearchProviderWhenConfigured() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getWeb().setSearchBackend("exa");
        java.util.List<WebSearchProvider> providers =
                new java.util.concurrent.CopyOnWriteArrayList<WebSearchProvider>();
        WebSearchProvider exaProvider =
                new WebSearchProvider() {
                    @Override
                    public String name() {
                        return "exa";
                    }

                    @Override
                    public boolean isAvailable() {
                        return true;
                    }

                    @Override
                    public List<SearchResult> search(String query, int limit) {
                        assertThat(query).isEqualTo("solon ai");
                        assertThat(limit).isEqualTo(2);
                        return Arrays.asList(
                                new SearchResult(
                                        "Exa Result", "https://example.com/exa", "附加搜索结果"));
                    }
                };
        DefaultToolRegistry registry =
                DefaultToolRegistry.builder()
                        .appConfig(env.appConfig)
                        .preferenceStore(new SqlitePreferenceStore(env.sqliteDatabase))
                        .sessionRepository(env.sessionRepository)
                        .agentProfileService(env.agentProfileService)
                        .deliveryService(env.deliveryService)
                        .memoryService(env.memoryService)
                        .sessionSearchService(env.sessionSearchService)
                        .localSkillService(env.localSkillService)
                        .skillHubService(env.skillHubService)
                        .checkpointService(env.checkpointService)
                        .delegationService(env.delegationService)
                        .runtimeSettingsService(env.runtimeSettingsService)
                        .gatewayRuntimeRefreshService(env.gatewayRuntimeRefreshService)
                        .securityPolicyService(new SecurityPolicyService(env.appConfig))
                        .processRegistry(env.processRegistry)
                        .pluginTools(java.util.Collections.emptyList())
                        .webSearchProviders(providers)
                        .build();
        providers.add(exaProvider);

        SolonClawWebTools.SafeWebsearchTool websearch = null;
        for (Object tool : registry.resolveEnabledTools("MEMORY:room-1:user-1")) {
            if (tool instanceof SolonClawWebTools.SafeWebsearchTool) {
                websearch = (SolonClawWebTools.SafeWebsearchTool) tool;
                break;
            }
        }

        assertThat(websearch).isNotNull();
        Document document =
                websearch.websearch(
                        "solon ai", Integer.valueOf(2), "fallback", "auto", Integer.valueOf(1000));
        ONode result = ONode.ofJson(document.getContent());

        assertThat(result.get("provider").getString()).isEqualTo("exa");
        assertThat(result.get("data").get("web").get(0).get("url").getString())
                .isEqualTo("https://example.com/exa");
    }

    @Test
    void shouldFallbackToBuiltInBackendWhenMatchedAdditionalProviderFails() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getWeb().setSearchBackend("brave-free");
        env.appConfig.getWeb().setBraveSearchApiKey("brv-test-secret");
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        new SecurityPolicyService(env.appConfig), null, env.appConfig) {
                    @Override
                    protected String executeBraveSearchRequest(
                            String query, int limit, String apiKey) {
                        return "{\"web\":{\"results\":[{\"title\":\"Built-in Result\",\"url\":\"https://example.com/builtin\",\"description\":\"后备搜索结果\"}]}}";
                    }
                };
        websearch.setWebSearchProviders(
                Arrays.asList(failingWebSearchProvider(true), failingWebSearchProvider(false)));

        Document document =
                websearch.websearch(
                        "solon ai", Integer.valueOf(2), "fallback", "auto", Integer.valueOf(1000));
        ONode result = ONode.ofJson(document.getContent());

        assertThat(result.get("data").get("web").get(0).get("url").getString())
                .isEqualTo("https://example.com/builtin");
    }

    @Test
    void shouldNotSendQueryToUnselectedProviderWhenConfiguredProviderIsUnavailable()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getWeb().setSearchBackend("primary-search");
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        new SecurityPolicyService(env.appConfig),
                        new WebsearchTalent() {
                            @Override
                            public String websearch(
                                    String query,
                                    Integer numResults,
                                    String livecrawl,
                                    String type,
                                    Integer contextMaxCharacters) {
                                return "Built-in fallback https://example.com/builtin-fallback";
                            }
                        },
                        env.appConfig);
        websearch.setWebSearchProviders(
                Arrays.asList(
                        new WebSearchProvider() {
                            @Override
                            public String name() {
                                throw new IllegalStateException("broken provider metadata");
                            }

                            @Override
                            public boolean isAvailable() {
                                return true;
                            }

                            @Override
                            public List<SearchResult> search(String query, int limit) {
                                throw new AssertionError("元数据损坏的提供方不应执行搜索");
                            }
                        },
                        unavailableWebSearchProvider("primary-search"),
                        successfulWebSearchProvider("backup_search")));

        Document document =
                websearch.websearch(
                        "solon ai", Integer.valueOf(2), "fallback", "auto", Integer.valueOf(1000));
        assertThat(document.getContent())
                .contains("Built-in fallback", "https://example.com/builtin-fallback")
                .doesNotContain("https://example.com/backup");
    }

    /** 构造不可用的搜索提供方，验证显式配置失败后不会把查询转发给未选择提供方。 */
    private static WebSearchProvider unavailableWebSearchProvider(String name) {
        return new WebSearchProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public List<SearchResult> search(String query, int limit) {
                throw new AssertionError("不可用提供方不应执行搜索");
            }
        };
    }

    /** 构造可用的搜索提供方，返回固定公开 URL。 */
    private static WebSearchProvider successfulWebSearchProvider(String name) {
        return new WebSearchProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<SearchResult> search(String query, int limit) {
                return Arrays.asList(
                        new SearchResult("Backup", "https://example.com/backup", "备用搜索结果"));
            }
        };
    }

    /** 构造在可用性检查或搜索阶段失败的匹配提供方，以验证后备链不会被中断。 */
    private static WebSearchProvider failingWebSearchProvider(boolean failAvailability) {
        return new WebSearchProvider() {
            @Override
            public String name() {
                return "brave-free";
            }

            @Override
            public boolean isAvailable() {
                if (failAvailability) {
                    throw new IllegalStateException("availability failure");
                }
                return true;
            }

            @Override
            public List<SearchResult> search(String query, int limit) {
                throw new IllegalStateException("search failure");
            }
        };
    }

    @Test
    void shouldReportSolonAiWebsearchInitializationFailureWithoutRawObjectMapperCrash()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getWeb().setSearchBackend("solon-ai");
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        new SecurityPolicyService(env.appConfig), null, env.appConfig) {
                    @Override
                    protected WebsearchTalent createSolonAiWebsearchTalent() {
                        throw new NoClassDefFoundError(
                                "Could not initialize class com.fasterxml.jackson.databind.ObjectMapper");
                    }
                };

        assertThatThrownBy(
                        () ->
                                websearch.websearch(
                                        "solon ai",
                                        Integer.valueOf(2),
                                        "fallback",
                                        "auto",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Solon AI websearch backend")
                .hasMessageNotContaining("ObjectMapper")
                .hasRootCauseInstanceOf(NoClassDefFoundError.class);
    }

    @Test
    void shouldUseBraveFreeSearchBackendWhenConfigured() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getWeb().setSearchBackend("brave-free");
        env.appConfig.getWeb().setBraveSearchApiKey("brv-test-secret");
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        new SecurityPolicyService(env.appConfig), null, env.appConfig) {
                    @Override
                    protected String executeBraveSearchRequest(
                            String query, int limit, String apiKey) {
                        assertThat(query).isEqualTo("solon ai");
                        assertThat(limit).isEqualTo(2);
                        assertThat(apiKey).isEqualTo("brv-test-secret");
                        return "{\"web\":{\"results\":[{\"title\":\"Solon AI\",\"url\":\"https://example.com/solon\",\"description\":\"Java agent\"},{\"title\":\"Jimuqu\",\"url\":\"https://example.com/jimuqu\",\"description\":\"Agent\"}]}}";
                    }
                };

        Document document =
                websearch.websearch(
                        "solon ai", Integer.valueOf(2), "fallback", "auto", Integer.valueOf(1000));
        ONode result = ONode.ofJson(document.getContent());

        assertThat(result.get("provider").getString()).isEqualTo("brave-free");
        assertThat(((List<?>) result.get("data").get("web").toData()).size()).isEqualTo(2);
        assertThat(result.get("data").get("web").get(0).get("url").getString())
                .isEqualTo("https://example.com/solon");
    }

    @Test
    void shouldRequireBraveFreeApiKeyWhenConfigured() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getWeb().setSearchBackend("brave_free");
        env.appConfig.getWeb().setBraveSearchApiKey("");
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        new SecurityPolicyService(env.appConfig), null, env.appConfig);

        assertThatThrownBy(
                        () ->
                                websearch.websearch(
                                        "solon ai",
                                        Integer.valueOf(2),
                                        "fallback",
                                        "auto",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BRAVE_SEARCH_API_KEY");
    }

    void shouldUseDdgsSearchBackendWhenConfigured() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getWeb().setSearchBackend("ddgs");
        SecurityPolicyService policy = fixedPublicDnsPolicy(env);
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(policy, null, env.appConfig) {
                    @Override
                    protected String executeDdgsSearchRequest(String query, int limit) {
                        assertThat(query).isEqualTo("solon ai");
                        assertThat(limit).isEqualTo(2);
                        return "<html><body>"
                                + "<a rel=\"nofollow\" class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fsolon&amp;rut=x\">Solon <b>AI</b></a>"
                                + "<a class=\"result__snippet\">Java&nbsp;agent framework</a>"
                                + "<a class=\"result__a\" href=\"https://example.com/jimuqu\">Jimuqu Agent</a>"
                                + "<div class=\"result__snippet\">Local agent</div>"
                                + "</body></html>";
                    }
                };

        Document document =
                websearch.websearch(
                        "solon ai", Integer.valueOf(2), "fallback", "auto", Integer.valueOf(1000));
        ONode result = ONode.ofJson(document.getContent());

        assertThat(result.get("provider").getString()).isEqualTo("ddgs");
        assertThat(((List<?>) result.get("data").get("web").toData()).size()).isEqualTo(2);
        assertThat(result.get("data").get("web").get(0).get("title").getString())
                .isEqualTo("Solon AI");
        assertThat(result.get("data").get("web").get(0).get("url").getString())
                .isEqualTo("https://example.com/solon");
        assertThat(result.get("data").get("web").get(0).get("description").getString())
                .isEqualTo("Java agent framework");
    }

    @Test
    void shouldRedactCodesearchReturnedPojoFields() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = fixedPublicDnsPolicy(env);
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(
                        policy,
                        new CodeSearchTalent() {
                            @Override
                            public String codesearch(String query, Integer tokensNum)
                                    throws Throwable {
                                return "token=ghp_pojoresult12345 https://example.com/code";
                            }
                        });

        Object result = codesearch.codesearch("allowed code query", Integer.valueOf(5000));

        assertThat(result).isInstanceOf(String.class);
        assertThat(String.valueOf(result))
                .contains("example.com")
                .doesNotContain("ghp_pojoresult12345");
    }

    @Test
    void shouldRedactCodesearchUnstructuredObjectStringFields() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = fixedPublicDnsPolicy(env);
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(
                        policy,
                        new CodeSearchTalent() {
                            @Override
                            public String codesearch(String query, Integer tokensNum)
                                    throws Throwable {
                                return new SecretStringReturnedObject().toString();
                            }
                        });

        Object result = codesearch.codesearch("allowed code query", Integer.valueOf(5000));

        assertThat(result).isInstanceOf(String.class);
        assertThat(String.valueOf(result))
                .contains("example.com")
                .doesNotContain("ghp_unstructured12345");
    }

    @Test
    void shouldReportDirectCodeExecutionSecurityBoundaryEnabled() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Map<String, Object> summary =
                SolonClawCodeExecutionSkills.codeExecutionPolicySummary(env.appConfig);

        assertThat(summary)
                .containsEntry("scriptPreflightPathPolicy", Boolean.TRUE)
                .containsEntry("scriptPreflightUrlPolicy", Boolean.TRUE)
                .containsEntry("scriptPreflightMetadataUrlPolicy", Boolean.TRUE)
                .containsEntry("dangerousCommandRulesApplied", Boolean.TRUE)
                .containsEntry("hardlineRulesApplied", Boolean.TRUE)
                .containsEntry("agentApprovalInterceptorRequired", Boolean.TRUE);
    }

    @Test
    void shouldRedactDirectCodeExecutionSkillOutputs() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SolonClawCodeExecutionSkills.SafePythonSkill python =
                new SolonClawCodeExecutionSkills.SafePythonSkill(
                        env.appConfig.getRuntime().getHome(), "python", policy);

        String output =
                python.execute(
                        "print('Authorization: Bearer ghp_directpython12345')\n",
                        Integer.valueOf(1000));

        assertThat(output)
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_directpython12345");
    }

    @Test
    void shouldExposeJimuquStyleExecuteCodeResultEnvelope() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setToolOutputInlineLimit(200);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import json_parse, shell_quote\n"
                                        + "print('\\u001b[31mapi_key=sk-test-secret\\u001b[0m')\n"
                                        + "print(json_parse('{\"ok\": true}')['ok'])\n"
                                        + "print(shell_quote('a b'))\n",
                                Integer.valueOf(5)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(0);
        assertThat(result.get("duration_seconds").getDouble()).isGreaterThanOrEqualTo(0.0d);
        assertThat(result.get("output").getString())
                .contains("api_key=***")
                .contains("True")
                .contains("'a b'")
                .doesNotContain("sk-test-secret")
                .doesNotContain("\u001b");
    }

    @Test
    void shouldReturnJimuquStyleExecuteCodeErrorsWithStderr() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "import sys\nprint('before')\nsys.stderr.write('token=secret123\\n')\nraise RuntimeError('boom')\n",
                                Integer.valueOf(5)));

        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("error").getString()).contains("token=***").contains("RuntimeError");
        assertThat(result.get("output").getString())
                .contains("before")
                .contains("--- stderr ---")
                .contains("token=***")
                .doesNotContain("secret123");
    }

    @Test
    void shouldRedactExecuteCodeTimeoutOutput() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "import time\n"
                                        + "print('token=ghp_timeoutsecret12345', flush=True)\n"
                                        + "time.sleep(3)\n",
                                Integer.valueOf(1)));

        assertThat(result.get("process_status").getString()).isEqualTo("timeout");
        assertThat(result.get("error").getString()).contains("timed out");
        assertThat(result.get("output").getString())
                .contains("token=***")
                .contains("timed out")
                .doesNotContain("ghp_timeoutsecret12345");
    }

    @Test
    void shouldAllowExecuteCodeToCallJimuquFileAndTerminalToolsThroughRpc() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("rpc-source.txt"),
                Arrays.asList("alpha", "needle"),
                StandardCharsets.UTF_8);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        String terminalCommand =
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "echo rpc-terminal"
                        : "printf 'rpc-terminal\\n'";
        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import read_file, write_file, patch, search_files, terminal\n"
                                        + "print(read_file('rpc-source.txt')['content'].splitlines()[0])\n"
                                        + "print(search_files('needle', path='.', limit=5)['matches'][0]['path'])\n"
                                        + "print(write_file('rpc-output.txt', 'before\\n')['output'])\n"
                                        + "print(patch(path='rpc-output.txt', old_string='before', new_string='after')['status'])\n"
                                        + "print(read_file('rpc-output.txt')['content'])\n"
                                        + "print(terminal(\""
                                        + terminalCommand
                                                .replace("\\", "\\\\")
                                                .replace("\"", "\\\"")
                                        + "\")['output'].strip())\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(6);
        assertThat(result.get("output").getString())
                .contains("alpha")
                .contains("rpc-source.txt")
                .contains("success")
                .contains("after")
                .contains("rpc-terminal");
        assertThat(
                        new String(
                                Files.readAllBytes(workspace.resolve("rpc-output.txt")),
                                StandardCharsets.UTF_8))
                .contains("after");
    }

    /** execute_code 内部桥接必须服从当前来源的工具禁用状态，不能绕过注册表策略。 */
    @Test
    void shouldKeepDisabledTerminalAndWebfetchUnavailableInsideExecuteCode() throws Exception {
        String pythonCommand = SolonClawCodeExecutionSkills.defaultPythonCommand();
        assumeTrue(commandExists(pythonCommand));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:execute-code-toolset:disabled";
        env.toolRegistry.disableTools(sourceKey, Arrays.asList("terminal", "webfetch"));
        Path marker =
                new java.io.File(env.appConfig.getRuntime().getHome())
                        .toPath()
                        .resolve("disabled-terminal-ran.txt");
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                env.toolRegistry.resolveEnabledTools(sourceKey).stream()
                        .filter(SolonClawCodeExecutionSkills.SafeExecuteCodeTool.class::isInstance)
                        .map(SolonClawCodeExecutionSkills.SafeExecuteCodeTool.class::cast)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("execute_code tool missing"));
        String terminalCommand =
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "echo executed>disabled-terminal-ran.txt"
                        : "touch disabled-terminal-ran.txt";

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "import solonclaw_tools as tools\n"
                                        + "for name, call in [('terminal', lambda: tools.terminal("
                                        + ONode.serialize(terminalCommand)
                                        + ")), ('webfetch', lambda: tools.webfetch('5'))]:\n"
                                        + "    print(name + ': ' + call()['error'])\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).as(result.toJson()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isZero();
        assertThat(result.get("output").getString())
                .contains("terminal: Tool 'terminal' is not enabled for this execute_code run")
                .contains("webfetch: Tool 'webfetch' is not enabled for this execute_code run")
                .doesNotContain("URL 格式错误");
        assertThat(marker).doesNotExist();
    }

    @Test
    void shouldResetExecuteCodeRpcReadDedupAfterOtherToolCall() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("rpc-repeat.txt"),
                Arrays.asList("alpha", "needle"),
                StandardCharsets.UTF_8);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import read_file, search_files\n"
                                        + "print(read_file('rpc-repeat.txt').get('success'))\n"
                                        + "print(read_file('rpc-repeat.txt').get('dedup'))\n"
                                        + "print(search_files('needle', path='.', limit=5)['matches'][0]['path'])\n"
                                        + "third = read_file('rpc-repeat.txt')\n"
                                        + "print(third.get('dedup'))\n"
                                        + "print(third.get('error'))\n"
                                        + "print(read_file('rpc-repeat.txt').get('error'))\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(5);
        assertThat(result.get("output").getString())
                .contains("True")
                .contains("rpc-repeat.txt")
                .contains("None")
                .contains("BLOCKED");
    }

    @Test
    void shouldRedactSecretsFromFileReadContent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("project-config.txt"),
                Arrays.asList(
                        "public=true",
                        "api_key=sk-test-secret",
                        "callback=https://user:pass@example.com/?token=secret123"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result = ONode.ofJson(fileSkill.read("project-config.txt"));
        String content = result.get("content").getString();

        assertToolSuccess(result);
        assertThat(content)
                .contains("public=true")
                .contains("api_key=***")
                .doesNotContain("sk-test-secret")
                .doesNotContain("secret123")
                .doesNotContain("user:pass");
    }

    @Test
    void shouldRedactSecretsFromFileReadErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        String result = fileSkill.read("logs/token=ghp_filereaderror12345.txt");

        assertThat(result).contains("token=***").doesNotContain("ghp_filereaderror12345");
    }

    @Test
    void shouldRedactSecretsFromFileReadSuccessPath() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.createDirectories(workspace.resolve("logs"));
        Files.write(
                workspace.resolve("logs/token-ghp_filereadsuccess12345.txt"),
                Arrays.asList("public=true"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result =
                ONode.ofJson(fileSkill.read("logs/token-ghp_filereadsuccess12345.txt", 1, 5));

        assertToolSuccess(result);
        assertThat(result.get("summary").getString())
                .contains("token-ghp_***")
                .doesNotContain("ghp_filereadsuccess12345");
        assertThat(result.get("path").getString())
                .contains("token-ghp_***")
                .doesNotContain("ghp_filereadsuccess12345");
        assertThat(result.toJson()).doesNotContain("ghp_filereadsuccess12345");
    }

    @Test
    void shouldRedactSecretsFromFileWriteListAndDeleteResultsOnly() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        String write =
                fileSkill.write(
                        "logs/token-ghp_filewrite12345.txt",
                        "Authorization: Bearer ghp_filecontent12345");
        String list = fileSkill.list("logs");
        assertThat(
                        new String(
                                Files.readAllBytes(
                                        new java.io.File(env.appConfig.getRuntime().getHome())
                                                .toPath()
                                                .resolve("logs/token-ghp_filewrite12345.txt")),
                                StandardCharsets.UTF_8))
                .contains("ghp_filecontent12345");
        String delete = fileSkill.delete("logs/token-ghp_filewrite12345.txt");

        assertThat(write)
                .contains("token-ghp_***")
                .doesNotContain("ghp_filewrite12345")
                .doesNotContain("ghp_filecontent12345");
        assertThat(list).contains("token-ghp_***").doesNotContain("ghp_filewrite12345");
        assertThat(delete).contains("token-ghp_***").doesNotContain("ghp_filewrite12345");
    }

    @Test
    void shouldReportResolvedAbsolutePathForFileWrite() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result = ONode.ofJson(fileSkill.write("notes/out.txt", "hello\n"));
        String expected = workspace.resolve("notes/out.txt").toRealPath().toString();

        assertToolSuccess(result);
        assertThat(result.get("resolved_path").getString()).isEqualTo(expected);
        Object filesModified = result.get("files_modified").toData();
        assertThat(String.valueOf(filesModified)).isEqualTo("[" + expected + "]");
        assertThat(result.get("path").getString()).isEqualTo("notes/out.txt");
        assertThat(
                        new String(
                                Files.readAllBytes(workspace.resolve("notes/out.txt")),
                                StandardCharsets.UTF_8))
                .isEqualTo("hello\n");
    }

    @Test
    void shouldReportResolvedAbsolutePathForFileRead() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path file = workspace.resolve("notes/input.txt");
        Files.createDirectories(file.getParent());
        Files.write(file, Arrays.asList("alpha", "beta"), StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result = ONode.ofJson(fileSkill.read("notes/input.txt", 1, 2));
        String expected = file.toRealPath().toString();

        assertToolSuccess(result);
        assertThat(result.get("resolved_path").getString()).isEqualTo(expected);
        assertThat(result.get("path").getString()).isEqualTo("notes/input.txt");
        assertThat(result.get("content").getString()).contains("1|alpha").contains("2|beta");
    }

    @Test
    void shouldReadAndWriteRuntimeReferencesWithinToolRoot() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path input = workspace.resolve("tool-results/run-1/call-1.txt");
        Files.createDirectories(input.getParent());
        Files.write(input, Arrays.asList("runtime ref"), StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode read =
                ONode.ofJson(fileSkill.read("workspace://tool-results/run-1/call-1.txt", 1, 2));
        ONode write = ONode.ofJson(fileSkill.write("workspace://notes/out.txt", "saved\n"));

        assertToolSuccess(read);
        assertThat(read.get("content").getString()).contains("1|runtime ref");
        assertThat(read.get("resolved_path").getString()).isEqualTo(input.toRealPath().toString());
        assertToolSuccess(write);
        assertThat(write.get("resolved_path").getString())
                .isEqualTo(workspace.resolve("notes/out.txt").toRealPath().toString());
        assertThat(
                        new String(
                                Files.readAllBytes(workspace.resolve("notes/out.txt")),
                                StandardCharsets.UTF_8))
                .isEqualTo("saved\n");
    }

    @Test
    void shouldSuggestSimilarFilesWhenFileReadPathIsMissing() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.createDirectories(workspace.resolve("config"));
        Files.write(
                workspace.resolve("config/app.yaml"),
                Arrays.asList("app: true"),
                StandardCharsets.UTF_8);
        Files.write(
                workspace.resolve("config/app.yml"),
                Arrays.asList("app: short"),
                StandardCharsets.UTF_8);
        Files.write(
                workspace.resolve("config/application.yaml"),
                Arrays.asList("app: long"),
                StandardCharsets.UTF_8);
        Files.write(
                workspace.resolve("config/readme.txt"),
                Arrays.asList("ignore"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result = ONode.ofJson(fileSkill.read("config/app.json", 1, 5));
        Object suggestionsData = result.get("similar_files").toData();
        String suggestions = String.valueOf(suggestionsData);

        assertToolError(result);
        assertThat(result.get("error").getString()).contains("config/app.json");
        assertThat(suggestionsData).isNotNull();
        assertThat(result.get("path").getString()).isEqualTo("config/app.json");
        assertThat(result.get("resolved_path").getString())
                .isEqualTo(
                        workspace
                                .resolve("config/app.json")
                                .toAbsolutePath()
                                .normalize()
                                .toString());
        assertThat(suggestions)
                .contains("config/app.yaml")
                .contains("config/app.yml")
                .contains("config/application.yaml")
                .doesNotContain("config/readme.txt");
    }

    // shouldNotSuggestSensitiveFilesWhenFileReadPathIsMissing 已删除：相似文件建议走读路径
    // （checkFileToolArgs(READ_FILE, ...)），凭据文件读已放宽（对齐 外部对标仓库"读非安全边界"），
    // credentials.json 现在可出现在建议中，原"读阻断"语义不再成立。

    @Test
    void shouldHideUtf8BomOnFileReadAndPreserveItAcrossEdits() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path readTarget = workspace.resolve("bom-read.txt");
        Path writeTarget = workspace.resolve("bom-write.txt");
        Path patchTarget = workspace.resolve("bom-patch.txt");
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        Files.write(readTarget, concat(bom, "alpha\nbravo\n".getBytes(StandardCharsets.UTF_8)));
        Files.write(writeTarget, concat(bom, "old\n".getBytes(StandardCharsets.UTF_8)));
        Files.write(patchTarget, concat(bom, "first\nsecond\n".getBytes(StandardCharsets.UTF_8)));
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(env.appConfig.getRuntime().getHome(), policy);
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(env.appConfig.getRuntime().getHome(), policy);

        ONode read = ONode.ofJson(fileSkill.read("bom-read.txt", 1, 2));
        ONode write = ONode.ofJson(fileSkill.write("bom-write.txt", "new\n"));
        ONode patch =
                ONode.ofJson(
                        patchTools.patch(
                                "replace",
                                "bom-patch.txt",
                                "first",
                                "first updated",
                                Boolean.FALSE,
                                null));

        assertToolSuccess(read);
        assertThat(read.get("content").getString())
                .contains("1|alpha")
                .doesNotContain("     1|")
                .doesNotContain("\ufeff");
        assertToolSuccess(write);
        assertThat(Files.readAllBytes(writeTarget))
                .startsWith(bom)
                .isEqualTo(concat(bom, "new\n".getBytes(StandardCharsets.UTF_8)));
        assertToolSuccess(patch);
        assertThat(Files.readAllBytes(patchTarget))
                .startsWith(bom)
                .isEqualTo(concat(bom, "first updated\nsecond\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldRejectJarInternalFileToolPathsBeforeDelegating() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));
        String jarPath = "app.jar!/org/noear/solon/core/USER.md";

        ONode readResult = ONode.ofJson(fileSkill.read(jarPath));

        assertToolError(readResult);
        assertThat(readResult.get("error").getString())
                .contains("jar-internal paths are not disk files");
        assertThatThrownBy(() -> fileSkill.write(jarPath, "content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jar-internal paths are not disk files");
        assertThatThrownBy(() -> fileSkill.write("notes/\0output.txt", "content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径包含非法字符");
        assertThatThrownBy(() -> fileSkill.delete(jarPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jar-internal paths are not disk files");
        assertThatThrownBy(() -> fileSkill.list(jarPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jar-internal paths are not disk files");
    }

    @Test
    void shouldGuardFileToolsAgainstSymlinkEscapesBeforeDelegating() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path outside = Files.createTempDirectory("jimuqu-file-outside");
        Path outsideFile = outside.resolve("secret.txt");
        Files.write(outsideFile, Arrays.asList("TOKEN=old"), StandardCharsets.UTF_8);
        Path link = workspace.resolve("linked");
        assumeTrue(createDirectoryLink(link, outside));
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode readResult = ONode.ofJson(fileSkill.read("linked/secret.txt"));
        assertToolError(readResult);
        assertThat(readResult.get("error").getString()).contains("符号链接").contains("沙箱外部");
        assertThatThrownBy(() -> fileSkill.write("linked/secret.txt", "TOKEN=new"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APPROVAL_REQUIRED")
                .hasMessageContaining("工作区外")
                .hasMessageContaining("linked/secret.txt");
        assertThat(new String(Files.readAllBytes(outsideFile), StandardCharsets.UTF_8))
                .contains("TOKEN=old");
    }

    @Test
    void shouldGuardPatchToolsAgainstSymlinkEscapesBeforeWriting() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path outside = Files.createTempDirectory("jimuqu-patch-outside");
        Path outsideFile = outside.resolve("secret.txt");
        Files.write(outsideFile, Arrays.asList("TOKEN=old"), StandardCharsets.UTF_8);
        Files.write(
                workspace.resolve("inside.txt"), Arrays.asList("inside"), StandardCharsets.UTF_8);
        Path link = workspace.resolve("linked");
        assumeTrue(createDirectoryLink(link, outside));
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode replaceResult =
                ONode.ofJson(
                        patchTools.patch(
                                "replace",
                                "linked/secret.txt",
                                "TOKEN=old",
                                "TOKEN=new",
                                Boolean.FALSE,
                                null));
        ONode updateResult =
                ONode.ofJson(
                        patchTools.patch(
                                "patch",
                                null,
                                null,
                                null,
                                null,
                                "*** Begin Patch\n"
                                        + "*** Update File: linked/secret.txt\n"
                                        + "@@ TOKEN @@\n"
                                        + "-TOKEN=old\n"
                                        + "+TOKEN=new\n"
                                        + "*** End Patch"));
        ONode addResult =
                ONode.ofJson(
                        patchTools.patch(
                                "patch",
                                null,
                                null,
                                null,
                                null,
                                "*** Begin Patch\n"
                                        + "*** Add File: linked/new.txt\n"
                                        + "+TOKEN=new\n"
                                        + "*** End Patch"));
        ONode moveResult =
                ONode.ofJson(
                        patchTools.patch(
                                "patch",
                                null,
                                null,
                                null,
                                null,
                                "*** Begin Patch\n"
                                        + "*** Move File: inside.txt -> linked/moved.txt\n"
                                        + "*** End Patch"));

        assertPatchSymlinkEscapeBlocked(replaceResult);
        assertPatchSymlinkEscapeBlocked(updateResult);
        assertPatchSymlinkEscapeBlocked(addResult);
        assertPatchSymlinkEscapeBlocked(moveResult);
        assertThat(new String(Files.readAllBytes(outsideFile), StandardCharsets.UTF_8))
                .contains("TOKEN=old");
        assertThat(Files.exists(outside.resolve("new.txt"))).isFalse();
        assertThat(Files.exists(outside.resolve("moved.txt"))).isFalse();
        assertThat(
                        new String(
                                Files.readAllBytes(workspace.resolve("inside.txt")),
                                StandardCharsets.UTF_8))
                .contains("inside");
    }

    @Test
    void shouldApplyJimuquToolOutputLimitsToFileReads() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("long-lines.txt"),
                Arrays.asList("alpha", "0123456789ABCDEFGHIJ", "charlie", "delta"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig),
                        2,
                        10);

        ONode firstPage = ONode.ofJson(fileSkill.read("long-lines.txt", 1, 99));

        assertToolSuccess(firstPage);
        assertThat(firstPage.get("limit").getInt()).isEqualTo(2);
        assertThat(firstPage.get("total_lines").getInt()).isEqualTo(4);
        assertThat(firstPage.get("truncated").getBoolean()).isTrue();
        assertThat(firstPage.get("hint").getString()).contains("offset=3");
        assertThat(firstPage.get("content").getString())
                .contains("1|alpha")
                .contains("2|0123456789... [truncated]")
                .doesNotContain("     1|")
                .doesNotContain("charlie");

        ONode secondPage = ONode.ofJson(fileSkill.read("long-lines.txt", 3, 2));

        assertThat(secondPage.get("truncated").getBoolean()).isFalse();
        assertThat(secondPage.get("content").getString())
                .contains("3|charlie")
                .contains("4|delta")
                .doesNotContain("     3|");
    }

    @Test
    void shouldApplyUpdatedToolOutputLimitsToExistingFileReadTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setToolOutputMaxLines(2);
        env.appConfig.getTask().setToolOutputMaxLineLength(10);
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("runtime-limits.txt"),
                Arrays.asList("alpha", "0123456789ABCDEFGHIJ", "charlie", "delta"),
                StandardCharsets.UTF_8);
        FunctionTool fileRead =
                functionTool(
                        env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1"), "file_read");

        ONode firstPage = ONode.ofJson(invokeFileRead(fileRead, "runtime-limits.txt", 1, 99));
        env.appConfig.getTask().setToolOutputMaxLines(3);
        env.appConfig.getTask().setToolOutputMaxLineLength(20);
        ToolCallLoopGuardrailService.notifyFileReadDedupIfOtherTool("test_config_refresh");
        ONode updatedPage = ONode.ofJson(invokeFileRead(fileRead, "runtime-limits.txt", 1, 99));

        assertThat(firstPage.get("limit").getInt()).isEqualTo(2);
        assertThat(firstPage.get("content").getString())
                .contains("2|0123456789... [truncated]")
                .doesNotContain("3|charlie");
        assertThat(updatedPage.get("limit").getInt()).isEqualTo(3);
        assertThat(updatedPage.get("content").getString())
                .contains("2|0123456789ABCDEFGHIJ")
                .contains("3|charlie")
                .doesNotContain("... [truncated]");
    }

    @Test
    void shouldApplyUpdatedLineLengthToExistingFileReadToolForSamePage() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setToolOutputMaxLines(3);
        env.appConfig.getTask().setToolOutputMaxLineLength(10);
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("runtime-line-length.txt"),
                Arrays.asList("0123456789ABCDEFGHIJ", "bravo", "charlie"),
                StandardCharsets.UTF_8);
        FunctionTool fileRead =
                functionTool(
                        env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1"), "file_read");

        ONode firstPage = ONode.ofJson(invokeFileRead(fileRead, "runtime-line-length.txt", 1, 2));
        env.appConfig.getTask().setToolOutputMaxLineLength(20);
        ToolCallLoopGuardrailService.notifyFileReadDedupIfOtherTool("test_config_refresh");
        ONode updatedPage = ONode.ofJson(invokeFileRead(fileRead, "runtime-line-length.txt", 1, 2));

        assertThat(firstPage.get("content").getString())
                .contains("1|0123456789... [truncated]")
                .contains("2|bravo");
        assertToolSuccess(updatedPage);
        assertThat(updatedPage.get("dedup").getBoolean()).isFalse();
        assertThat(updatedPage.get("content").getString())
                .contains("1|0123456789ABCDEFGHIJ")
                .contains("2|bravo")
                .doesNotContain("... [truncated]");
    }

    /**
     * 从注册表结果中按模型可见函数名定位工具。
     *
     * @param toolObjects 注册表返回的工具对象。
     * @param name 目标函数名。
     * @return 匹配的模型函数工具。
     */
    private static FunctionTool functionTool(List<Object> toolObjects, String name) {
        for (Object toolObject : toolObjects) {
            Collection<FunctionTool> functions = null;
            if (toolObject instanceof FunctionTool) {
                FunctionTool function = (FunctionTool) toolObject;
                functions = java.util.Collections.singletonList(function);
            } else if (toolObject instanceof ToolProvider) {
                functions = ((ToolProvider) toolObject).getTools();
            } else if (toolObject instanceof Talent) {
                functions = ((Talent) toolObject).getTools(Prompt.of(""));
            }
            if (functions == null) {
                continue;
            }
            for (FunctionTool function : functions) {
                if (function != null && name.equals(function.name())) {
                    return function;
                }
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    /**
     * 通过模型函数边界调用 file_read，验证注册实例的运行时配置读取行为。
     *
     * @param tool file_read 函数工具。
     * @param fileName 文件名。
     * @param offset 起始行。
     * @param limit 最大行数。
     * @return 工具返回的 JSON 文本。
     */
    private static String invokeFileRead(FunctionTool tool, String fileName, int offset, int limit)
            throws Exception {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", fileName);
        args.put("offset", Integer.valueOf(offset));
        args.put("limit", Integer.valueOf(limit));
        try {
            return String.valueOf(tool.handle(args));
        } catch (Exception e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError("file_read invocation failed", e);
        }
    }

    @Test
    void shouldDeduplicateUnchangedRepeatedFileReadsWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("repeat.txt"),
                Arrays.asList("alpha", "bravo", "charlie"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode first = ONode.ofJson(fileSkill.read("repeat.txt", 1, 2));
        ONode second = ONode.ofJson(fileSkill.read("repeat.txt", 1, 2));
        ONode third = ONode.ofJson(fileSkill.read("repeat.txt", 1, 2));
        String expected = workspace.resolve("repeat.txt").toRealPath().toString();

        assertToolSuccess(first);
        assertThat(first.get("content").getString()).contains("alpha").contains("bravo");
        assertThat(first.get("resolved_path").getString()).isEqualTo(expected);
        assertToolSuccess(second);
        assertThat(second.get("dedup").getBoolean()).isTrue();
        assertThat(second.get("resolved_path").getString()).isEqualTo(expected);
        assertThat(second.get("content_returned").getBoolean()).isFalse();
        assertThat(second.get("content").getString()).isNull();
        assertToolError(third);
        assertThat(third.get("error").getString()).contains("BLOCKED").contains("重复");
        assertThat(third.get("resolved_path").getString()).isEqualTo(expected);

        fileSkill.write("repeat.txt", "delta\n");
        ONode changed = ONode.ofJson(fileSkill.read("repeat.txt", 1, 2));

        assertToolSuccess(changed);
        assertThat(changed.get("content").getString()).contains("delta");
    }

    @Test
    void shouldRedactRepeatedFileReadStatusPaths() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path dir = workspace.resolve("token-ghp_filereadstatus12345");
        Files.createDirectories(dir);
        Files.write(
                dir.resolve("repeat.txt"), Arrays.asList("alpha", "bravo"), StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));
        String path = "token-ghp_filereadstatus12345/repeat.txt";

        fileSkill.read(path, 1, 2);
        ONode second = ONode.ofJson(fileSkill.read(path, 1, 2));
        ONode third = ONode.ofJson(fileSkill.read(path, 1, 2));

        assertThat(second.get("path").getString())
                .contains("repeat.txt")
                .doesNotContain("ghp_filereadstatus12345");
        assertThat(third.get("error").getString())
                .contains("BLOCKED")
                .contains("repeat.txt")
                .doesNotContain("ghp_filereadstatus12345");
        assertThat(third.get("path").getString())
                .contains("repeat.txt")
                .doesNotContain("ghp_filereadstatus12345");
    }

    @Test
    void shouldResetFileReadDedupHitsAfterOtherToolCall() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("repeat-after-tool.txt"),
                Arrays.asList("alpha", "bravo", "charlie"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode first = ONode.ofJson(fileSkill.read("repeat-after-tool.txt", 1, 2));
        ONode second = ONode.ofJson(fileSkill.read("repeat-after-tool.txt", 1, 2));
        ToolCallLoopGuardrailService.notifyFileReadDedupIfOtherTool("search_files");
        ONode third = ONode.ofJson(fileSkill.read("repeat-after-tool.txt", 1, 2));
        ONode fourth = ONode.ofJson(fileSkill.read("repeat-after-tool.txt", 1, 2));

        assertToolSuccess(first);
        assertToolSuccess(second);
        assertThat(second.get("dedup").getBoolean()).isTrue();
        assertToolSuccess(third);
        assertThat(third.get("dedup").getBoolean()).isTrue();
        assertThat(third.get("error").getString()).isNull();
        assertToolError(fourth);
        assertThat(fourth.get("error").getString()).contains("BLOCKED").contains("重复");
    }

    @Test
    void shouldWarnButNotBlockWhenWritingStaleReadFileWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path file = workspace.resolve("stale-write.txt");
        Files.write(file, Arrays.asList("alpha"), StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode read = ONode.ofJson(fileSkill.read("stale-write.txt", 1, 2));
        Files.write(file, Arrays.asList("external"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000L));
        ONode staleWrite = ONode.ofJson(fileSkill.write("stale-write.txt", "agent\n"));
        String plainWrite = fileSkill.write("stale-write.txt", "agent2\n");

        assertToolSuccess(read);
        assertToolSuccess(staleWrite);
        assertThat(staleWrite.get("_warning").getString())
                .contains("was modified since you last read")
                .contains("stale-write.txt");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).contains("agent2");
        assertThat(plainWrite).contains("文件保存成功").doesNotContain("_warning");
    }

    @Test
    void shouldRedactSecretsFromFileWriteStaleWarningResult() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path file = workspace.resolve("stale-token-ghp_stalewrite12345.txt");
        Files.write(file, Arrays.asList("alpha"), StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode read = ONode.ofJson(fileSkill.read("stale-token-ghp_stalewrite12345.txt", 1, 2));
        Files.write(file, Arrays.asList("external"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000L));
        ONode staleWrite =
                ONode.ofJson(fileSkill.write("stale-token-ghp_stalewrite12345.txt", "agent\n"));

        assertToolSuccess(read);
        assertToolSuccess(staleWrite);
        assertThat(staleWrite.toJson())
                .contains("stale-token-ghp_***")
                .doesNotContain("ghp_stalewrite12345");
    }

    @Test
    void shouldWarnWhenPatchingStaleReadFileWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path file = workspace.resolve("stale-patch.txt");
        Files.write(file, Arrays.asList("alpha", "bravo"), StandardCharsets.UTF_8);
        SolonClawFileStateTracker tracker = new SolonClawFileStateTracker();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig),
                        2000,
                        2000,
                        tracker);
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig),
                        tracker);

        ONode read = ONode.ofJson(fileSkill.read("stale-patch.txt", 1, 2));
        Files.write(file, Arrays.asList("external", "bravo"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000L));
        ONode patched =
                ONode.ofJson(
                        patchTools.patch(
                                "replace",
                                "stale-patch.txt",
                                "external",
                                "agent",
                                Boolean.FALSE,
                                null));

        assertToolSuccess(read);
        assertToolSuccess(patched);
        assertThat(patched.get("_warning").getString())
                .contains("was modified since you last read")
                .contains("stale-patch.txt");
        assertThat(patched.get("warnings").size()).isEqualTo(1);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).contains("agent");
    }

    @Test
    void shouldRefuseWritingInternalReadDedupStatusTextWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("dedup-source.txt"),
                Arrays.asList("alpha", "bravo"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        fileSkill.read("dedup-source.txt", 1, 2);
        ONode dedup = ONode.ofJson(fileSkill.read("dedup-source.txt", 1, 2));
        String status = dedup.get("summary").getString();

        assertThatThrownBy(() -> fileSkill.write("bad.txt", status))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal read_file status");
        assertThatThrownBy(() -> fileSkill.write("bad.txt", "Note:\n" + status))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal read_file status");

        StringBuilder longDoc = new StringBuilder();
        longDoc.append("This document quotes a tool status for tests:\n").append(status);
        while (longDoc.length() <= status.length() * 2) {
            longDoc.append("\nmore ordinary documentation");
        }
        fileSkill.write("quoted-status.txt", longDoc.toString());
        assertThat(Files.readAllBytes(workspace.resolve("quoted-status.txt")).length)
                .isGreaterThan(0);
    }

    private void assertPatchSymlinkEscapeBlocked(ONode result) {
        assertToolError(result);
        assertThat(result.get("error").getString()).contains("APPROVAL_REQUIRED").contains("工作区外");
    }

    private byte[] concat(byte[] prefix, byte[] suffix) {
        byte[] result = new byte[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }

    private boolean commandExists(String command) {
        try {
            Process process =
                    new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private ToolExchanger exchange(String toolName, Map<String, Object> args) {
        return new ToolExchanger(toolName, args);
    }

    public static class ReturnedPojo {
        private final String title;
        private final String finalUrl;

        public ReturnedPojo(String title, String finalUrl) {
            this.title = title;
            this.finalUrl = finalUrl;
        }

        public String getTitle() {
            return title;
        }

        public String getFinalUrl() {
            return finalUrl;
        }
    }

    public static class BlockedStringReturnedObject {
        @Override
        public String toString() {
            return "https://blocked.example/code?token=secret123";
        }
    }

    public static class SecretStringReturnedObject {
        @Override
        public String toString() {
            return "token=ghp_unstructured12345 https://example.com/code";
        }
    }
}
