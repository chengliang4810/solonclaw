package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.gateway.command.SlashConfirmService;
import com.jimuqu.solon.claw.goal.GoalService;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.Solon;
import org.noear.solon.core.AppContext;

public class DashboardControllerHttpTest {
    private static int port;
    private static File runtimeHome;
    private static String previousHttpKeepAlive;

    @BeforeAll
    static void startApp() throws Exception {
        port = findFreePort();
        runtimeHome = Files.createTempDirectory("solon-claw-dashboard-test").toFile();
        previousHttpKeepAlive = System.getProperty("http.keepAlive");
        System.setProperty("http.keepAlive", "false");

        Solon.start(
                SolonClawApp.class,
                new String[] {
                    "--server.port=" + port,
                    "--solonclaw.runtime.home=" + runtimeHome.getAbsolutePath(),
                    "--solonclaw.scheduler.enabled=false",
                    "--solonclaw.gateway.allowAllUsers=true",
                    "--solonclaw.gateway.injectionSecret=test-injection-secret"
                });

        waitForHealth();
        createSampleSkill();
    }

    @AfterAll
    static void stopApp() {
        try {
            Solon.stopBlock(false, 0);
        } finally {
            if (runtimeHome != null) {
                try {
                    FileUtil.del(runtimeHome);
                } catch (IORuntimeException ignored) {
                    // Windows may keep logback's agent.log handle briefly after Solon stops.
                }
            }
            if (previousHttpKeepAlive == null) {
                System.clearProperty("http.keepAlive");
            } else {
                System.setProperty("http.keepAlive", previousHttpKeepAlive);
            }
        }
    }

    @Test
    void shouldInjectDashboardTokenAndProtectSensitiveApis() throws Exception {
        HttpResult index = request("GET", "/", null, null);
        assertThat(index.status).isEqualTo(200);
        assertThat(index.body).contains("__APP_SESSION_TOKEN__");

        String token = extractToken(index.body);
        assertThat(token).isNotBlank();

        HttpResult status = request("GET", "/api/status", null, null);
        assertThat(status.status).isEqualTo(200);
        assertThat(status.body).contains("\"version\"");
        assertThat(status.body).doesNotContain("\"setup_state\"");

        HttpResult authorizedStatus = request("GET", "/api/status", null, token);
        assertThat(authorizedStatus.status).isEqualTo(200);
        assertThat(authorizedStatus.body).contains("\"setup_state\"");

        HttpResult unauthorizedRuntimeConfig = request("GET", "/api/runtime-config", null, null);
        assertThat(unauthorizedRuntimeConfig.status).isEqualTo(401);

        HttpResult authorizedRuntimeConfig = request("GET", "/api/runtime-config", null, token);
        assertThat(authorizedRuntimeConfig.status).isEqualTo(200);
        assertThat(authorizedRuntimeConfig.body)
                .contains("providers.default.apiKey")
                .contains("solonclaw.task.toolOutputInlineLimit")
                .contains("solonclaw.task.toolOutputTurnBudget")
                .contains("solonclaw.task.toolOutputMaxLines")
                .contains("solonclaw.task.toolOutputMaxLineLength")
                .doesNotContain("tool_output.max_bytes");

        HttpResult unauthorizedDoctor = request("GET", "/api/gateway/doctor", null, null);
        assertThat(unauthorizedDoctor.status).isEqualTo(401);

        HttpResult authorizedDoctor = request("GET", "/api/gateway/doctor", null, token);
        assertThat(authorizedDoctor.status).isEqualTo(200);
        assertThat(authorizedDoctor.body).contains("\"platforms\"");

        HttpResult diagnosticsDoctor = request("GET", "/api/diagnostics/doctor", null, token);
        assertThat(diagnosticsDoctor.status).isEqualTo(200);
        assertThat(diagnosticsDoctor.body)
                .contains("\"runtime_home\"")
                .contains("\"model\"")
                .contains("\"health_checks\"")
                .contains("\"platforms\"");

        HttpResult login = request("GET", "/login", null, null);
        assertThat(login.status).isEqualTo(200);
        assertThat(login.body).contains("__APP_SESSION_TOKEN__");

        HttpResult chat = request("GET", "/chat", null, null);
        assertThat(chat.status).isEqualTo(200);
        assertThat(chat.body).contains("__APP_SESSION_TOKEN__");

        HttpResult files = request("GET", "/files", null, null);
        assertThat(files.status).isEqualTo(200);
        assertThat(files.body).contains("__APP_SESSION_TOKEN__");
    }

    @Test
    void shouldReturnStructuredErrorForInvalidDiagnosticsJson() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult result =
                request(
                        "POST",
                        "/api/diagnostics/security-audit",
                        "{\"action\":\"policy\",\"token\":\"ghp_invaliddiagnostics12345\"",
                        token);

        assertThat(result.status).isEqualTo(200);
        assertThat(result.body)
                .contains("\"success\":false")
                .contains("\"code\":\"DIAGNOSTICS_BAD_REQUEST\"")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_invaliddiagnostics12345")
                .doesNotContain("\"action\":\"policy\"");
    }


    @Test
    void shouldExposeSubprocessEnvironmentProbeHttpEndpoint() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult result =
                request(
                        "POST",
                        "/api/diagnostics/subprocess-environment/probe",
                        "{\"names\":[\"PATH\",\"OPENAI_API_KEY\",\"_SOLONCLAW_FORCE_CUSTOM_TOKEN\",\"ghp_httpdiag1234567890\"]}",
                        token);

        assertThat(result.status).isEqualTo(200);
        assertThat(result.body)
                .contains("\"success\":true")
                .contains("\"subprocess_environment\"")
                .contains("\"requested_count\":4")
                .contains("provider-blocked")
                .contains("force")
                .contains("***")
                .doesNotContain("ghp_httpdiag1234567890");
    }
    @Test
    void shouldReturnStructuredErrorForInvalidProviderValidationRequest() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult result =
                request(
                        "POST",
                        "/api/providers/validate",
                        "{\"baseUrl\":\"\",\"dialect\":\"unsupported\"}",
                        token);

        assertThat(result.status).isEqualTo(400);
        assertThat(result.body)
                .contains("\"success\":false")
                .contains("\"code\":\"PROVIDER_VALIDATE_BAD_REQUEST\"");
    }


    @Test
    void shouldReturnStructuredErrorForInvalidSubprocessEnvironmentProbeJson() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult result =
                request(
                        "POST",
                        "/api/diagnostics/subprocess-environment/probe",
                        "{\"names\":[\"PATH\",\"ghp_invalidprobe1234567890\"",
                        token);

        assertThat(result.status).isEqualTo(200);
        assertThat(result.body)
                .contains("\"success\":false")
                .contains("\"code\":\"DIAGNOSTICS_BAD_REQUEST\"")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_invalidprobe1234567890");
    }
    @Test
    void shouldUpdateDashboardPlatformToolsetPolicy() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult overview = request("GET", "/api/tools/platform-toolsets", null, token);
        assertThat(overview.status).isEqualTo(200);
        assertThat(overview.body)
                .contains("\"platforms\"")
                .contains("\"feishu\"")
                .contains("\"dingtalk\"")
                .contains("\"wecom\"")
                .contains("\"weixin\"")
                .contains("\"qqbot\"")
                .contains("\"yuanbao\"");

        HttpResult update =
                request(
                        "PUT",
                        "/api/tools/platform-toolsets/feishu",
                        "{\"enabledToolsets\":[\"terminal\",\" file \",\"terminal\",\"\"],\"disabledToolsets\":\"browser, terminal , browser\",\"approvalRequired\":true}",
                        token);
        assertThat(update.status).isEqualTo(200);
        assertThat(update.body)
                .contains("\"enabledToolsets\":[\"terminal\",\"file\"]")
                .contains("\"disabledToolsets\":[\"browser\",\"terminal\"]")
                .contains("\"approvalRequired\":true");

        File overrideFile = new File(runtimeHome, "config.yml");
        assertThat(overrideFile).exists();
        assertThat(FileUtil.readUtf8String(overrideFile))
                .contains("gateway:")
                .contains("platforms:")
                .contains("FEISHU:")
                .contains("enabledToolsets:")
                .contains("- terminal")
                .contains("- file")
                .contains("disabledToolsets:")
                .contains("- browser")
                .contains("approvalRequired: true");

        HttpResult refreshed = request("GET", "/api/tools/platform-toolsets", null, token);
        assertThat(refreshed.status).isEqualTo(200);
        assertThat(refreshed.body)
                .contains("\"feishu\"")
                .contains("\"enabledToolsets\":[\"terminal\",\"file\"]")
                .contains("\"disabledToolsets\":[\"browser\",\"terminal\"]")
                .contains("\"approvalRequired\":true");

        HttpResult unsupported =
                request(
                        "PUT",
                        "/api/tools/platform-toolsets/slack",
                        "{\"enabledToolsets\":[\"terminal\"]}",
                        token);
        assertThat(unsupported.status).isEqualTo(400);
        assertThat(unsupported.body)
                .contains("\"success\":false")
                .contains("\"code\":\"PLATFORM_TOOLSETS_BAD_REQUEST\"");
    }

    @Test
    void shouldValidateConfiguredProviderThroughDashboardRoute() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        AppConfig.ProviderConfig provider =
                Solon.context().getBean(AppConfig.class).getProviders().get("default");
        String previousBaseUrl = provider.getBaseUrl();
        String previousDialect = provider.getDialect();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/v1/models",
                    exchange -> {
                        byte[] bytes =
                                "{\"data\":[{\"id\":\"dashboard-runtime-model\"}]}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.start();
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            provider.setDialect("openai");

            HttpResult result =
                    request(
                            "POST",
                            "/api/providers/validate",
                            "{\"providerKey\":\"default\"}",
                            token);

            assertThat(result.status).isEqualTo(200);
            assertThat(result.body)
                    .contains("\"success\":true")
                    .contains("\"ok\":true")
                    .contains("\"reachable\":true")
                    .contains("\"status\":\"valid\"")
                    .contains("dashboard-runtime-model");
        } finally {
            provider.setBaseUrl(previousBaseUrl);
            provider.setDialect(previousDialect);
            server.stop(0);
        }
    }

    @Test
    void shouldExposeConfigDriftDiagnosticsThroughDashboard() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        File configFile = new File(runtimeHome, "config.yml");
        String previous = configFile.exists() ? FileUtil.readUtf8String(configFile) : null;
        try {
            FileUtil.writeUtf8String(
                    "provider: stale-root-provider\n"
                            + "solonclaw:\n"
                            + "  scheduler:\n"
                            + "    tickSeconds: bad-number\n"
                            + "  oldPanel:\n"
                            + "    apiKey: sk-dashboardconfigdrift12345\n"
                            + "providers:\n"
                            + "  default:\n"
                            + "    name: DefaultProvider\n"
                            + "    baseUrl: https://api.openai.com\n"
                            + "    apiKey: sk-dashboardconfigdrift12345\n"
                            + "    defaultModel: gpt-5.4\n"
                            + "    dialect: openai\n"
                            + "model:\n"
                            + "  providerKey: default\n"
                            + "  default: gpt-5.4\n",
                    configFile);
            RuntimeConfigResolver.initialize(runtimeHome.getAbsolutePath()).reload();

            HttpResult diagnostics = request("GET", "/api/config/diagnostics", null, token);
            assertThat(diagnostics.status).isEqualTo(200);
            assertThat(diagnostics.body)
                    .contains("\"unknown_keys\"")
                    .contains("solonclaw.oldPanel.apiKey")
                    .contains("\"provider\"")
                    .contains("\"effective_diffs\"")
                    .contains("solonclaw.scheduler.tickSeconds")
                    .contains("\"raw_value\"")
                    .contains("\"effective_value\"")
                    .doesNotContain("\"legacy_keys\"")
                    .doesNotContain("sk-dashboardconfigdrift12345");

            HttpResult doctor = request("GET", "/api/diagnostics/doctor", null, token);
            assertThat(doctor.status).isEqualTo(200);
            assertThat(doctor.body)
                    .contains("\"config\"")
                    .contains("config_unknown_keys")
                    .doesNotContain("config_legacy_keys")
                    .doesNotContain("sk-dashboardconfigdrift12345");
        } finally {
            if (previous == null) {
                FileUtil.del(configFile);
            } else {
                FileUtil.writeUtf8String(previous, configFile);
            }
            RuntimeConfigResolver.initialize(runtimeHome.getAbsolutePath()).reload();
        }
    }

    @Test
    void shouldPersistConfigAndExposeDashboardResources() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult createProvider =
                request(
                        "POST",
                        "/api/providers",
                        "{\"providerKey\":\"openai-direct\",\"name\":\"OpenAI渠道\",\"baseUrl\":\"https://api.openai.com\",\"apiKey\":\"test-key\",\"defaultModel\":\"gpt-5-mini\",\"dialect\":\"openai-responses\"}",
                        token);
        assertThat(createProvider.status).isEqualTo(200);

        HttpResult updateDefaultModel =
                request(
                        "PUT",
                        "/api/model/default",
                        "{\"providerKey\":\"openai-direct\",\"model\":\"gpt-5.4\"}",
                        token);
        assertThat(updateDefaultModel.status).isEqualTo(200);

        HttpResult updateFallbacks =
                request(
                        "PUT",
                        "/api/model/fallbacks",
                        "{\"fallbackProviders\":[{\"provider\":\"openai-direct\",\"model\":\"gpt-5-mini\"}]}",
                        token);
        assertThat(updateFallbacks.status).isEqualTo(200);

        HttpResult providers = request("GET", "/api/providers", null, token);
        assertThat(providers.status).isEqualTo(200);
        assertThat(providers.body).contains("openai-direct");
        assertThat(providers.body).contains("\"hasApiKey\":true");
        assertThat(providers.body).doesNotContain("test-key");
        AppConfig.ProviderConfig defaultProvider =
                Solon.context().getBean(AppConfig.class).getProviders().get("default");
        defaultProvider.setBaseUrl("https://user:provider-pass@example.com/v1?token=provider-token");
        HttpResult maskedProviders = request("GET", "/api/providers", null, token);
        assertThat(maskedProviders.status).isEqualTo(200);
        assertThat(maskedProviders.body)
                .contains("https://user:***@example.com/v1?token=***")
                .doesNotContain("provider-pass")
                .doesNotContain("provider-token");
        defaultProvider.setBaseUrl("https://api.openai.com");

        HttpResult saveConfig =
                request(
                        "PUT",
                        "/api/config",
                        "{\"config\":{\"llm\":{\"model\":\"dashboard-model\"},\"scheduler\":{\"tickSeconds\":45}}}",
                        token);
        assertThat(saveConfig.status).isEqualTo(200);
        File overrideFile = new File(runtimeHome, "config.yml");
        assertThat(overrideFile).exists();
        assertThat(FileUtil.readUtf8String(overrideFile)).contains("dashboard-model");

        HttpResult configSchema = request("GET", "/api/config/schema", null, token);
        assertThat(configSchema.status).isEqualTo(200);
        assertThat(configSchema.body)
                .contains("\"task.toolOutputInlineLimit\"")
                .contains("\"task.toolOutputTurnBudget\"")
                .contains("\"task.toolOutputMaxLines\"")
                .contains("\"task.toolOutputMaxLineLength\"")
                .doesNotContain("\"tool_output.max_bytes\"");

        HttpResult saveRuntimeConfig =
                request(
                        "PUT",
                        "/api/runtime-config",
                        "{\"key\":\"providers.default.apiKey\",\"value\":\"secret12345678\"}",
                        token);
        assertThat(saveRuntimeConfig.status).isEqualTo(200);
        assertThat(overrideFile).exists();
        assertThat(FileUtil.readUtf8String(overrideFile)).contains("apiKey: secret12345678");

        HttpResult saveToolOutputBudget =
                request(
                        "PUT",
                        "/api/runtime-config",
                        "{\"key\":\"solonclaw.task.toolOutputTurnBudget\",\"value\":\"123456\"}",
                        token);
        assertThat(saveToolOutputBudget.status).isEqualTo(200);
        assertThat(FileUtil.readUtf8String(overrideFile))
                .contains("task:")
                .contains("toolOutputTurnBudget: '123456'")
                .doesNotContain("tool_output:");

        HttpResult revealRuntimeConfig =
                request(
                        "POST",
                        "/api/runtime-config/reveal",
                        "{\"key\":\"providers.default.apiKey\"}",
                        token);
        assertThat(revealRuntimeConfig.status).isEqualTo(200);
        assertThat(revealRuntimeConfig.body).contains("secret12345678");

        seedDashboardGoalSession();

        HttpResult sessions = request("GET", "/api/sessions?limit=20&offset=0", null, token);
        assertThat(sessions.status).isEqualTo(200);
        assertThat(sessions.body).contains("\"total\"");
        assertThat(sessions.body)
                .contains("\"goal_state\"")
                .contains("完成 dashboard 会话目标展示")
                .contains("\"turns_used\":0")
                .contains("\"max_turns\":3");

        HttpResult sessionMessages =
                request("GET", "/api/sessions/dashboard-chat/messages", null, token);
        assertThat(sessionMessages.status).isEqualTo(200);
        assertThat(sessionMessages.body)
                .contains("\"goal_state\"")
                .contains("完成 dashboard 会话目标展示");

        HttpResult renameSession =
                request(
                        "PUT",
                        "/api/sessions/dashboard-chat",
                        "{\"title\":\"Dashboard renamed session\"}",
                        token);
        assertThat(renameSession.status).isEqualTo(200);
        assertThat(renameSession.body).contains("\"title\":\"Dashboard renamed session\"");

        HttpResult renamedSessions =
                request("GET", "/api/sessions?limit=20&offset=0", null, token);
        assertThat(renamedSessions.status).isEqualTo(200);
        assertThat(renamedSessions.body).contains("\"title\":\"Dashboard renamed session\"");

        HttpResult renamedMessages =
                request("GET", "/api/sessions/dashboard-chat/messages", null, token);
        assertThat(renamedMessages.status).isEqualTo(200);
        assertThat(renamedMessages.body)
                .contains("\"goal_state\"")
                .contains("完成 dashboard 会话目标展示");

        HttpResult runs = request("GET", "/api/sessions/dashboard-chat/runs", null, token);
        assertThat(runs.status).isEqualTo(200);
        assertThat(runs.body).contains("\"runs\"");

        HttpResult tree = request("GET", "/api/sessions/dashboard-chat/tree", null, token);
        assertThat(tree.status).isEqualTo(200);
        assertThat(tree.body).contains("\"nodes\"");

        HttpResult latestDescendant =
                request("GET", "/api/sessions/dashboard-chat/latest-descendant", null, token);
        assertThat(latestDescendant.status).isEqualTo(200);
        assertThat(latestDescendant.body)
                .contains("\"requested_session_id\":\"dashboard-chat\"")
                .contains("\"path\"")
                .contains("\"changed\":false");

        HttpResult checkpoints =
                request("GET", "/api/sessions/dashboard-chat/checkpoints", null, token);
        assertThat(checkpoints.status).isEqualTo(200);
        assertThat(checkpoints.body).contains("\"checkpoints\"");

        HttpResult diagnostics = request("GET", "/api/diagnostics", null, token);
        assertThat(diagnostics.status).isEqualTo(200);
        assertThat(diagnostics.body)
                .contains("\"providers\"")
                .contains("\"channels\"")
                .contains("\"policies\"")
                .contains("\"schema_sanitizer\"")
                .contains("\"patch_parser\"")
                .contains("\"code_execution\"")
                .contains("\"subprocess_environment\"")
                .contains("\"attachment_policies\"")
                .contains("\"download_io\"")
                .contains("\"media_cache\"")
                .contains("\"terminal_paste\"")
                .contains("\"redirectUrlCheckedBeforeFollow\":true")
                .contains("\"crossHostHeaderForwardingBlocked\":true")
                .contains("\"streamReadBounded\":true")
                .contains("\"safeOriginalNameSecretRedacted\":true")
                .contains("\"mediaReferenceTraversalBlocked\":true")
                .contains("\"hostPathsNotReturnedInMediaReference\":true")
                .contains("\"pastedLocalPathDetection\":true")
                .contains("\"pathPolicyCheckedBeforeCache\":true")
                .contains("\"credentialPathBlocked\":true")
                .contains("\"rawPathHiddenInPrompt\":true")
                .contains("\"mcpInputSchemaSanitized\":true")
                .contains("\"invalidSchemaDefaultsToObject\":true")
                .contains("\"nullableUnionCollapsed\":true")
                .contains("\"patchFormat\":\"V4A\"")
                .contains("\"atomicValidationBeforeWrite\":true")
                .contains("\"credentialPolicyPrechecked\":true")
                .contains("\"executeCodeSupported\":true")
                .contains("\"scriptPreflightUrlPolicy\":true")
                .contains("\"hardlineRulesApplied\":true")
                .contains("\"sandboxEnvironmentSanitized\":true")
                .contains("\"rpcToolOutputsRedacted\":true")
                .contains("\"defaultDenyUnknownEnv\":true")
                .contains("\"providerBlocklistOverridesPassthrough\":true")
                .contains("\"secretNameSubstringsBlocked\":true")
                .contains("\"runtimeSafetyTogglesBlocked\":true")
                .contains("\"mcp\"")
                .contains("\"runtime_policy\"")
                .contains("\"oauth_policy\"")
                .contains("\"remoteEndpointUrlSafety\":true")
                .contains("\"remoteToolArgumentUrlSafety\":true")
                .contains("\"remoteToolArgumentPathSafety\":true")
                .contains("\"resourceUriUrlSafety\":true")
                .contains("\"blockedUrlsMasked\":true")
                .contains("\"blockedPathsRedacted\":true")
                .contains("\"toolsChangeNotificationPersisted\":true")
                .contains("\"oauthFailureStructuredReauth\":true")
                .contains("\"oauthSecretsRedacted\":true")
                .contains("\"authorizationEndpointUrlSafety\":true")
                .contains("\"tokenEndpointUrlSafety\":true")
                .contains("\"tokenEndpointRedirectUrlSafety\":true")
                .contains("\"stateValidationRequired\":true")
                .contains("\"pkceS256Required\":true")
                .contains("\"accessTokenRedacted\":true")
                .contains("\"refreshTokenRedacted\":true")
                .contains("\"clientSecretRedacted\":true")
                .contains("\"security\"")
                .contains("\"approvals\"")
                .contains("\"approval_policy\"")
                .contains("\"hardline_policy\"")
                .contains("\"cron_approval_policy\"")
                .contains("\"subagent_approval_policy\"")
                .contains("\"smart_approval_policy\"")
                .contains("\"tirith_approval_policy\"")
                .contains("\"approval_lifecycle_policy\"")
                .contains("\"slash_confirm_policy\"")
                .contains("\"approval_card_policy\"")
                .contains("\"approval_audit_policy\"")
                .contains("\"mcp_reload_policy\"")
                .contains("\"dangerousRuleCount\"")
                .contains("\"hardlineRuleCount\"")
                .contains("\"approvalBypassAllowed\":false")
                .contains("\"hardlineAlwaysBlocked\":true")
                .contains("\"humanApprovalPromptSuppressed\":true")
                .contains("\"judgeFailureFallsBackToHumanApproval\":true")
                .contains("\"pendingListHidesApprovalKey\":true")
                .contains("\"approvalRequestObserved\":true")
                .contains("\"approvalResponseObserved\":true")
                .contains("\"scopeOptions\":[\"once\",\"session\",\"always\"]")
                .contains("\"selectorTokenPattern\":\"[A-Za-z0-9_.-]{1,128}\"")
                .contains("\"unsafeSelectorRejected\":true")
                .contains("\"approvalCardDeliveryMode\":\"dangerous_command_approval_card\"")
                .contains("\"approvalCardPlatforms\":[\"FEISHU\",\"QQBOT\"]")
                .contains("\"permanentApprovalAllowedExceptTirith\":true")
                .contains("\"tirithPermanentApprovalHidden\":true")
                .contains("\"domesticCardLabelsLocalized\":true")
                .contains("\"qqbotSessionActionSupported\":true")
                .contains("\"outboundApprovalIdSanitized\":true")
                .contains("\"unsafeApprovalIdFallsBackToKeySelector\":true")
                .contains("\"pendingListUsesSafeSelector\":true")
                .contains("\"bulkRejectUsesSafeSelector\":true")
                .contains("\"rawCommandRedactedInExtras\":true")
                .contains("\"observerEventsRedacted\":true")
                .contains("\"encodedUrlParameterRedacted\":true")
                .contains("\"fragmentUrlParameterRedacted\":true")
                .contains("\"toolChangeNoticeInjected\":true")
                .contains("\"oauthUrlSafetyCovered\":true")
                .contains("\"allow_private_urls\"")
                .contains("\"url_policy\"")
                .contains("\"private_url_policy\"")
                .contains("\"website_policy\"")
                .contains("\"path_policy\"")
                .contains("\"credential_policy\"")
                .contains("\"tool_args_policy\"")
                .contains("\"tirith_policy\"")
                .contains("\"allowedNetworkSchemes\":[\"http\",\"https\",\"ws\",\"wss\"]")
                .contains("\"unsupportedNetworkSchemeBlocked\":true")
                .contains("\"protocolRelativeUrlChecked\":true")
                .contains("\"schemelessHostChecked\":true")
                .contains("\"percentEncodedHostChecked\":true")
                .contains("\"idnHostNormalized\":true")
                .contains("\"userinfoBlocked\":true")
                .contains("\"schemelessSensitiveQueryBlocked\":true")
                .contains("\"sensitiveQueryNameAliasNormalized\":true")
                .contains("\"encodedSensitiveQueryBlocked\":true")
                .contains("\"repeatedEncodedSensitiveQueryBlocked\":true")
                .contains("\"fragmentSensitiveQueryBlocked\":true")
                .contains("\"cloudMetadataBlocked\":true")
                .contains("\"cloudMetadataAlwaysBlocked\":true")
                .contains("\"dnsResolutionRequired\":true")
                .contains("\"obfuscatedIpv4Checked\":true")
                .contains("\"loopbackBlocked\":true")
                .contains("\"sharedFilePathSafetyChecked\":true")
                .contains("\"traversalBlocked\":true")
                .contains("\"rawControlCharactersBlocked\":true")
                .contains("\"devicePathBlocked\":true")
                .contains("\"localManagementSocketAccessBlocked\":true")
                .contains("\"localManagementSocketEnvironmentBlocked\":true")
                .contains("\"localManagementPipeAccessBlocked\":true")
                .contains("\"directorySegmentCount\"")
                .contains("\"fileNameCount\"")
                .contains("\"envExampleFilesAllowed\":true")
                .contains("\"recursiveUrlExtraction\":true")
                .contains("\"returnedDocumentContentChecked\":true")
                .contains("\"downloadOutputPathOptionChecked\":true")
                .contains("\"proxyOptionUrlChecked\":true")
                .contains("\"preproxyOptionUrlChecked\":true")
                .contains("\"systemDnsCommandChecked\":true")
                .contains("\"powershellProxyEnvironmentChecked\":true")
                .contains("\"setxProxyEnvironmentChecked\":true")
                .contains("\"systemProxyCommandChecked\":true")
                .contains("\"windowsRegistryProxyCommandChecked\":true")
                .contains("\"proxyBypassEnvironmentChecked\":true")
                .contains("\"gitPersistentProxyConfigChecked\":true")
                .contains("\"packageManagerProxyBypassEnvironmentChecked\":true")
                .contains("\"packageManagerPersistentProxyConfigChecked\":true")
                .contains("\"warnRequiresApproval\":true")
                .contains("\"blockRequiresApproval\":true")
                .contains("\"commandPassedAsSingleArgument\":true")
                .contains("\"subprocessEnvironmentSanitized\":true")
                .contains("\"timeoutKillsProcess\":true")
                .contains("\"stdoutStderrCollectedSeparately\":true")
                .contains("\"parseFailureKeepsDecision\":true")
                .contains("\"toolShellDetectionApplied\":true")
                .contains("\"secretRedaction\":true")
                .contains("\"credential_file_count\"")
                .contains("\"credential_file_policy\"")
                .contains("\"runtimeRelativeOnly\":true")
                .contains("\"absolutePathRejected\":true")
                .contains("\"pathTraversalRejected\":true")
                .contains("\"hostPathsOmittedFromMetadata\":true")
                .contains("\"rejectedPathsRedacted\":true")
                .contains("\"skillFrontmatterKey\":\"required_credential_files\"")
                .contains("\"configKey\":\"solonclaw.terminal.credentialFiles\"")
                .contains("\"terminal_output_policy\"")
                .contains("\"secretRedactionApplied\":true")
                .contains("\"headTailTruncation\":true")
                .contains("\"truncationNoticeIncluded\":true")
                .contains("\"emptySuccessMessage\":\"执行成功\"")
                .contains("\"exitCodeMeaningReturned\":true")
                .contains("\"executeShellExitMeaningNotice\":true")
                .contains("\"exitCodeSemantics\"")
                .contains("\"grepNoMatchExitOneInformational\":true")
                .contains("\"curlNetworkErrorsExplained\":true")
                .contains("\"tool_result_storage_policy\"")
                .contains("\"pinnedInlineTools\":[\"file_read\",\"read_file\"]")
                .contains("\"pinnedInlineRawObservationAllowed\":false")
                .contains("\"pinnedInlineObservationRedacted\":true")
                .contains("\"pinnedInlinePreviewRedacted\":true")
                .contains("\"oversizedResultsPersisted\":true")
                .contains("\"turnBudgetOverflowPersisted\":true")
                .contains("\"resultRefReturned\":true")
                .contains("\"previewRedacted\":true")
                .contains("\"describedPreviewRedacted\":true")
                .contains("\"persistedOutputRedacted\":true")
                .contains("\"fullOutputSavedRaw\":false")
                .contains("\"storageBase\":\"tool-results\"")
                .contains("\"storageFailureFallsBackToPreviewOnly\":true")
                .contains("\"sudo_rewrite_policy\"")
                .contains("\"rewritesRealSudoInvocations\":true")
                .contains("\"stdinPasswordInjection\":true")
                .contains("\"passwordRedacted\":true")
                .contains("\"ptyDisabledForStdinPipe\":true")
                .contains("\"background_process_policy\"")
                .contains("\"processRegistryBacked\":true")
                .contains("\"stdinWriteSubmitCloseSupported\":true")
                .contains("\"startDangerousCommandChecked\":true")
                .contains("\"startHardlineBlocked\":true")
                .contains("\"stdinExecutionPayloadChecked\":true")
                .contains("\"managedBackgroundRequiredForLongRunningCommands\":true")
                .contains("\"terminal_guardrail_policy\"")
                .contains("\"managedBackgroundProcessRequired\":true")
                .contains("\"credentialPathPrechecked\":true")
                .contains("\"sudoPasswordRedacted\":true")
                .contains("\"sudo_password_configured\"");
        assertThat(diagnostics.body)
                .doesNotContain("\"sudo_password\"")
                .doesNotContain("\"credential_files\"")
                .doesNotContain("\"env_passthrough\"")
                .doesNotContain("\"approval_key\"");
        ONode approvalDiagnostics =
                ONode.ofJson(diagnostics.body).get("data").get("security").get("approvals");
        assertThat(approvalDiagnostics.toJson())
                .doesNotContain("\"pendingQueueContextKey\"")
                .doesNotContain("\"legacyPendingContextKey\"")
                .doesNotContain("\"onceScopeStoresContextKey\"")
                .doesNotContain("\"sessionScopeStoresContextKey\"");
        ONode approvalCardDiagnostics = approvalDiagnostics.get("approval_card_policy");
        assertThat(approvalCardDiagnostics.get("outboundApprovalIdSanitized").getBoolean())
                .isTrue();
        assertThat(approvalCardDiagnostics.get("unsafeApprovalIdFallsBackToKeySelector").getBoolean())
                .isTrue();
        assertThat(approvalCardDiagnostics.get("domesticCardLabelsLocalized").getBoolean())
                .isTrue();
        assertThat(approvalCardDiagnostics.get("qqbotSessionActionSupported").getBoolean())
                .isTrue();
        ONode mcpDiagnostics = ONode.ofJson(diagnostics.body).get("data").get("mcp");
        assertThat(mcpDiagnostics.toJson())
                .doesNotContain("\"oauthFailureMarkers\"")
                .doesNotContain("\"pathishArgumentKeys\"");
        ONode toolPolicies = ONode.ofJson(diagnostics.body).get("data").get("tools").get("policies");
        assertThat(toolPolicies.toJson())
                .doesNotContain("\"unsupportedKeywordsStripped\"")
                .doesNotContain("\"topLevelForbiddenCombinatorsStripped\"")
                .doesNotContain("\"patternAndFormatKeywords\"")
                .doesNotContain("\"rpcTools\"")
                .doesNotContain("\"forcePrefix\"");
        ONode terminalDiagnostics =
                ONode.ofJson(diagnostics.body).get("data").get("security").get("terminal");
        assertThat(terminalDiagnostics.toJson())
                .doesNotContain("\"envKey\"")
                .doesNotContain("\"stdinWrapperFamilies\"");
        ONode policyDiagnostics =
                ONode.ofJson(diagnostics.body).get("data").get("security").get("policy");
        assertThat(policyDiagnostics.toJson())
                .doesNotContain("\"writeSafeRoot\"")
                .doesNotContain("\"workdirSafePattern\"")
                .doesNotContain("\"directorySegmentSamples\"")
                .doesNotContain("\"configuredCredentialFileSamples\"")
                .doesNotContain("\"urlKeySamples\"")
                .doesNotContain("\"pathKeySamples\"")
                .doesNotContain("\"configuredPath\"")
                .doesNotContain("\"resolvedPath\"");
        ONode readOnlyAuditPolicy =
                ONode.ofJson(diagnostics.body)
                        .get("data")
                        .get("security")
                        .get("audit_policy")
                        .get("coverage")
                        .get("readOnlyAuditPolicy");
        assertThat(readOnlyAuditPolicy.get("executesCommand").getBoolean()).isFalse();
        assertThat(readOnlyAuditPolicy.get("opensNetworkConnection").getBoolean()).isFalse();
        assertThat(readOnlyAuditPolicy.get("readsTargetUrl").getBoolean()).isFalse();
        assertThat(readOnlyAuditPolicy.get("writesFile").getBoolean()).isFalse();
        assertThat(readOnlyAuditPolicy.get("storesAuditInput").getBoolean()).isFalse();
        assertThat(readOnlyAuditPolicy.get("secretRedactionApplied").getBoolean()).isTrue();
        assertThat(readOnlyAuditPolicy.get("toolArgsCommandPolicyInherited").getBoolean()).isTrue();
        assertThat(readOnlyAuditPolicy.get("toolArgsUrlPolicyInherited").getBoolean()).isTrue();
        assertThat(readOnlyAuditPolicy.get("toolArgsPathPolicyInherited").getBoolean()).isTrue();
        assertThat(readOnlyAuditPolicy.get("toolArgsJsonParseErrorsRedacted").getBoolean()).isTrue();
        assertThat(readOnlyAuditPolicy.get("commandPreviewLimitChars").getInt()).isEqualTo(400);
        assertThat(readOnlyAuditPolicy.get("findingMessageLimitChars").getInt()).isEqualTo(1000);
        assertThat(readOnlyAuditPolicy.toJson())
                .contains("security_audit")
                .contains("tool_args")
                .contains("policy")
                .doesNotContain("secret-sudo");

        HttpResult commandAudit =
                request(
                        "POST",
                        "/api/diagnostics/security-audit",
                        "{\"action\":\"command\",\"toolName\":\"execute_shell\",\"command\":\"rm -rf /\"}",
                        token);
        assertThat(commandAudit.status).isEqualTo(200);
        assertThat(commandAudit.body)
                .contains("\"action\":\"command\"")
                .contains("\"decision\":\"block\"")
                .contains("\"blocking\":true")
                .contains("\"approval_required\":false")
                .contains("\"suggested_action\":\"change_command\"")
                .contains("\"hardline\"");

        HttpResult policyAudit =
                request(
                        "POST",
                        "/api/diagnostics/security-audit",
                        "{\"action\":\"policy\"}",
                        token);
        assertThat(policyAudit.status).isEqualTo(200);
        assertThat(policyAudit.body)
                .contains("\"action\":\"policy\"")
                .contains("\"coverage\"")
                .contains("\"activeSurfaces\"")
                .contains("\"dangerousCommandApproval\":true")
                .contains("\"slashConfirmPolicy\"")
                .contains("\"approvalCardPolicy\"")
                .contains("\"approvalLifecyclePolicy\"")
                .contains("\"approvalAuditPolicy\"")
                .contains("\"mcpReloadPolicy\"")
                .contains("\"hardlineCommandBlocks\":true")
                .contains("\"urlSafety\":true")
                .contains("\"privateUrlPolicy\":true")
                .contains("\"credentialFilePolicy\":true")
                .contains("\"mcpUrlSafety\":true")
                .contains("mcpOauthUrlSafety")
                .contains("\"mcpPackageSecurity\":true")
                .contains("\"mcpPackageSecurityPolicy\"")
                .contains("\"approvalCardPlatforms\":[\"FEISHU\",\"QQBOT\"]")
                .contains("\"permanentApprovalAllowedExceptTirith\":true")
                .contains("\"tirithPermanentApprovalHidden\":true")
                .contains("\"outboundApprovalIdSanitized\":true")
                .contains("\"unsafeApprovalIdFallsBackToKeySelector\":true")
                .contains("\"rawCommandRedactedInExtras\":true")
                .contains("\"observerEventsRedacted\":true")
                .contains("\"fragmentUrlParameterRedacted\":true")
                .contains("\"reloadHistoryNoticeRedacted\":true")
                .doesNotContain("\"sudo_password\"")
                .doesNotContain("\"credential_files\"")
                .doesNotContain("\"env_passthrough\"")
                .doesNotContain("\"unsupportedKeywordsStripped\"")
                .doesNotContain("\"topLevelForbiddenCombinatorsStripped\"")
                .doesNotContain("\"patternAndFormatKeywords\"")
                .doesNotContain("\"forcePrefix\"")
                .doesNotContain("\"configuredCredentialFileSamples\"")
                .doesNotContain("\"urlKeySamples\"")
                .doesNotContain("\"pathKeySamples\"");
        ONode policyAuditReadOnlyAudit =
                ONode.ofJson(policyAudit.body)
                        .get("data")
                        .get("policy")
                        .get("coverage")
                        .get("readOnlyAuditPolicy");
        assertThat(policyAuditReadOnlyAudit.get("executesCommand").getBoolean()).isFalse();
        assertThat(policyAuditReadOnlyAudit.get("opensNetworkConnection").getBoolean()).isFalse();
        assertThat(policyAuditReadOnlyAudit.get("storesAuditInput").getBoolean()).isFalse();
        assertThat(policyAuditReadOnlyAudit.get("secretRedactionApplied").getBoolean()).isTrue();

        HttpResult statusAudit =
                request(
                        "POST",
                        "/api/diagnostics/security-audit",
                        "{\"action\":\"status\",\"command\":\"echo token=ghp_httpstatus12345\",\"url\":\"http://127.0.0.1/callback?token=http-status-secret\",\"path\":\"target/sk-http-status-secret.txt\"}",
                        token);
        assertThat(statusAudit.status).isEqualTo(200);
        assertThat(statusAudit.body)
                .contains("\"action\":\"status\"")
                .contains("\"blocking\":false")
                .contains("\"approval_required\":false")
                .contains("\"summary\":\"Security policy status is available without exposing secret values.\"")
                .contains("\"coverage\"")
                .contains("\"readOnlyAuditPolicy\"")
                .contains("\"supportsActions\":\"command,url,path,tool_args,policy,status\"")
                .doesNotContain("ghp_httpstatus12345")
                .doesNotContain("http-status-secret")
                .doesNotContain("sk-http-status-secret");
        ONode statusAuditReadOnlyAudit =
                ONode.ofJson(statusAudit.body)
                        .get("data")
                        .get("policy")
                        .get("coverage")
                        .get("readOnlyAuditPolicy");
        assertThat(statusAuditReadOnlyAudit.get("executesCommand").getBoolean()).isFalse();
        assertThat(statusAuditReadOnlyAudit.get("writesFile").getBoolean()).isFalse();
        assertThat(statusAuditReadOnlyAudit.get("secretRedactionApplied").getBoolean()).isTrue();

        HttpResult urlAudit =
                request(
                        "POST",
                        "/api/diagnostics/security-audit",
                        "{\"action\":\"url\",\"url\":\"http://169.254.169.254/latest/meta-data/\"}",
                        token);
        assertThat(urlAudit.status).isEqualTo(200);
        assertThat(urlAudit.body)
                .contains("\"action\":\"url\"")
                .contains("\"decision\":\"block\"")
                .contains("\"blocking\":true")
                .contains("\"approval_required\":false")
                .contains("\"suggested_action\":\"change_url_or_policy\"")
                .contains("\"url_policy\"");

        HttpResult skills = request("GET", "/api/skills", null, token);
        assertThat(skills.status).isEqualTo(200);
        assertThat(skills.body).contains("sample-skill");

        HttpResult skillView = request("GET", "/api/skills/view?name=sample-skill", null, token);
        assertThat(skillView.status).isEqualTo(200);
        assertThat(ONode.ofJson(skillView.body).get("content").getString())
                .contains("Sample skill for dashboard tests");

        HttpResult skillFiles = request("GET", "/api/skills/files?name=sample-skill", null, token);
        assertThat(skillFiles.status).isEqualTo(200);
        assertThat(skillFiles.body).contains("references/info.md");

        HttpResult skillSupportFile =
                request(
                        "GET",
                        "/api/skills/view?name=sample-skill&filePath="
                                + URLEncoder.encode("references/info.md", "UTF-8"),
                        null,
                        token);
        assertThat(skillSupportFile.status).isEqualTo(200);
        assertThat(ONode.ofJson(skillSupportFile.body).get("content").getString())
                .contains("supporting skill notes");

        HttpResult toggleSkill =
                request(
                        "PUT",
                        "/api/skills/toggle",
                        "{\"name\":\"sample-skill\",\"enabled\":false}",
                        token);
        assertThat(toggleSkill.status).isEqualTo(200);
        HttpResult skillsAfterToggle = request("GET", "/api/skills", null, token);
        assertThat(skillsAfterToggle.body).contains("\"enabled\":false");

        HttpResult invalidSkillToggle =
                request(
                        "PUT",
                        "/api/skills/toggle",
                        "{\"name\":\"sample-skill token=ghp_skillparse12345\"",
                        token);
        assertThat(invalidSkillToggle.status).isEqualTo(400);
        assertThat(invalidSkillToggle.body)
                .contains("SKILLS_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_skillparse12345")
                .doesNotContain("sample-skill token");

        HttpResult createCron =
                request(
                        "POST",
                        "/api/cron/jobs",
                        "{\"prompt\":\"daily summary\",\"schedule\":\"0 9 * * *\",\"name\":\"Daily summary\",\"deliver\":\"feishu\",\"deliver_chat_id\":\"chat-dashboard\",\"deliver_thread_id\":\"thread-dashboard\",\"model\":\"gpt-5-mini\",\"base_url\":\"https://api.cron.example/v1/\"}",
                        token);
        assertThat(createCron.status).isEqualTo(200);
        assertThat(createCron.body).contains("\"model\":\"gpt-5-mini\"");
        assertThat(createCron.body).contains("\"provider\":\"openai-direct\"");
        assertThat(createCron.body).contains("\"base_url\":\"https://api.cron.example/v1\"");
        assertThat(createCron.body).contains("\"deliver_chat_id\":\"chat-dashboard\"");
        assertThat(createCron.body).contains("\"deliver_thread_id\":\"thread-dashboard\"");
        String dashboardCronId = ONode.ofJson(createCron.body).get("data").get("id").getString();
        File dashboardScriptsDir = new File(runtimeHome, "scripts");
        FileUtil.mkdir(dashboardScriptsDir);
        FileUtil.writeUtf8String(
                "print('dashboard trigger ok: daily summary')\n",
                new File(dashboardScriptsDir, "dashboard-trigger.py"));
        HttpResult updateCron =
                request(
                        "PUT",
                        "/api/cron/jobs/" + dashboardCronId,
                        "{\"deliver\":\"local\",\"deliver_chat_id\":null,\"deliver_thread_id\":null,\"no_agent\":true,\"script\":\"dashboard-trigger.py\"}",
                        token);
        assertThat(updateCron.status).isEqualTo(200);
        HttpResult cronJobs = request("GET", "/api/cron/jobs", null, token);
        assertThat(cronJobs.body).contains("Daily summary");
        assertThat(cronJobs.body).contains("\"model\":\"gpt-5-mini\"");
        HttpResult cronDetail = request("GET", "/api/cron/jobs/" + dashboardCronId, null, token);
        assertThat(cronDetail.status).isEqualTo(200);
        assertThat(cronDetail.body)
                .contains("\"id\":\"" + dashboardCronId + "\"")
                .contains("\"name\":\"Daily summary\"")
                .contains("\"script\":\"dashboard-trigger.py\"");
        HttpResult cronNext = request("GET", "/api/cron/jobs/next?limit=1", null, token);
        assertThat(cronNext.status).isEqualTo(200);
        ONode cronNextData = ONode.ofJson(cronNext.body).get("data");
        assertThat(cronNextData.get("count").getInt()).isEqualTo(1);
        assertThat(cronNextData.get("include_disabled").getBoolean()).isFalse();
        assertThat(cronNextData.get("jobs").get(0).get("id").getString()).isEqualTo(dashboardCronId);
        HttpResult cronNextLimited = request("GET", "/api/cron/jobs/next?limit=500", null, token);
        assertThat(ONode.ofJson(cronNextLimited.body).get("data").get("limit").getInt()).isEqualTo(50);
        HttpResult apiCronNext =
                request("GET", "/api/cron/jobs/next?include_disabled=true&limit=1", null, token);
        assertThat(apiCronNext.status).isEqualTo(200);
        ONode apiCronNextData = ONode.ofJson(apiCronNext.body).get("data");
        assertThat(apiCronNextData.get("count").getInt()).isEqualTo(1);
        assertThat(apiCronNextData.get("include_disabled").getBoolean()).isTrue();
        assertThat(apiCronNextData.get("jobs").get(0).get("id").getString()).isEqualTo(dashboardCronId);
        HttpResult apiCronNextDefaulted = request("GET", "/api/cron/jobs/next?limit=0", null, token);
        assertThat(ONode.ofJson(apiCronNextDefaulted.body).get("data").get("limit").getInt()).isEqualTo(5);

        HttpResult triggerCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/trigger", "{}", token);
        assertThat(triggerCron.status).isEqualTo(200);
        ONode triggeredCron = ONode.ofJson(triggerCron.body).get("data");
        assertThat(triggeredCron.get("id").getString()).isEqualTo(dashboardCronId);
        assertThat(triggeredCron.get("last_status").getString()).isEqualTo("ok");
        assertThat(triggeredCron.get("last_run_at").getString()).isNotBlank();
        assertThat(triggeredCron.get("last_output").getString())
                .contains("dashboard trigger ok: daily summary");
        HttpResult cronRuns =
                request("GET", "/api/cron/jobs/" + dashboardCronId + "/runs?limit=5", null, token);
        assertThat(cronRuns.status).isEqualTo(200);
        ONode cronRunsData = ONode.ofJson(cronRuns.body).get("data");
        assertThat(cronRunsData.get("job_id").getString()).isEqualTo(dashboardCronId);
        assertThat(cronRunsData.get("count").getInt()).isGreaterThanOrEqualTo(1);
        ONode latestCronRun = cronRunsData.get("runs").get(0);
        assertThat(latestCronRun.get("status").getString()).isEqualTo("ok");
        assertThat(latestCronRun.get("trigger").getString()).isEqualTo("manual");
        assertThat(latestCronRun.get("output").getString())
                .contains("dashboard trigger ok: daily summary");
        assertThat(latestCronRun.get("delivery_result").get("skipped").getString()).isEqualTo("local");
        assertThat(latestCronRun.get("delivery_result").toJson()).contains("\"targets\":[]");

        HttpResult apiCronRuns =
                request("GET", "/api/cron/jobs/" + dashboardCronId + "/runs?limit=5", null, token);
        assertThat(apiCronRuns.status).isEqualTo(200);
        ONode apiCronRunsData = ONode.ofJson(apiCronRuns.body).get("data");
        assertThat(apiCronRunsData.get("job_id").getString()).isEqualTo(dashboardCronId);
        assertThat(apiCronRunsData.get("count").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(apiCronRunsData.get("runs").get(0).get("run_id").getString()).isNotBlank();
        assertThat(apiCronRunsData.get("runs").get(0).get("output").getString())
                .contains("dashboard trigger ok: daily summary");

        HttpResult apiPutCron =
                request(
                        "PUT",
                        "/api/cron/jobs/" + dashboardCronId,
                        "{\"name\":\"Daily summary via API\",\"schedule\":\"0 10 * * *\"}",
                        token);
        assertThat(apiPutCron.status).isEqualTo(200);
        assertThat(apiPutCron.body)
                .contains("\"name\":\"Daily summary via API\"")
                .contains("\"schedule_display\":\"0 10 * * *\"");

        HttpResult apiTriggerCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/trigger", "{}", token);
        assertThat(apiTriggerCron.status).isEqualTo(200);
        assertThat(apiTriggerCron.body).contains("\"data\"").contains("\"last_status\":\"ok\"");

        HttpResult apiCronHistory =
                request("GET", "/api/cron/jobs/" + dashboardCronId + "/runs?limit=2", null, token);
        assertThat(apiCronHistory.status).isEqualTo(200);
        ONode apiCronHistoryData = ONode.ofJson(apiCronHistory.body).get("data");
        assertThat(apiCronHistoryData.get("job_id").getString()).isEqualTo(dashboardCronId);
        assertThat(apiCronHistoryData.get("count").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(apiCronHistoryData.get("runs").get(0).get("status").getString()).isEqualTo("ok");

        HttpResult cronInspect =
                request("GET", "/api/cron/jobs/" + dashboardCronId + "/inspect?limit=1", null, token);
        assertThat(cronInspect.status).isEqualTo(200);
        ONode cronInspectData = ONode.ofJson(cronInspect.body).get("data");
        assertThat(cronInspectData.get("job").get("id").getString()).isEqualTo(dashboardCronId);
        assertThat(cronInspectData.get("run_count").getInt()).isEqualTo(1);
        assertThat(cronInspectData.get("limit").getInt()).isEqualTo(1);
        assertThat(cronInspectData.get("runs").get(0).get("output").getString())
                .contains("dashboard trigger ok: daily summary");

        HttpResult apiCronInspect =
                request("GET", "/api/cron/jobs/" + dashboardCronId + "/inspect?limit=1", null, token);
        assertThat(apiCronInspect.status).isEqualTo(200);
        ONode apiCronInspectData = ONode.ofJson(apiCronInspect.body).get("data");
        assertThat(apiCronInspectData.get("job").get("id").getString()).isEqualTo(dashboardCronId);
        assertThat(apiCronInspectData.get("run_count").getInt()).isEqualTo(1);
        assertThat(apiCronInspectData.get("runs").get(0).get("status").getString()).isEqualTo("ok");

        HttpResult cronStatus = request("GET", "/api/cron/jobs/status?limit=2", null, token);
        assertThat(cronStatus.status).isEqualTo(200);
        ONode cronStatusData = ONode.ofJson(cronStatus.body).get("data");
        assertThat(cronStatusData.get("total").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(cronStatusData.get("active").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(cronStatusData.get("due").getInt()).isGreaterThanOrEqualTo(0);

        HttpResult apiJobsStatus = request("GET", "/api/cron/jobs/status?limit=2", null, token);
        assertThat(apiJobsStatus.status).isEqualTo(200);
        ONode apiJobsStatusData = ONode.ofJson(apiJobsStatus.body).get("data");
        assertThat(apiJobsStatusData.get("total").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(apiJobsStatusData.get("active").getInt()).isGreaterThanOrEqualTo(1);

        HttpResult pauseCron =
                request(
                        "POST",
                        "/api/cron/jobs/" + dashboardCronId + "/pause",
                        "{\"reason\":\"dashboard maintenance\"}",
                        token);
        assertThat(pauseCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(pauseCron.body).get("data").get("paused_reason").getString())
                .isEqualTo("dashboard maintenance");
        HttpResult enableCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/resume", "{}", token);
        assertThat(enableCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(enableCron.body).get("data").get("enabled").getBoolean()).isTrue();
        HttpResult stopCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/pause", "{}", token);
        assertThat(stopCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(stopCron.body).get("data").get("enabled").getBoolean()).isFalse();
        HttpResult apiJobsStatusWithStopped =
                request("GET", "/api/cron/jobs/status?include_disabled=true&limit=2", null, token);
        assertThat(apiJobsStatusWithStopped.status).isEqualTo(200);
        ONode apiJobsStatusWithStoppedData = ONode.ofJson(apiJobsStatusWithStopped.body).get("data");
        assertThat(apiJobsStatusWithStoppedData.get("total").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(apiJobsStatusWithStoppedData.get("paused").getInt()).isGreaterThanOrEqualTo(1);

        HttpResult startCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/resume", "{}", token);
        assertThat(startCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(startCron.body).get("data").get("enabled").getBoolean()).isTrue();
        HttpResult disableCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/pause", "{}", token);
        assertThat(disableCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(disableCron.body).get("data").get("enabled").getBoolean()).isFalse();
        HttpResult resumeCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/resume", "{}", token);
        assertThat(resumeCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(resumeCron.body).get("data").get("enabled").getBoolean()).isTrue();
        HttpResult runCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/trigger", "{}", token);
        assertThat(runCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(runCron.body).get("data").get("id").getString()).isEqualTo(dashboardCronId);
        HttpResult retryCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/retry", "{}", token);
        assertThat(retryCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(retryCron.body).get("data").get("id").getString()).isEqualTo(dashboardCronId);
        HttpResult invalidMcp =
                request(
                        "POST",
                        "/api/mcp",
                        "{\"serverId\":\"bad-mcp\",\"token\":\"ghp_invalidmcp12345\"",
                        token);
        assertThat(invalidMcp.status).isEqualTo(400);
        assertThat(invalidMcp.body)
                .contains("\"success\":false")
                .contains("\"code\":\"MCP_BAD_REQUEST\"")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_invalidmcp12345")
                .doesNotContain("bad-mcp");

        HttpResult createMcp =
                request(
                        "POST",
                        "/api/mcp",
                        "{\"serverId\":\"dashboard-local-docs\",\"name\":\"Local Docs\",\"transport\":\"stdio\",\"command\":\"docs-mcp\",\"args\":[\"--stdio\"],\"oauth\":{\"enabled\":true,\"provider\":\"github\",\"status\":\"pending\"},\"capabilities\":{\"resources\":true,\"tools\":true},\"tools\":[{\"name\":\"docs_search\",\"description\":\"Search docs\"}]}",
                        token);
        assertThat(createMcp.status).isEqualTo(200);

        HttpResult checkMcp =
                request("POST", "/api/mcp/dashboard-local-docs/check", "{}", token);
        assertThat(checkMcp.status).isEqualTo(200);
        assertThat(checkMcp.body).contains("\"status\":\"disabled\"");
        assertThat(checkMcp.body)
                .contains("\"tool_changed_notification\"")
                .contains("\"added_tools\"")
                .contains("\"removed_tools\"")
                .contains("\"schema_sanitizer\":\"snack4\"")
                .contains("\"security\":{\"allowed\":true,\"reason\":\"allow\"}");

        HttpResult checkMcpAgain =
                request("POST", "/api/mcp/dashboard-local-docs/check", "{}", token);
        assertThat(checkMcpAgain.status).isEqualTo(200);
        assertThat(checkMcpAgain.body).contains("\"tool_changed_notification\":false");

        HttpResult updateMcp =
                request(
                        "POST",
                        "/api/mcp",
                        "{\"serverId\":\"dashboard-local-docs\",\"name\":\"Local Docs\",\"transport\":\"stdio\",\"command\":\"docs-mcp\",\"args\":[\"--stdio\"],\"oauth\":{\"enabled\":true,\"provider\":\"github\",\"status\":\"pending\"},\"capabilities\":{\"resources\":true,\"tools\":true},\"tools\":[{\"name\":\"docs_search\",\"description\":\"Search docs\"},{\"name\":\"docs_fetch\",\"description\":\"Fetch docs\"}]}",
                        token);
        assertThat(updateMcp.status).isEqualTo(200);

        HttpResult changedMcp =
                request("POST", "/api/mcp/dashboard-local-docs/check", "{}", token);
        assertThat(changedMcp.status).isEqualTo(200);
        assertThat(changedMcp.body).contains("\"status\":\"disabled\"");
        assertThat(changedMcp.body)
                .contains("\"tool_changed_notification\"")
                .contains("\"schema_sanitizer\":\"snack4\"");

        HttpResult removeMcpTool =
                request(
                        "POST",
                        "/api/mcp",
                        "{\"serverId\":\"dashboard-local-docs\",\"name\":\"Local Docs\",\"transport\":\"stdio\",\"command\":\"docs-mcp\",\"args\":[\"--stdio\"],\"oauth\":{\"enabled\":true,\"provider\":\"github\",\"status\":\"pending\"},\"capabilities\":{\"resources\":true,\"tools\":true},\"tools\":[{\"name\":\"docs_fetch\",\"description\":\"Fetch docs\"}]}",
                        token);
        assertThat(removeMcpTool.status).isEqualTo(200);

        HttpResult removedMcp =
                request("POST", "/api/mcp/dashboard-local-docs/check", "{}", token);
        assertThat(removedMcp.status).isEqualTo(200);
        assertThat(removedMcp.body)
                .contains("\"tool_changed_notification\"")
                .contains("\"added_tools\"")
                .contains("\"removed_tools\"");

        HttpResult updateMcpForReloadAll =
                request(
                        "POST",
                        "/api/mcp",
                        "{\"serverId\":\"dashboard-local-docs\",\"name\":\"Local Docs\",\"transport\":\"stdio\",\"command\":\"docs-mcp\",\"args\":[\"--stdio\"],\"oauth\":{\"enabled\":true,\"provider\":\"github\",\"status\":\"pending\"},\"capabilities\":{\"resources\":true,\"tools\":true},\"tools\":[{\"name\":\"docs_search\",\"description\":\"Search docs\"},{\"name\":\"docs_fetch\",\"description\":\"Fetch docs\"},{\"name\":\"docs_rank\",\"description\":\"Rank docs\"}]}",
                        token);
        assertThat(updateMcpForReloadAll.status).isEqualTo(200);

        HttpResult reloadAllMcp =
                request("POST", "/api/mcp/reload", "{}", token);
        assertThat(reloadAllMcp.status).isEqualTo(200);
        ONode reloadAllMcpData = ONode.ofJson(reloadAllMcp.body).get("data");
        assertThat(reloadAllMcpData.get("tool_count").getInt()).isGreaterThanOrEqualTo(3);
        assertThat(reloadAllMcpData.get("server_count").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(stringsAt(reloadAllMcp.body, "changed_servers")).contains("dashboard-local-docs");
        assertThat(reloadAllMcp.body).contains("\"tool_changed_notification\":true");

        HttpResult reloadAllMcpAgain =
                request("POST", "/api/mcp/reload", "{}", token);
        assertThat(reloadAllMcpAgain.status).isEqualTo(200);
        assertThat(stringsAt(reloadAllMcpAgain.body, "unchanged_servers")).contains("dashboard-local-docs");

        HttpResult mcpList = request("GET", "/api/mcp", null, token);
        assertThat(mcpList.status).isEqualTo(200);
        assertThat(mcpList.body).contains("Local Docs");
        assertThat(mcpList.body).contains("\"oauth\"");
        assertThat(mcpList.body).contains("\"capabilities\"");
        assertThat(mcpList.body).contains("\"last_tools_hash\"");

        HttpResult secretToolMcp =
                request(
                        "POST",
                        "/api/mcp",
                        "{\"serverId\":\"secret-tool-docs\",\"name\":\"Secret Tool Docs\",\"transport\":\"stdio\",\"command\":\"docs-mcp\",\"tools\":[{\"name\":\"docs_secret\",\"title\":\"Read with token=secret-title-token\",\"description\":\"Use bearer ghp_toolsecret12345\",\"input_schema\":{\"type\":\"object\",\"properties\":{\"api_key\":{\"type\":\"string\",\"description\":\"OPENAI_API_KEY=sk-test-tool-secret\"}}},\"output_schema\":{\"type\":\"object\",\"properties\":{\"access_token\":{\"type\":\"string\",\"description\":\"secret-output-token\"}}}}]}",
                        token);
        assertThat(secretToolMcp.status).isEqualTo(200);
        HttpResult secretToolMcpList = request("GET", "/api/mcp", null, token);
        assertThat(secretToolMcpList.status).isEqualTo(200);
        assertThat(secretToolMcpList.body)
                .contains("token=***")
                .contains("bearer ***")
                .contains("\"description\":\"***\"")
                .doesNotContain("secret-title-token")
                .doesNotContain("ghp_toolsecret12345")
                .doesNotContain("OPENAI_API_KEY")
                .doesNotContain("sk-test-tool-secret")
                .doesNotContain("secret-output-token");

        HttpResult secretChangedToolMcp =
                request(
                        "POST",
                        "/api/mcp",
                        "{\"serverId\":\"secret-changed-tool-docs\",\"name\":\"Secret Changed Tool Docs\",\"transport\":\"stdio\",\"command\":\"docs-mcp\",\"tools\":[{\"name\":\"docs_token_ghp_mcpchanged12345\",\"description\":\"Search docs\"}]}",
                        token);
        assertThat(secretChangedToolMcp.status).isEqualTo(200);
        HttpResult secretChangedToolCheck =
                request("POST", "/api/mcp/secret-changed-tool-docs/check", "{}", token);
        assertThat(secretChangedToolCheck.status).isEqualTo(200);
        assertThat(stringsAt(secretChangedToolCheck.body, "added_tools"))
                .containsExactly("docs_token_ghp_***");
        assertThat(secretChangedToolCheck.body).doesNotContain("ghp_mcpchanged12345");

        HttpResult secretMcp =
                request(
                        "POST",
                        "/api/mcp",
                        "{\"serverId\":\"secret-stdio-docs\",\"name\":\"Secret Stdio\",\"transport\":\"http\",\"endpoint\":\"https://example.com/sse\",\"command\":\"OPENAI_API_KEY=sk-test-dashboard-secret docs-mcp\",\"args\":[\"--token=secret-arg-value\",\"--stdio\"],\"auth\":{\"header\":\"Authorization: Bearer ghp_mcpsecret12345\"}}",
                        token);
        assertThat(secretMcp.status).isEqualTo(200);
        HttpResult userInfoMcp =
                request(
                        "POST",
                        "/api/mcp",
                        "{\"serverId\":\"userinfo-docs\",\"name\":\"Userinfo Docs\",\"transport\":\"http\",\"endpoint\":\"https://user:secret-endpoint-pass@example.com/sse?token=secret-userinfo-token\",\"tools\":[{\"name\":\"docs_search\"}]}",
                        token);
        assertThat(userInfoMcp.status).isEqualTo(400);
        assertThat(userInfoMcp.body)
                .contains("MCP_BAD_REQUEST")
                .contains("[REDACTED_PATH]")
                .doesNotContain("secret-endpoint-pass")
                .doesNotContain("secret-userinfo-token");
        HttpResult secretMcpList = request("GET", "/api/mcp", null, token);
        assertThat(secretMcpList.status).isEqualTo(200);
        assertThat(secretMcpList.body)
                .contains("OPENAI_API_KEY=***")
                .contains("--token=***")
                .contains("Authorization: Bearer ***")
                .contains("https://example.com/sse")
                .doesNotContain("sk-test-dashboard-secret")
                .doesNotContain("secret-arg-value")
                .doesNotContain("secret-endpoint-pass")
                .doesNotContain("secret-userinfo-token")
                .doesNotContain("ghp_mcpsecret12345");

        HttpResult updateMcpOAuth =
                request(
                        "POST",
                        "/api/mcp",
                        "{\"serverId\":\"oauth-docs\",\"name\":\"OAuth Docs\",\"transport\":\"http\",\"endpoint\":\"https://example.com/sse\",\"oauth\":{\"enabled\":true,\"provider\":\"github\",\"auth_type\":\"oauth_pkce\",\"access_token\":\"secret-access\",\"refresh_token\":\"secret-refresh\",\"client_secret\":\"secret-client\",\"expires_at\":4102444800000,\"scopes\":[\"repo\"]},\"tools\":[{\"name\":\"docs_search\"}]}",
                        token);
        assertThat(updateMcpOAuth.status).isEqualTo(200);

        HttpResult oauthStatus =
                request("GET", "/api/mcp/oauth-docs/oauth/status", null, token);
        assertThat(oauthStatus.status).isEqualTo(200);
        assertThat(oauthStatus.body).contains("\"status\":\"authenticated\"");
        assertThat(oauthStatus.body).contains("\"has_access_token\":true");
        assertThat(oauthStatus.body).contains("\"has_refresh_token\":true");
        assertThat(oauthStatus.body).contains("\"has_client_secret\":true");
        assertThat(oauthStatus.body).doesNotContain("secret-access");
        assertThat(oauthStatus.body).doesNotContain("secret-refresh");

        HttpResult oauthErrorServer =
                request(
                        "POST",
                        "/api/mcp",
                        "{\"serverId\":\"oauth-error-docs\",\"name\":\"OAuth Error Docs\",\"transport\":\"http\",\"endpoint\":\"https://example.com/sse\",\"oauth\":{\"enabled\":true,\"status\":\"pending\",\"error\":\"access_token=ghp_oautherror12345&callback=http://localhost/cb?api%255Fkey=oauth-encoded-secret&token=secret-oauth-error\",\"message\":\"client_secret=oauth-message-secret https://example.test/callback#refresh_token=oauth-fragment-secret\"},\"tools\":[{\"name\":\"docs_search\"}]}",
                        token);
        assertThat(oauthErrorServer.status).isEqualTo(200);
        HttpResult oauthErrorStatus =
                request("GET", "/api/mcp/oauth-error-docs/oauth/status", null, token);
        assertThat(oauthErrorStatus.status).isEqualTo(200);
        assertThat(oauthErrorStatus.body).contains("access_token=***");
        assertThat(oauthErrorStatus.body).contains("api%255Fkey=***");
        assertThat(oauthErrorStatus.body).contains("refresh_token=***");
        assertThat(oauthErrorStatus.body).contains("client_secret=***");
        assertThat(oauthErrorStatus.body)
                .doesNotContain("ghp_oautherror12345")
                .doesNotContain("secret-oauth-error")
                .doesNotContain("oauth-encoded-secret")
                .doesNotContain("oauth-fragment-secret")
                .doesNotContain("oauth-message-secret");

        HttpResult mcpListWithOAuth = request("GET", "/api/mcp", null, token);
        assertThat(mcpListWithOAuth.body).contains("\"has_access_token\":true");
        assertThat(mcpListWithOAuth.body).doesNotContain("secret-access");

        HttpResult beginOAuth =
                request(
                        "POST",
                        "/api/mcp/oauth-docs/oauth/begin",
                        "{\"authorization_endpoint\":\"https://example.com/oauth/authorize\",\"token_endpoint\":\""
                                + "https://example.com/oauth/token"
                                + "\",\"client_id\":\"client-1\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\",\"scopes\":[\"repo\",\"read:user\"]}",
                        token);
        assertThat(beginOAuth.status).isEqualTo(200);
        assertThat(beginOAuth.body).contains("\"status\":\"pending\"");
        assertThat(beginOAuth.body).contains("https://example.com/oauth/authorize");
        assertThat(beginOAuth.body).contains("code_challenge_method=S256");
        assertThat(beginOAuth.body).contains("scope=repo%20read%3Auser");
        assertThat(beginOAuth.body).contains("\"has_code_verifier\":true");
        assertThat(beginOAuth.body).doesNotContain("\"code_verifier\":\"");

        HttpResult oauthCallbackError =
                request(
                        "GET",
                        "/api/mcp/oauth-docs/oauth/callback?error="
                                + URLEncoder.encode(
                                        "access_token=ghp_callbackerror12345&redirect_uri=http://localhost/cb?token=secret-callback-error",
                                        "UTF-8"),
                        null,
                        null);
        assertThat(oauthCallbackError.status).isEqualTo(400);
        assertThat(oauthCallbackError.body)
                .contains("MCP_BAD_REQUEST")
                .contains("access_token=***")
                .contains("token=***")
                .doesNotContain("ghp_callbackerror12345")
                .doesNotContain("secret-callback-error");

        HttpResult blockedAuthorizationEndpoint =
                request(
                        "POST",
                        "/api/mcp/oauth-docs/oauth/begin",
                        "{\"authorization_endpoint\":\"http://169.254.169.254/latest/meta-data/?token=secret-auth\",\"client_id\":\"client-1\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\"}",
                        token);
        assertThat(blockedAuthorizationEndpoint.status).isEqualTo(400);
        assertThat(blockedAuthorizationEndpoint.body)
                .contains("MCP_BAD_REQUEST")
                .contains("token=***")
                .doesNotContain("secret-auth");

        HttpResult pendingStatus =
                request("GET", "/api/mcp/oauth-docs/oauth/status", null, token);
        assertThat(pendingStatus.body).contains("\"status\":\"pending\"");
        assertThat(pendingStatus.body).contains("\"has_access_token\":false");
        assertThat(pendingStatus.body).contains("\"has_code_verifier\":true");

        String pendingState = ONode.ofJson(pendingStatus.body).get("data").get("oauth").get("state").getString();
        assertThat(pendingState).isNotBlank();

        HttpResult blockedTokenEndpoint =
                request(
                        "POST",
                        "/api/mcp/oauth-docs/oauth/callback",
                        "{\"code\":\"auth-code-blocked\",\"state\":\""
                                + pendingState
                                + "\",\"token_endpoint\":\"http://169.254.169.254/latest/meta-data/?token=secret-token\"}",
                        token);
        assertThat(blockedTokenEndpoint.status).isEqualTo(400);
        assertThat(blockedTokenEndpoint.body)
                .contains("MCP_BAD_REQUEST")
                .contains("token=***")
                .doesNotContain("secret-token");

        HttpResult stateMismatch =
                request(
                        "POST",
                        "/api/mcp/oauth-docs/oauth/callback",
                        "{\"code\":\"bad-code\",\"state\":\"wrong\",\"token_endpoint\":\"http://127.0.0.1:1/token\"}",
                        token);
        assertThat(stateMismatch.status).isEqualTo(400);
        assertThat(stateMismatch.body).contains("MCP_BAD_REQUEST");

        TokenEndpointStub tokenEndpoint = TokenEndpointStub.start();
        try {
            beginOAuth =
                    request(
                            "POST",
                            "/api/mcp/oauth-docs/oauth/begin",
                            "{\"authorization_endpoint\":\"https://example.com/oauth/authorize\",\"token_endpoint\":\""
                                    + tokenEndpoint.url()
                                    + "\",\"client_id\":\"client-1\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\",\"scopes\":[\"repo\",\"read:user\"]}",
                            token);
            pendingStatus =
                    request("GET", "/api/mcp/oauth-docs/oauth/status", null, token);
            pendingState =
                    ONode.ofJson(pendingStatus.body).get("data").get("oauth").get("state").getString();
            tokenEndpoint.failNextTokenResponse();
            HttpResult tokenEndpointError =
                    request(
                            "GET",
                            "/api/mcp/oauth-docs/oauth/callback"
                                    + "?code=auth-code-error&state="
                                    + URLEncoder.encode(pendingState, "UTF-8"),
                            null,
                            null);
            assertThat(tokenEndpointError.status).isEqualTo(400);
            assertThat(tokenEndpointError.body)
                    .contains("MCP_BAD_REQUEST")
                    .contains("api%255Fkey=***")
                    .contains("token=***")
                    .contains("refresh_token=***")
                    .contains("client_secret=***")
                    .doesNotContain("ghp_tokenerror12345")
                    .doesNotContain("token-error-encoded")
                    .doesNotContain("token-error-secret")
                    .doesNotContain("token-error-client")
                    .doesNotContain("token-error-fragment");

            beginOAuth =
                    request(
                            "POST",
                            "/api/mcp/oauth-docs/oauth/begin",
                            "{\"authorization_endpoint\":\"https://example.com/oauth/authorize\",\"token_endpoint\":\""
                                    + tokenEndpoint.url()
                                    + "\",\"client_id\":\"client-1\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\",\"scopes\":[\"repo\",\"read:user\"]}",
                            token);
            pendingStatus =
                    request("GET", "/api/mcp/oauth-docs/oauth/status", null, token);
            pendingState =
                    ONode.ofJson(pendingStatus.body).get("data").get("oauth").get("state").getString();
            HttpResult completeOAuth =
                    request(
                            "GET",
                            "/api/mcp/oauth-docs/oauth/callback"
                                    + "?code=auth-code-1&state="
                                    + URLEncoder.encode(pendingState, "UTF-8"),
                            null,
                            null);
            assertThat(completeOAuth.status).isEqualTo(200);
            assertThat(completeOAuth.body).contains("\"status\":\"authenticated\"");
            assertThat(completeOAuth.body).contains("\"has_access_token\":true");
            assertThat(completeOAuth.body).contains("\"has_refresh_token\":true");
            assertThat(completeOAuth.body).doesNotContain("token-secret-1");
            assertThat(completeOAuth.body).doesNotContain("refresh-secret-1");
            assertThat(completeOAuth.body).doesNotContain("code_verifier");
            assertThat(tokenEndpoint.lastForm.get("grant_type")).isEqualTo("authorization_code");
            assertThat(tokenEndpoint.lastForm.get("code")).isEqualTo("auth-code-1");
            assertThat(tokenEndpoint.lastForm.get("client_id")).isEqualTo("client-1");
            assertThat(tokenEndpoint.lastForm.get("code_verifier")).isNotBlank();

            HttpResult authenticatedStatus =
                    request("GET", "/api/mcp/oauth-docs/oauth/status", null, token);
            assertThat(authenticatedStatus.body).contains("\"status\":\"authenticated\"");
            assertThat(authenticatedStatus.body).contains("\"has_access_token\":true");
            assertThat(authenticatedStatus.body).doesNotContain("token-secret-1");

            tokenEndpoint.failNextTokenResponse();
            HttpResult refreshTokenEndpointError =
                    request(
                            "POST",
                            "/api/mcp/oauth-docs/oauth/refresh",
                            "{}",
                            token);
            assertThat(refreshTokenEndpointError.status).isEqualTo(400);
            assertThat(refreshTokenEndpointError.body)
                    .contains("MCP_BAD_REQUEST")
                    .contains("api%255Fkey=***")
                    .contains("token=***")
                    .contains("refresh_token=***")
                    .contains("client_secret=***")
                    .doesNotContain("ghp_tokenerror12345")
                    .doesNotContain("token-error-encoded")
                    .doesNotContain("token-error-secret")
                    .doesNotContain("token-error-client")
                    .doesNotContain("token-error-fragment");

            HttpResult refreshOAuth =
                    request(
                            "POST",
                            "/api/mcp/oauth-docs/oauth/refresh",
                            "{}",
                            token);
            assertThat(refreshOAuth.status).isEqualTo(200);
            assertThat(refreshOAuth.body).contains("\"refreshed\":true");
            assertThat(refreshOAuth.body).contains("\"reconnect_required\":true");
            assertThat(refreshOAuth.body).contains("\"has_access_token\":true");
            assertThat(refreshOAuth.body).doesNotContain("token-secret-");
            assertThat(refreshOAuth.body).doesNotContain("refresh-secret-");
            Map<String, String> firstRefreshForm =
                    tokenEndpoint.firstFormByRefreshToken("refresh-secret-1");
            assertThat(firstRefreshForm).isNotNull();
            assertThat(firstRefreshForm.get("grant_type")).isEqualTo("refresh_token");
            String refreshedToken = tokenEndpoint.lastIssuedRefreshToken();
            assertThat(refreshedToken).isNotBlank();
            assertThat(refreshedToken).isNotEqualTo("refresh-secret-1");

            tokenEndpoint.failNextTokenResponse();
            HttpResult handle401RefreshError =
                    request(
                            "POST",
                            "/api/mcp/oauth-docs/oauth/handle-401",
                            "{}",
                            token);
            assertThat(handle401RefreshError.status).isEqualTo(200);
            assertThat(handle401RefreshError.body)
                    .contains("\"recovered\":false")
                    .contains("\"needs_reauth\":true")
                    .contains("\"reason\":\"refresh_failed\"")
                    .contains("api%255Fkey=***")
                    .contains("token=***")
                    .contains("refresh_token=***")
                    .contains("client_secret=***")
                    .doesNotContain("ghp_tokenerror12345")
                    .doesNotContain("token-error-encoded")
                    .doesNotContain("token-error-secret")
                    .doesNotContain("token-error-client")
                    .doesNotContain("token-error-fragment");

            request(
                    "GET",
                    "/api/mcp/oauth-docs/oauth/callback"
                            + "?error="
                            + URLEncoder.encode("reset-pending-after-refresh-error", "UTF-8"),
                    null,
                    null);
            beginOAuth =
                    request(
                            "POST",
                            "/api/mcp/oauth-docs/oauth/begin",
                            "{\"authorization_endpoint\":\"https://example.com/oauth/authorize\",\"token_endpoint\":\""
                                    + tokenEndpoint.url()
                                    + "\",\"client_id\":\"client-1\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\",\"scopes\":[\"repo\",\"read:user\"]}",
                            token);
            pendingStatus =
                    request("GET", "/api/mcp/oauth-docs/oauth/status", null, token);
            pendingState =
                    ONode.ofJson(pendingStatus.body).get("data").get("oauth").get("state").getString();
            completeOAuth =
                    request(
                            "GET",
                            "/api/mcp/oauth-docs/oauth/callback"
                                    + "?code=auth-code-recover&state="
                                    + URLEncoder.encode(pendingState, "UTF-8"),
                            null,
                            null);
            assertThat(completeOAuth.status).isEqualTo(200);
            refreshedToken = tokenEndpoint.lastIssuedRefreshToken();

            HttpResult handle401 =
                    request(
                            "POST",
                            "/api/mcp/oauth-docs/oauth/handle-401",
                            "{}",
                            token);
            assertThat(handle401.status).isEqualTo(200);
            assertThat(handle401.body).contains("\"recovered\":true");
            assertThat(handle401.body).contains("\"needs_reauth\":false");
            assertThat(handle401.body).contains("\"reconnect_required\":true");
            assertThat(handle401.body).doesNotContain("token-secret-");
            assertThat(handle401.body).doesNotContain("refresh-secret-");
            Map<String, String> recoveryRefreshForm =
                    tokenEndpoint.firstFormByRefreshToken(refreshedToken);
            assertThat(recoveryRefreshForm).isNotNull();

            beginOAuth =
                    request(
                            "POST",
                            "/api/mcp/oauth-docs/oauth/begin",
                            "{\"authorization_endpoint\":\"https://example.com/oauth/authorize\",\"token_endpoint\":\""
                                    + tokenEndpoint.redirectUrl()
                                    + "\",\"client_id\":\"client-1\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\",\"scopes\":[\"repo\",\"read:user\"]}",
                            token);
            pendingStatus =
                    request("GET", "/api/mcp/oauth-docs/oauth/status", null, token);
            pendingState =
                    ONode.ofJson(pendingStatus.body).get("data").get("oauth").get("state").getString();
            HttpResult redirectedTokenEndpoint =
                    request(
                            "GET",
                            "/api/mcp/oauth-docs/oauth/callback"
                                    + "?code=auth-code-redirect&state="
                                    + URLEncoder.encode(pendingState, "UTF-8"),
                            null,
                            null);
            assertThat(redirectedTokenEndpoint.status).isEqualTo(400);
            assertThat(redirectedTokenEndpoint.body)
                    .contains("MCP_BAD_REQUEST")
                    .doesNotContain("secret-redirect");
            assertThat(tokenEndpoint.redirectForm.get("grant_type"))
                    .isEqualTo("authorization_code");
            assertThat(tokenEndpoint.redirectForm.get("code")).isEqualTo("auth-code-redirect");
        } finally {
            tokenEndpoint.stop();
        }

        HttpResult blockedRefreshServer =
                request(
                        "POST",
                        "/api/mcp",
                        "{\"serverId\":\"blocked-oauth-docs\",\"name\":\"Blocked OAuth Docs\",\"transport\":\"http\",\"endpoint\":\"https://example.com/sse\",\"oauth\":{\"enabled\":true,\"status\":\"authenticated\",\"client_id\":\"client-1\",\"access_token\":\"secret-access\",\"refresh_token\":\"refresh-secret\",\"token_endpoint\":\"http://169.254.169.254/latest/meta-data/?token=secret-refresh-url\"},\"tools\":[{\"name\":\"docs_search\"}]}",
                        token);
        assertThat(blockedRefreshServer.status).isEqualTo(200);
        HttpResult blockedRefresh =
                request(
                        "POST",
                        "/api/mcp/blocked-oauth-docs/oauth/refresh",
                        "{}",
                        token);
        assertThat(blockedRefresh.status).isEqualTo(400);
        assertThat(blockedRefresh.body)
                .contains("MCP_BAD_REQUEST")
                .contains("token=***")
                .doesNotContain("secret-refresh-url");

        HttpResult clearOAuth =
                request("POST", "/api/mcp/oauth-docs/oauth/clear", "{}", token);
        assertThat(clearOAuth.status).isEqualTo(200);
        assertThat(clearOAuth.body).contains("\"cleared\":true");
        assertThat(clearOAuth.body).doesNotContain("secret-refresh");

        HttpResult clearedStatus =
                request("GET", "/api/mcp/oauth-docs/oauth/status", null, token);
        assertThat(clearedStatus.body).contains("\"status\":\"cleared\"");
        assertThat(clearedStatus.body).contains("\"has_access_token\":false");

        HttpResult handle401AfterClear =
                request(
                        "POST",
                        "/api/mcp/oauth-docs/oauth/handle-401",
                        "{}",
                        token);
        assertThat(handle401AfterClear.status).isEqualTo(200);
        assertThat(handle401AfterClear.body).contains("\"needs_reauth\":true");
        assertThat(handle401AfterClear.body).contains("\"reconnect_required\":false");

        HttpResult logs = request("GET", "/api/logs?file=agent&lines=20", null, token);
        assertThat(logs.status).isEqualTo(200);
        assertThat(logs.body).contains("\"lines\"");
    }

    @Test
    void shouldListAndResolvePendingDashboardApprovals() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        seedPendingApproval(
                "dashboard-approval-chat",
                "MEMORY:dashboard-approval-chat:dashboard-user",
                "Dashboard approval session",
                "printf api_key=sk-test-secret-token-value\u202E",
                "需要确认危险命令 Authorization: Bearer ghp_dashboardsecret12345\u202E");

        HttpResult pending =
                request("GET", "/api/diagnostics/approvals?limit=20", null, token);
        assertThat(pending.status).isEqualTo(200);
        assertThat(pending.body)
                .contains("\"count\"")
                .contains("\"session_id\":\"dashboard-approval-chat\"")
                .contains("\"source_ref\"")
                .contains("\"tool_name\":\"execute_shell\"")
                .contains("\"command_preview\":\"printf api_key=***\"")
                .contains("\"expires_in_seconds\"")
                .contains("\"expired\":false")
                .contains("\"scope_options\":[\"once\",\"session\",\"always\"]")
                .contains("\"permanent_allowed\":true")
                .doesNotContain("MEMORY:dashboard-approval-chat:dashboard-user")
                .doesNotContain("\"source_key\"")
                .doesNotContain("sk-test-secret-token-value")
                .doesNotContain("ghp_dashboardsecret12345")
                .contains("Authorization: Bearer ***");

        ONode pendingData = ONode.ofJson(pending.body).get("data").get("items").get(0);
        String selector = pendingData.get("selector").getString();
        assertThat(selector).isNotBlank();
        assertThat(selector).isEqualTo(pendingData.get("approval_id").getString());
        assertThat(selector).doesNotContain("execute_shell:");
        assertThat(pending.body).doesNotContain("\"approval_key\":");

        HttpResult historyBefore =
                request("GET", "/api/diagnostics/approvals/history?limit=20", null, token);
        assertThat(historyBefore.status).isEqualTo(200);
        assertThat(historyBefore.body)
                .contains("\"event_type\":\"request\"")
                .contains("\"command_preview\":\"printf api_key=***\"")
                .doesNotContain("\\u202E")
                .doesNotContain("sk-test-secret-token-value")
                .doesNotContain("ghp_dashboardsecret12345")
                .contains("Authorization: Bearer ***");

        HttpResult resolve =
                request(
                        "POST",
                        "/api/diagnostics/approvals/resolve",
                        "{\"sessionId\":\"dashboard-approval-chat\",\"approvalId\":\""
                                + jsonEscape(selector)
                                + "\",\"action\":\"deny\",\"resume\":false}",
                        token);
        assertThat(resolve.status).isEqualTo(200);
        assertThat(resolve.body)
                .contains("\"success\":true")
                .contains("\"action\":\"deny\"")
                .contains("\"resumed\":false");

        HttpResult historyAfter =
                request("GET", "/api/diagnostics/approvals/history?limit=20", null, token);
        assertThat(historyAfter.status).isEqualTo(200);
        assertThat(historyAfter.body)
                .contains("\"event_type\":\"response\"")
                .contains("\"choice\":\"deny\"")
                .contains("\"approver\":\"dashboard\"")
                .contains("\"command_preview\":\"printf api_key=***\"")
                .doesNotContain("\\u202E")
                .doesNotContain("sk-test-secret-token-value")
                .doesNotContain("ghp_dashboardsecret12345")
                .contains("Authorization: Bearer ***");

        HttpResult after =
                request("GET", "/api/diagnostics/approvals?limit=20", null, token);
        assertThat(after.status).isEqualTo(200);
        assertThat(after.body).doesNotContain("\"session_id\":\"dashboard-approval-chat\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeSafeSelectorForUnsafeDashboardApprovalId() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        seedPendingApproval(
                "dashboard-unsafe-approval-chat",
                "MEMORY:dashboard-unsafe-approval-chat:dashboard-user",
                "Dashboard unsafe approval session",
                "rm -rf runtime/cache");
        SessionRepository repository = bean(SessionRepository.class);
        SessionRecord record = repository.findById("dashboard-unsafe-approval-chat");
        SqliteAgentSession agentSession = new SqliteAgentSession(record, repository);
        List<Map<String, Object>> queue =
                (List<Map<String, Object>>)
                        agentSession.getContext().get("_dangerous_command_pending_queue_");
        queue.get(0).put("approvalId", "approval-unsafe always");
        agentSession.getContext().put("_dangerous_command_pending_", queue.get(0));
        agentSession.updateSnapshot();

        HttpResult pending =
                request("GET", "/api/diagnostics/approvals?limit=20", null, token);
        assertThat(pending.status).isEqualTo(200);
        ONode pendingData = ONode.ofJson(pending.body).get("data").get("items").get(0);
        String selector = pendingData.get("selector").getString();
        String approvalId = pendingData.get("approval_id").getString();

        assertThat(selector).startsWith("key_");
        assertThat(approvalId).isEqualTo(selector);
        assertThat(pending.body).doesNotContain("approval-unsafe always");

        HttpResult resolve =
                request(
                        "POST",
                        "/api/diagnostics/approvals/resolve",
                        "{\"sessionId\":\"dashboard-unsafe-approval-chat\",\"approvalId\":\""
                                + jsonEscape(approvalId)
                                + "\",\"action\":\"deny\",\"resume\":false}",
                        token);
        assertThat(resolve.status).isEqualTo(200);
        assertThat(resolve.body).contains("\"success\":true").contains("\"action\":\"deny\"");
    }

    @Test
    void shouldListAndRevokeAlwaysDashboardApprovals() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        seedPendingApproval(
                "dashboard-always-chat",
                "MEMORY:dashboard-always-chat:dashboard-user",
                "Dashboard always approval session",
                "rm -rf runtime/cache");

        HttpResult pending =
                request("GET", "/api/diagnostics/approvals?limit=20", null, token);
        ONode pendingData = ONode.ofJson(pending.body).get("data").get("items").get(0);
        String selector = pendingData.get("selector").getString();
        assertThat(selector).isNotBlank();

        HttpResult approve =
                request(
                        "POST",
                        "/api/diagnostics/approvals/resolve",
                        "{\"sessionId\":\"dashboard-always-chat\",\"approvalId\":\""
                                + jsonEscape(selector)
                                + "\",\"action\":\"approve\",\"scope\":\"always\",\"resume\":false}",
                        token);
        assertThat(approve.status).isEqualTo(200);
        assertThat(approve.body).contains("\"success\":true").contains("\"action\":\"approve\"");

        HttpResult always =
                request("GET", "/api/diagnostics/approvals/always?limit=20", null, token);
        assertThat(always.status).isEqualTo(200);
        assertThat(always.body)
                .contains("\"count\"")
                .contains("\"tool_name\":\"execute_shell\"")
                .doesNotContain("\"approval\":");
        ONode alwaysData = ONode.ofJson(always.body).get("data").get("items").get(0);
        String approvalId = alwaysData.get("approval_id").getString();
        String patternKey = alwaysData.get("pattern_key").getString();
        assertThat(approvalId).isNotBlank();
        assertThat(patternKey).isNotBlank();
        assertThat(bean(DangerousCommandApprovalService.class)
                        .isAlwaysApproved("execute_shell", patternKey, "rm -rf runtime/logs"))
                .isTrue();

        HttpResult revoke =
                request(
                        "POST",
                        "/api/diagnostics/approvals/always/revoke",
                        "{\"approvalId\":\"" + jsonEscape(approvalId) + "\"}",
                        token);
        assertThat(revoke.status).isEqualTo(200);
        assertThat(revoke.body)
                .contains("\"success\":true")
                .contains("长期授权已撤销")
                .doesNotContain("\"approval_id\":")
                .doesNotContain("\"approval\":")
                .doesNotContain("\"approval\":\"execute_shell:rm_recursive_root:");
        assertThat(bean(DangerousCommandApprovalService.class)
                        .isAlwaysApproved("execute_shell", patternKey, "rm -rf runtime/logs"))
                .isFalse();

        HttpResult history =
                request("GET", "/api/diagnostics/approvals/history?limit=20", null, token);
        assertThat(history.status).isEqualTo(200);
        assertThat(history.body)
                .contains("\"choice\":\"revoke\"")
                .contains("\"approver\":\"dashboard\"")
                .doesNotContain("\"approval_key\":")
                .doesNotContain("execute_shell:rm_recursive_root:97c852eaef0753db")
                .contains("撤销长期审批授权");

        HttpResult after =
                request("GET", "/api/diagnostics/approvals/always?limit=20", null, token);
        assertThat(after.status).isEqualTo(200);
        assertThat(after.body).doesNotContain(approvalId);
    }

    @Test
    void shouldRejectRawAlwaysApprovalRevokeWithoutLeakingDashboardInput() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        seedPendingApproval(
                "dashboard-raw-always-chat",
                "MEMORY:dashboard-raw-always-chat:dashboard-user",
                "Dashboard raw always approval session",
                "curl https://example.test/callback?api_key=ghp_rawalwayssecret12345");

        HttpResult pending =
                request("GET", "/api/diagnostics/approvals?limit=20", null, token);
        ONode pendingData =
                findItemByStringField(
                        ONode.ofJson(pending.body).get("data").get("items"),
                        "session_id",
                        "dashboard-raw-always-chat");
        assertThat(pendingData).isNotNull();
        String selector = pendingData.get("selector").getString();

        HttpResult approve =
                request(
                        "POST",
                        "/api/diagnostics/approvals/resolve",
                        "{\"sessionId\":\"dashboard-raw-always-chat\",\"approvalId\":\""
                                + jsonEscape(selector)
                                + "\",\"action\":\"approve\",\"scope\":\"always\",\"resume\":false}",
                        token);
        assertThat(approve.status).isEqualTo(200);
        assertThat(approve.body).contains("\"success\":true");

        HttpResult alwaysBefore =
                request("GET", "/api/diagnostics/approvals/always?limit=20", null, token);
        assertThat(alwaysBefore.status).isEqualTo(200);
        assertThat(alwaysBefore.body).doesNotContain("rawalwayssecret12345");

        HttpResult rejected =
                request(
                        "POST",
                        "/api/diagnostics/approvals/always/revoke",
                        "{\"approval\":\"execute_shell:curl_url_query_api_key\","
                                + "\"approvalId\":\"execute_shell:curl_url_query_api_key:ghp_rawalwayssecret12345\"}",
                        token);
        assertThat(rejected.status).isEqualTo(200);
        assertThat(rejected.body)
                .contains("\"success\":false")
                .contains("\"code\":\"missing_approval\"")
                .doesNotContain("execute_shell:curl_url_query_api_key")
                .doesNotContain("ghp_rawalwayssecret12345")
                .doesNotContain("\"approval\":")
                .doesNotContain("\"approval_id\":");

        HttpResult alwaysAfter =
                request("GET", "/api/diagnostics/approvals/always?limit=20", null, token);
        assertThat(alwaysAfter.status).isEqualTo(200);
        assertThat(alwaysAfter.body)
                .contains("\"tool_name\":\"execute_shell\"")
                .doesNotContain("rawalwayssecret12345")
                .doesNotContain("\"approval\":");
    }

    @Test
    void shouldListAndResolvePendingSlashConfirmsFromDashboard() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        GatewayMessage commandMessage =
                new GatewayMessage(
                        PlatformType.MEMORY,
                        "dashboard-confirm-chat",
                        "dashboard-confirm-user",
                        "/reload-mcp");
        GatewayReply prompt = bean(CommandService.class).handle(commandMessage, "/reload-mcp");
        assertThat(prompt.getContent()).contains("确认编号");

        HttpResult confirms =
                request("GET", "/api/diagnostics/slash-confirms?limit=20", null, token);
        assertThat(confirms.status).isEqualTo(200);
        assertThat(confirms.body)
                .contains("\"command_preview\":\"reload-mcp\"")
                .contains("\"confirm_ref\"")
                .contains("\"source_ref\"")
                .contains("\"allow_always\":true")
                .contains("\"action_options\":[\"approve\",\"deny\",\"always\"]")
                .contains("\"expires_in_seconds\"")
                .contains("\"expired\":false")
                .doesNotContain("\"command\":\"reload-mcp\"")
                .doesNotContain("\"prompt\":")
                .doesNotContain("MEMORY:dashboard-confirm-chat:dashboard-confirm-user");
        ONode confirm =
                findItemByStringField(
                        ONode.ofJson(confirms.body).get("data").get("items"),
                        "command_preview",
                        "reload-mcp");
        assertThat(confirm).isNotNull();
        String confirmId = confirm.get("confirm_id").getString();
        assertThat(confirmId).isNotBlank();

        HttpResult resolve =
                request(
                        "POST",
                        "/api/diagnostics/slash-confirms/resolve",
                        "{\"confirmId\":\""
                                + jsonEscape(confirmId.substring(0, 8)
                                        + "\u202E"
                                        + confirmId.substring(8))
                                + "\",\"action\":\"deny\"}",
                        token);
        assertThat(resolve.status).isEqualTo(200);
        assertThat(resolve.body)
                .contains("\"success\":true")
                .contains("已取消 /reload-mcp");

        HttpResult after =
                request("GET", "/api/diagnostics/slash-confirms?limit=20", null, token);
        assertThat(after.status).isEqualTo(200);
        assertThat(after.body).doesNotContain("dashboard-confirm-chat");
    }

    @Test
    void shouldRejectDisallowedAlwaysSlashConfirmFromDashboard() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        bean(SlashConfirmService.class)
                .register(
                        "MEMORY:dashboard-confirm-once:dashboard-user",
                        "rollback",
                        "确认回滚？",
                        false);

        HttpResult confirms =
                request("GET", "/api/diagnostics/slash-confirms?limit=20", null, token);
        assertThat(confirms.status).isEqualTo(200);
        assertThat(confirms.body)
                .contains("\"command_preview\":\"rollback\"")
                .contains("\"source_ref\"")
                .contains("\"allow_always\":false")
                .contains("\"action_options\":[\"approve\",\"deny\"]")
                .doesNotContain("\"command\":\"rollback\"")
                .doesNotContain("MEMORY:dashboard-confirm-once:dashboard-user")
                .doesNotContain("\"action_options\":[\"approve\",\"deny\",\"always\"]");
        ONode confirm =
                findItemByStringField(
                        ONode.ofJson(confirms.body).get("data").get("items"),
                        "command_preview",
                        "rollback");
        assertThat(confirm).isNotNull();
        String confirmId = confirm.get("confirm_id").getString();

        HttpResult rejected =
                request(
                        "POST",
                        "/api/diagnostics/slash-confirms/resolve",
                        "{\"confirmId\":\""
                                + jsonEscape(confirmId)
                                + "\",\"action\":\"always\"}",
                        token);
        assertThat(rejected.status).isEqualTo(200);
        assertThat(rejected.body)
                .contains("\"success\":false")
                .contains("\"code\":\"always_not_allowed\"");

        HttpResult after =
                request("GET", "/api/diagnostics/slash-confirms?limit=20", null, token);
        assertThat(after.status).isEqualTo(200);
        assertThat(after.body)
                .contains("\"command_preview\":\"rollback\"")
                .contains("\"source_ref\"")
                .doesNotContain("dashboard-confirm-once");
    }

    @Test
    void shouldRedactSlashConfirmPromptFromDashboard() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        bean(SlashConfirmService.class)
                .register(
                        "MEMORY:dashboard-secret-confirm:user",
                        "reload-mcp --token=ghp_slashcommandsecret12345",
                        "确认刷新 Authorization: Bearer ghp_slashsecret12345");

        HttpResult confirms =
                request("GET", "/api/diagnostics/slash-confirms?limit=20", null, token);

        assertThat(confirms.status).isEqualTo(200);
        assertThat(confirms.body)
                .contains("\"prompt_preview\":\"确认刷新 Authorization: Bearer ***\"")
                .contains("\"command_preview\":\"reload-mcp --token=***\"")
                .contains("\"source_ref\"")
                .doesNotContain("\\u202E")
                .contains("Authorization: Bearer ***")
                .contains("reload-mcp --token=***")
                .doesNotContain("\"prompt\":")
                .doesNotContain("\"command\":")
                .doesNotContain("MEMORY:dashboard-secret-confirm:user")
                .doesNotContain("ghp_slashsecret12345")
                .doesNotContain("ghp_slashcommandsecret12345");
        ONode items = ONode.ofJson(confirms.body).get("data").get("items");
        String confirmId = "";
        for (int i = 0; i < items.size(); i++) {
            ONode item = items.get(i);
            if ("reload-mcp --token=***".equals(item.get("command_preview").getString())) {
                confirmId = item.get("confirm_id").getString();
            }
        }
        assertThat(confirmId).isNotBlank();
        request(
                "POST",
                "/api/diagnostics/slash-confirms/resolve",
                "{\"confirmId\":\""
                        + jsonEscape(confirmId)
                        + "\",\"action\":\"deny\"}",
                token);
    }

    @Test
    void shouldRejectUnsafeSlashConfirmSelectorWithoutLeakingDashboardInput() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        bean(SlashConfirmService.class)
                .register(
                        "MEMORY:dashboard-unsafe-confirm:user-token=ghp_sourcesecret12345",
                        "security-selector-check --token=ghp_unsafecommandsecret12345",
                        "确认刷新 Authorization: Bearer ghp_unsafepromptsecret12345");

        HttpResult before =
                request("GET", "/api/diagnostics/slash-confirms?limit=20", null, token);
        assertThat(before.status).isEqualTo(200);
        ONode confirm =
                findItemByStringField(
                        ONode.ofJson(before.body).get("data").get("items"),
                        "command_preview",
                        "security-selector-check --token=***");
        assertThat(confirm).isNotNull();
        String confirmId = confirm.get("confirm_id").getString();
        String unsafeConfirmId =
                confirmId.substring(0, 8) + " " + confirmId.substring(8) + "\u202E";

        HttpResult rejected =
                request(
                        "POST",
                        "/api/diagnostics/slash-confirms/resolve",
                        "{\"confirmId\":\""
                                + jsonEscape(unsafeConfirmId)
                                + "\",\"sourceKey\":\"MEMORY:dashboard-unsafe-confirm:user-token=ghp_sourcesecret12345\","
                                + "\"action\":\"deny\"}",
                        token);

        assertThat(rejected.status).isEqualTo(200);
        assertThat(rejected.body)
                .contains("\"success\":false")
                .contains("\"code\":\"confirm_not_found\"")
                .doesNotContain(unsafeConfirmId)
                .doesNotContain("\\u202E")
                .doesNotContain("MEMORY:dashboard-unsafe-confirm")
                .doesNotContain("ghp_sourcesecret12345")
                .doesNotContain("ghp_unsafecommandsecret12345")
                .doesNotContain("ghp_unsafepromptsecret12345");

        HttpResult after =
                request("GET", "/api/diagnostics/slash-confirms?limit=20", null, token);
        assertThat(after.status).isEqualTo(200);
        assertThat(after.body)
                .contains("\"command_preview\":\"security-selector-check --token=***\"")
                .contains("\"prompt_preview\":\"确认刷新 Authorization: Bearer ***\"")
                .doesNotContain("MEMORY:dashboard-unsafe-confirm")
                .doesNotContain("ghp_sourcesecret12345")
                .doesNotContain("ghp_unsafecommandsecret12345")
                .doesNotContain("ghp_unsafepromptsecret12345");

        request(
                "POST",
                "/api/diagnostics/slash-confirms/resolve",
                "{\"confirmId\":\""
                        + jsonEscape(confirmId)
                        + "\",\"action\":\"deny\"}",
                token);
    }

    @Test
    void shouldKeepDashboardSlashConfirmResolutionSourceIsolated() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        SlashConfirmService service = bean(SlashConfirmService.class);
        String sourceA = "MEMORY:dashboard-source-isolated-a:user-a";
        String sourceB = "MEMORY:dashboard-source-isolated-b:user-token=ghp_sourceisolation12345";
        SlashConfirmService.PendingConfirm confirmA =
                service.register(sourceA, "reload-mcp", "确认刷新 A");
        SlashConfirmService.PendingConfirm confirmB =
                service.register(sourceB, "reload-mcp", "确认刷新 B");

        HttpResult rejected =
                request(
                        "POST",
                        "/api/diagnostics/slash-confirms/resolve",
                        "{\"confirmId\":\""
                                + jsonEscape(confirmA.getConfirmId())
                                + "\",\"sourceKey\":\""
                                + jsonEscape(sourceB)
                                + "\",\"action\":\"deny\"}",
                        token);

        assertThat(rejected.status).isEqualTo(200);
        assertThat(rejected.body)
                .contains("\"success\":false")
                .contains("\"code\":\"confirm_not_found\"")
                .doesNotContain(sourceA)
                .doesNotContain("dashboard-source-isolated")
                .doesNotContain("ghp_sourceisolation12345");
        assertThat(service.getPending(sourceA).getConfirmId()).isEqualTo(confirmA.getConfirmId());
        assertThat(service.getPending(sourceB).getConfirmId()).isEqualTo(confirmB.getConfirmId());

        service.resolve(sourceA, confirmA.getConfirmId());
        service.resolve(sourceB, confirmB.getConfirmId());
    }

    @Test
    void shouldRedactDashboardCronErrors() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        String leakedToken = "sk-dashboardcron12345";
        File tokenDir = new File(new File(runtimeHome, "projects"), leakedToken);
        FileUtil.mkdir(tokenDir);

        HttpResult rejectedCreate =
                request(
                        "POST",
                        "/api/cron/jobs",
                        "{\"name\":\"redact-cron\",\"schedule\":\"every 1h\",\"prompt\":\"x\",\"workdir\":\""
                                + jsonEscape(tokenDir.getAbsolutePath() + "; rm -rf runtime")
                                + "\"}",
                        token);

        assertThat(rejectedCreate.status).isEqualTo(400);
        assertThat(rejectedCreate.body).contains("\"success\":false");
        assertThat(rejectedCreate.body).contains("***");
        assertThat(rejectedCreate.body).doesNotContain(leakedToken);
        assertThat(rejectedCreate.body).doesNotContain(runtimeHome.getAbsolutePath());
    }

    @Test
    void shouldReturnStructuredErrorForInvalidCronJson() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult dashboardCron =
                request(
                        "POST",
                        "/api/cron/jobs",
                        "{\"name\":\"bad-cron\",\"token\":\"ghp_invalidcron12345\"",
                        token);
        assertThat(dashboardCron.status).isEqualTo(400);
        assertThat(dashboardCron.body)
                .contains("\"success\":false")
                .contains("\"code\":\"CRON_BAD_REQUEST\"")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_invalidcron12345")
                .doesNotContain("bad-cron");
    }

    @Test
    void shouldRedactDashboardChatRunFailedEvents() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        String leakedToken = "sk-chatfailed12345";
        ONode start =
                ONode.ofJson(
                        request(
                                        "POST",
                                        "/api/chat/runs",
                                        "{\"input\":\"/resume "
                                                + leakedToken
                                                + "\",\"session_id\":\"dashboard-chat-failed-redaction\"}",
                                        token)
                                .body);
        assertThat(start.get("run_id").getString()).isNotBlank();

        String events =
                request(
                                "GET",
                                "/api/chat/runs/" + start.get("run_id").getString() + "/events",
                                null,
                                token)
                        .body;
        ONode failed = extractSseEvent(events, "run.failed");
        assertThat(failed.get("error").getString()).contains("***").doesNotContain(leakedToken);
        assertThat(events).doesNotContain(leakedToken);
    }

    @Test
    void shouldSupportDashboardChatRunsAndUploads() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult upload =
                requestMultipart("/api/chat/uploads", token, "hello.txt", "hello world");
        assertThat(upload.status).isEqualTo(200);
        assertThat(upload.body).contains("\"local_path\"");
        assertThat(upload.body).contains("media://");
        assertThat(upload.body).doesNotContain(runtimeHome.getAbsolutePath());
        assertThat(upload.body).contains("\"mime_type\"");

        String uploadedLocalPath =
                ONode.ofJson(upload.body).get("files").get(0).get("local_path").getString();
        HttpResult attachmentRun =
                request(
                        "POST",
                        "/api/chat/runs",
                        "{\"input\":\"看附件\",\"session_id\":\"dashboard-chat-upload-ref\","
                                + "\"attachments\":[{\"name\":\"hello.txt\",\"local_path\":\""
                                + jsonEscape(uploadedLocalPath)
                                + "\",\"kind\":\"file\",\"mime_type\":\"text/plain\"}]}",
                        token);
        assertThat(attachmentRun.status).isEqualTo(200);
        assertThat(attachmentRun.body).contains("\"run_id\"");

        ONode startStatus =
                ONode.ofJson(
                        request(
                                        "POST",
                                        "/api/chat/runs",
                                        "{\"input\":\"/status\",\"session_id\":\"dashboard-chat-status\"}",
                                        token)
                                .body);
        String statusRunId = startStatus.get("run_id").getString();
        assertThat(statusRunId).isNotBlank();
        assertThat(startStatus.get("session_id").getString()).isEqualTo("dashboard-chat-status");

        String statusEvents =
                request("GET", "/api/chat/runs/" + statusRunId + "/events", null, token).body;
        assertThat(statusEvents).contains("event: run.started");
        assertThat(statusEvents).contains("event: message.delta");
        assertThat(statusEvents).contains("event: run.completed");

        ONode branchStart =
                ONode.ofJson(
                        request(
                                        "POST",
                                        "/api/chat/runs",
                                        "{\"input\":\"/branch feature-a\",\"session_id\":\"dashboard-chat-status\"}",
                                        token)
                                .body);
        String branchEvents =
                request(
                                "GET",
                                "/api/chat/runs/"
                                        + branchStart.get("run_id").getString()
                                        + "/events",
                                null,
                                token)
                        .body;
        ONode branchCompleted = extractSseEvent(branchEvents, "run.completed");
        String branchSessionId = branchCompleted.get("session_id").getString();
        assertThat(branchSessionId).isNotBlank().isNotEqualTo("dashboard-chat-status");

        ONode resumeStart =
                ONode.ofJson(
                        request(
                                        "POST",
                                        "/api/chat/runs",
                                        "{\"input\":\"/resume dashboard-chat-status\",\"session_id\":\""
                                                + branchSessionId
                                                + "\"}",
                                        token)
                                .body);
        String resumeEvents =
                request(
                                "GET",
                                "/api/chat/runs/"
                                        + resumeStart.get("run_id").getString()
                                        + "/events",
                                null,
                                token)
                        .body;
        ONode resumeCompleted = extractSseEvent(resumeEvents, "run.completed");
        assertThat(resumeCompleted.get("session_id").getString())
                .isEqualTo("dashboard-chat-status");

        ONode newStart =
                ONode.ofJson(
                        request(
                                        "POST",
                                        "/api/chat/runs",
                                        "{\"input\":\"/new\",\"session_id\":\"dashboard-chat-status\"}",
                                        token)
                                .body);
        String newEvents =
                request(
                                "GET",
                                "/api/chat/runs/" + newStart.get("run_id").getString() + "/events",
                                null,
                                token)
                        .body;
        ONode newCompleted = extractSseEvent(newEvents, "run.completed");
        String newSessionId = newCompleted.get("session_id").getString();
        assertThat(newSessionId).isNotBlank().isNotEqualTo("dashboard-chat-status");

        ONode undoStart =
                ONode.ofJson(
                        request(
                                        "POST",
                                        "/api/chat/runs",
                                        "{\"input\":\"/undo\",\"session_id\":\""
                                                + newSessionId
                                                + "\"}",
                                        token)
                                .body);
        String undoEvents =
                request(
                                "GET",
                                "/api/chat/runs/" + undoStart.get("run_id").getString() + "/events",
                                null,
                                token)
                        .body;
        assertThat(undoEvents).contains("event: run.completed");
    }

    @Test
    void shouldHideCuratorHostPaths() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult run = request("POST", "/api/curator/run?force=true", "{}", token);
        assertThat(run.status).isEqualTo(200);
        assertThat(run.body).contains("curator://report");
        assertThat(run.body).contains("skill://sample-skill");
        assertThat(run.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult list = request("GET", "/api/curator?limit=5", null, token);
        assertThat(list.status).isEqualTo(200);
        assertThat(list.body).contains("curator://report");
        assertThat(list.body).doesNotContain(runtimeHome.getAbsolutePath());
        String reportId =
                ONode.ofJson(list.body)
                        .get("data")
                        .get("reports")
                        .get(0)
                        .get("report_id")
                        .getString();

        HttpResult detail = request("GET", "/api/curator/" + reportId, null, token);
        assertThat(detail.status).isEqualTo(200);
        assertThat(detail.body).contains("curator://report");
        assertThat(detail.body).contains("skill://sample-skill");
        assertThat(detail.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult invalidJson =
                request(
                        "POST",
                        "/api/curator/apply",
                        "{\"skill\":\"sample-skill token=ghp_curatorparse12345\"",
                        token);
        assertThat(invalidJson.status).isEqualTo(400);
        assertThat(invalidJson.body)
                .contains("CURATOR_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_curatorparse12345")
                .doesNotContain("sample-skill token");
    }

    @Test
    void shouldHideAgentHostPaths() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult defaultAgent = request("GET", "/api/agents/default", null, token);
        assertThat(defaultAgent.status).isEqualTo(200);
        assertThat(defaultAgent.body).contains("agent://default/workspace");
        assertThat(defaultAgent.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult created =
                request(
                        "POST",
                        "/api/agents",
                        "{\"name\":\"dashboard-path-agent\",\"role_prompt\":\"测试路径脱敏\"}",
                        token);
        assertThat(created.status).isEqualTo(200);
        assertThat(created.body).contains("agent://dashboard-path-agent/workspace");
        assertThat(created.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult agents = request("GET", "/api/agents", null, token);
        assertThat(agents.status).isEqualTo(200);
        assertThat(agents.body).contains("agent://dashboard-path-agent/workspace");
        assertThat(agents.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult detail = request("GET", "/api/agents/dashboard-path-agent", null, token);
        assertThat(detail.status).isEqualTo(200);
        assertThat(detail.body).contains("agent://dashboard-path-agent/skills");
        assertThat(detail.body).contains("agent://dashboard-path-agent/cache");
        assertThat(detail.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult missing =
                request(
                        "GET",
                        "/api/agents/missing-agent?session_id=token=agent-session-secret",
                        null,
                        token);
        assertThat(missing.status).isEqualTo(400);
        assertThat(missing.body)
                .contains("AGENT_BAD_REQUEST")
                .doesNotContain("agent-session-secret");

        HttpResult invalidJson =
                request(
                        "POST",
                        "/api/agents",
                        "{\"name\":\"agent-token=ghp_agentparse12345\"",
                        token);
        assertThat(invalidJson.status).isEqualTo(400);
        assertThat(invalidJson.body)
                .contains("AGENT_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_agentparse12345")
                .doesNotContain("agent-token");

        HttpResult invalid =
                request(
                        "POST",
                        "/api/agents",
                        "{\"name\":\"bad/token-agent-secret\",\"role_prompt\":\"测试错误脱敏\"}",
                        token);
        assertThat(invalid.status).isEqualTo(400);
        assertThat(invalid.body)
                .contains("AGENT_BAD_REQUEST")
                .doesNotContain("token-agent-secret");
    }

    @Test
    void shouldHideWorkspaceHostPaths() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        File diaryDir = new File(runtimeHome, "memory");
        FileUtil.mkdir(diaryDir);
        File diary = new File(diaryDir, "2099-01-01.md");
        FileUtil.writeUtf8String("# private diary\n", diary);

        HttpResult files = request("GET", "/api/workspace/files", null, token);
        assertThat(files.status).isEqualTo(200);
        assertThat(files.body).contains("workspace://files/");
        assertThat(files.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult agents = request("GET", "/api/workspace/files/agents", null, token);
        assertThat(agents.status).isEqualTo(200);
        assertThat(agents.body).contains("workspace://files/agents");
        assertThat(agents.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult diaries = request("GET", "/api/workspace/diaries", null, token);
        assertThat(diaries.status).isEqualTo(200);
        assertThat(diaries.body).contains("workspace://diaries/memory/2099-01-01.md");
        assertThat(diaries.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult diaryFile =
                request(
                        "GET",
                        "/api/workspace/diaries/read?path="
                                + URLEncoder.encode("memory/2099-01-01.md", "UTF-8"),
                        null,
                        token);
        assertThat(diaryFile.status).isEqualTo(200);
        assertThat(diaryFile.body).contains("workspace://diaries/memory/2099-01-01.md");
        assertThat(diaryFile.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult rejectedDiary =
                request(
                        "GET",
                        "/api/workspace/diaries/read?path="
                                + URLEncoder.encode("memory/missing-secret-token.md", "UTF-8"),
                        null,
                        token);
        assertThat(rejectedDiary.status).isEqualTo(400);
        assertThat(rejectedDiary.body)
                .contains("WORKSPACE_BAD_REQUEST")
                .doesNotContain(runtimeHome.getAbsolutePath())
                .doesNotContain("missing-secret-token.md");

        HttpResult invalidJson =
                request(
                        "PUT",
                        "/api/workspace/files/agents",
                        "{\"content\":\"token=ghp_workspaceparse12345\"",
                        token);
        assertThat(invalidJson.status).isEqualTo(400);
        assertThat(invalidJson.body)
                .contains("WORKSPACE_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_workspaceparse12345")
                .doesNotContain("token=");
    }

    @Test
    void shouldWrapSessionMutationErrors() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult missingSession =
                request(
                        "PUT",
                        "/api/sessions/missing-token=ghp_sessiondashboard12345",
                        "{\"title\":\"bad\"}",
                        token);
        assertThat(missingSession.status).isEqualTo(400);
        assertThat(missingSession.body)
                .contains("SESSION_BAD_REQUEST")
                .contains("token=***")
                .doesNotContain("ghp_sessiondashboard12345");

        HttpResult invalidJson =
                request(
                        "PUT",
                        "/api/sessions/session-token=ghp_sessionparseid12345",
                        "{\"title\":\"token=ghp_sessionparse12345\"",
                        token);
        assertThat(invalidJson.status).isEqualTo(400);
        assertThat(invalidJson.body)
                .contains("SESSION_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_sessionparseid12345")
                .doesNotContain("ghp_sessionparse12345")
                .doesNotContain("token=");

        HttpResult missingCheckpoint =
                request(
                        "POST",
                        "/api/checkpoints/missing-token=ghp_checkpointdashboard12345/rollback",
                        null,
                        token);
        assertThat(missingCheckpoint.status).isEqualTo(400);
        assertThat(missingCheckpoint.body)
                .contains("SESSION_BAD_REQUEST")
                .doesNotContain("ghp_checkpointdashboard12345");
    }

    @Test
    void shouldWrapRunControlErrors() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult invalidJson =
                request(
                        "POST",
                        "/api/runs/run-invalid-json/control",
                        "{\"command\":\"interrupt\",\"reason\":\"ghp_invalidruncontrol12345\"",
                        token);
        assertThat(invalidJson.status).isEqualTo(400);
        assertThat(invalidJson.body)
                .contains("RUN_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_invalidruncontrol12345")
                .doesNotContain("interrupt");

        HttpResult missingRun =
                request(
                        "POST",
                        "/api/runs/missing-token=ghp_rundashboard12345/control",
                        "{\"command\":\"interrupt\",\"reason\":\"token=ghp_runreason12345\"}",
                        token);
        assertThat(missingRun.status).isEqualTo(400);
        assertThat(missingRun.body)
                .contains("RUN_BAD_REQUEST")
                .contains("token=***")
                .doesNotContain("ghp_rundashboard12345")
                .doesNotContain("ghp_runreason12345");
    }

    @Test
    void shouldRedactSubagentControlEcho() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult invalidJson =
                request(
                        "POST",
                        "/api/runs/subagents/sub-invalid-json/control",
                        "{\"command\":\"pause token=ghp_invalidsubcommand12345\"",
                        token);
        assertThat(invalidJson.status).isEqualTo(400);
        assertThat(invalidJson.body)
                .contains("RUN_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_invalidsubcommand12345")
                .doesNotContain("pause token");

        HttpResult control =
                request(
                        "POST",
                        "/api/runs/subagents/sub-token=ghp_subagent12345/control",
                        "{\"command\":\"pause token=ghp_subcommand12345\"}",
                        token);
        assertThat(control.status).isEqualTo(200);
        assertThat(control.body)
                .contains("token=***")
                .doesNotContain("ghp_subagent12345")
                .doesNotContain("ghp_subcommand12345");
    }

    @Test
    void shouldWrapDashboardChatErrors() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult missingRun =
                request(
                        "POST",
                        "/api/chat/runs/missing-token=ghp_chatrun12345/cancel",
                        null,
                        token);
        assertThat(missingRun.status).isEqualTo(404);
        assertThat(missingRun.body)
                .contains("CHAT_NOT_FOUND")
                .contains("token=***")
                .doesNotContain("ghp_chatrun12345");

        HttpResult invalidJson =
                request(
                        "POST",
                        "/api/chat/runs",
                        "{\"input\":\"/resume ghp_chatparse12345\"",
                        token);
        assertThat(invalidJson.status).isEqualTo(400);
        assertThat(invalidJson.body)
                .contains("CHAT_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_chatparse12345")
                .doesNotContain("/resume");

        HttpResult invalidRun = request("POST", "/api/chat/runs", "{}", token);
        assertThat(invalidRun.status).isEqualTo(400);
        assertThat(invalidRun.body).contains("CHAT_BAD_REQUEST");
    }

    @Test
    void shouldHideMediaCacheHostPaths() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        File mediaDir = new File(new File(runtimeHome, "cache"), "media/MEMORY");
        FileUtil.mkdir(mediaDir);
        File cached = new File(mediaDir, "dashboard-secret-token.txt");
        FileUtil.writeUtf8String("cached media", cached);

        HttpResult index =
                request(
                        "POST",
                        "/api/media/index",
                        "{\"mediaId\":\"dashboard-media-secret\",\"platform\":\"MEMORY\","
                                + "\"localPath\":\""
                                + jsonEscape(cached.getAbsolutePath())
                                + "\",\"originalName\":\"dashboard-secret-token.txt\","
                                + "\"kind\":\"file\",\"mimeType\":\"text/plain\","
                                + "\"remoteId\":\"token=ghp_mediasecret123\"}",
                        token);
        assertThat(index.status).isEqualTo(200);

        HttpResult detail =
                request("GET", "/api/media/dashboard-media-secret", null, token);
        assertThat(detail.status).isEqualTo(200);
        assertThat(detail.body).contains("media://MEMORY/dashboard-secret-token.txt");
        assertThat(detail.body).doesNotContain(runtimeHome.getAbsolutePath());
        assertThat(detail.body).doesNotContain("ghp_mediasecret123");

        HttpResult download =
                request(
                        "POST",
                        "/api/media/dashboard-media-secret/download",
                        "{}",
                        token);
        assertThat(download.status).isEqualTo(200);
        assertThat(download.body).contains("media://MEMORY/dashboard-secret-token.txt");
        assertThat(download.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult reference =
                request(
                        "POST",
                        "/api/media/dashboard-media-secret/reference",
                        "{}",
                        token);
        assertThat(reference.status).isEqualTo(200);
        assertThat(reference.body).contains("media://MEMORY/dashboard-secret-token.txt");
        assertThat(reference.body).doesNotContain(runtimeHome.getAbsolutePath());
    }

    @Test
    void shouldWrapMediaErrors() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        File secret = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String("apiKey: ghp_mediapath12345\n", secret);

        HttpResult invalidPath =
                request(
                        "POST",
                        "/api/media/index",
                        "{\"mediaId\":\"media-token=ghp_mediaindex12345\","
                                + "\"localPath\":\""
                                + jsonEscape(secret.getAbsolutePath())
                                + "\",\"platform\":\"MEMORY\"}",
                        token);
        assertThat(invalidPath.status).isEqualTo(400);
        assertThat(invalidPath.body)
                .contains("MEDIA_BAD_REQUEST")
                .doesNotContain(secret.getAbsolutePath())
                .doesNotContain(runtimeHome.getAbsolutePath())
                .doesNotContain("ghp_mediaindex12345");

        HttpResult invalidJson =
                request(
                        "POST",
                        "/api/media/index",
                        "{\"mediaId\":\"media-token=ghp_mediaparse12345\"",
                        token);
        assertThat(invalidJson.status).isEqualTo(400);
        assertThat(invalidJson.body)
                .contains("MEDIA_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_mediaparse12345")
                .doesNotContain("media-token");

        HttpResult missing =
                request(
                        "POST",
                        "/api/media/missing-token=ghp_mediamissing12345/download",
                        "{}",
                        token);
        assertThat(missing.status).isEqualTo(400);
        assertThat(missing.body)
                .contains("MEDIA_BAD_REQUEST")
                .contains("token=***")
                .doesNotContain("ghp_mediamissing12345");
    }

    @Test
    void shouldRejectDashboardChatAttachmentPathsOutsideMediaCache() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);
        File secret = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String("providers:\n  default:\n    apiKey: secret\n", secret);

        HttpResult rejected =
                request(
                        "POST",
                        "/api/chat/runs",
                        "{\"input\":\"看附件\",\"session_id\":\"dashboard-chat-attachment-guard\","
                                + "\"attachments\":[{\"name\":\"config.yml\","
                                + "\"local_path\":\""
                                + jsonEscape(secret.getAbsolutePath())
                                + "\",\"kind\":\"file\",\"mime_type\":\"text/yaml\"}]}",
                        token);

        assertThat(rejected.status).isEqualTo(400);
        assertThat(rejected.body).contains("outside media cache");
        assertThat(rejected.body).doesNotContain(secret.getAbsolutePath());
        assertThat(rejected.body).doesNotContain(runtimeHome.getAbsolutePath());
        assertThat(rejected.body).doesNotContain("run_id");
        assertThat(request("GET", "/api/sessions?limit=20&offset=0", null, token).body)
                .doesNotContain("dashboard-chat-attachment-guard");
    }

    @Test
    void shouldNotExposeTodoApis() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        assertThat(request("GET", "/api/todos", null, token).status).isEqualTo(404);
        assertThat(request("POST", "/api/todos", "{\"title\":\"removed\"}", token).status)
                .isEqualTo(404);
    }

    @Test
    void shouldRejectPlaceholderSecrets() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult unsafeProviderUrl =
                request(
                        "POST",
                        "/api/providers",
                        "{\"providerKey\":\"unsafe-url-provider\",\"name\":\"危险 URL Provider\",\"baseUrl\":\"http://169.254.169.254/latest/meta-data/?token=provider-url-secret\",\"apiKey\":\"test-key\",\"defaultModel\":\"gpt-5-mini\",\"dialect\":\"openai\"}",
                        token);
        assertThat(unsafeProviderUrl.status).isEqualTo(400);
        assertThat(unsafeProviderUrl.body)
                .contains("provider.baseUrl")
                .contains("token=***")
                .doesNotContain("provider-url-secret");
        assertThat(request("GET", "/api/providers", null, token).body)
                .doesNotContain("unsafe-url-provider");

        HttpResult createProvider =
                request(
                        "POST",
                        "/api/providers",
                        "{\"providerKey\":\"placeholder-provider\",\"name\":\"占位 Provider\",\"baseUrl\":\"https://api.example.com\",\"apiKey\":\"  Your-API-Key  \",\"defaultModel\":\"gpt-5-mini\",\"dialect\":\"openai\"}",
                        token);
        assertThat(createProvider.status).isEqualTo(400);
        assertThat(request("GET", "/api/providers", null, token).body)
                .doesNotContain("placeholder-provider");

        HttpResult saveRuntimeConfig =
                request(
                        "PUT",
                        "/api/runtime-config",
                        "{\"key\":\"providers.default.apiKey\",\"value\":\"NONE\"}",
                        token);
        assertThat(saveRuntimeConfig.status).isEqualTo(400);
        assertThat(saveRuntimeConfig.body)
                .contains("RUNTIME_CONFIG_BAD_REQUEST")
                .contains("占位符密钥")
                .doesNotContain("NONE");

        HttpResult saveChannelToken =
                request(
                        "PUT",
                        "/api/runtime-config",
                        "{\"key\":\"solonclaw.channels.weixin.token\",\"value\":\"dummy\"}",
                        token);
        assertThat(saveChannelToken.status).isEqualTo(400);
        assertThat(saveChannelToken.body)
                .contains("RUNTIME_CONFIG_BAD_REQUEST")
                .contains("占位符密钥")
                .doesNotContain("dummy");
    }

    @Test
    void shouldWrapRuntimeConfigStateErrors() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult unsupportedSet =
                request(
                        "PUT",
                        "/api/runtime-config",
                        "{\"key\":\"token=ghp_runtimeconfig12345\",\"value\":\"x\"}",
                        token);
        assertThat(unsupportedSet.status).isEqualTo(400);
        assertThat(unsupportedSet.body)
                .contains("RUNTIME_CONFIG_BAD_REQUEST")
                .contains("token=***")
                .doesNotContain("ghp_runtimeconfig12345");

        HttpResult unsupportedReveal =
                request(
                        "POST",
                        "/api/runtime-config/reveal",
                        "{\"key\":\"token=ghp_runtimereveal12345\"}",
                        token);
        assertThat(unsupportedReveal.status).isEqualTo(400);
        assertThat(unsupportedReveal.body)
                .contains("RUNTIME_CONFIG_BAD_REQUEST")
                .contains("token=***")
                .doesNotContain("ghp_runtimereveal12345");
    }

    @Test
    void shouldReturnStructuredErrorForInvalidRuntimeConfigJson() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult invalidSet =
                request(
                        "PUT",
                        "/api/runtime-config",
                        "{\"key\":\"providers.default.apiKey\",\"value\":\"ghp_invalidruntime12345\"",
                        token);
        HttpResult invalidReveal =
                request(
                        "POST",
                        "/api/runtime-config/reveal",
                        "{\"key\":\"providers.default.apiKey\",\"token\":\"ghp_invalidreveal12345\"",
                        token);
        HttpResult deleteByBody =
                request(
                        "DELETE",
                        "/api/runtime-config",
                        "{\"key\":\"providers.default.apiKey\"}",
                        token);

        assertThat(invalidSet.status).isEqualTo(400);
        assertThat(invalidSet.body)
                .contains("RUNTIME_CONFIG_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_invalidruntime12345")
                .doesNotContain("providers.default.apiKey");
        assertThat(invalidReveal.status).isEqualTo(400);
        assertThat(invalidReveal.body)
                .contains("RUNTIME_CONFIG_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_invalidreveal12345")
                .doesNotContain("providers.default.apiKey");
        assertThat(deleteByBody.status).isEqualTo(200);
    }

    @Test
    void shouldWrapDashboardConfigSaveErrors() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult invalidSaveJson =
                request(
                        "PUT",
                        "/api/config",
                        "{\"config\":{\"terminal\":{\"credentialFiles\":[\"ghp_invalidconfig12345\"]}}",
                        token);
        assertThat(invalidSaveJson.status).isEqualTo(400);
        assertThat(invalidSaveJson.body)
                .contains("CONFIG_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_invalidconfig12345")
                .doesNotContain("credentialFiles");

        HttpResult unsupportedSave =
                request(
                        "PUT",
                        "/api/config",
                        "{\"config\":{\"solonclaw\":{\"terminal\":{\"credentialFiles\":[\"C:/secret/ghp_configsave12345.txt\"]}}}}",
                        token);
        assertThat(unsupportedSave.status).isEqualTo(400);
        assertThat(unsupportedSave.body)
                .contains("CONFIG_BAD_REQUEST")
                .contains("runtime-relative paths")
                .doesNotContain("ghp_configsave12345");

        HttpResult invalidRawJson =
                request(
                        "PUT",
                        "/api/config/raw",
                        "{\"yaml_text\":\"apiKey: ghp_invalidrawconfig12345\"",
                        token);
        assertThat(invalidRawJson.status).isEqualTo(400);
        assertThat(invalidRawJson.body)
                .contains("CONFIG_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_invalidrawconfig12345")
                .doesNotContain("apiKey");

        HttpResult unsupportedRaw =
                request(
                        "PUT",
                        "/api/config/raw",
                        "{\"yaml_text\":\"solonclaw:\\n  terminal:\\n    credentialFiles:\\n      - C:/secret/ghp_configraw12345.txt\"}",
                        token);
        assertThat(unsupportedRaw.status).isEqualTo(400);
        assertThat(unsupportedRaw.body)
                .contains("CONFIG_BAD_REQUEST")
                .contains("runtime-relative paths")
                .doesNotContain("ghp_configraw12345");
    }

    @Test
    void shouldWrapProviderMutationErrors() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult invalidCreate =
                request(
                        "POST",
                        "/api/providers",
                        "{\"providerKey\":\"bad-provider\",\"apiKey\":\"ghp_invalidprovider12345\"",
                        token);
        assertThat(invalidCreate.status).isEqualTo(400);
        assertThat(invalidCreate.body)
                .contains("PROVIDER_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_invalidprovider12345")
                .doesNotContain("bad-provider");

        HttpResult invalidDefault =
                request(
                        "PUT",
                        "/api/model/default",
                        "{\"providerKey\":\"missing-provider?token=default-token-secret\",\"model\":\"gpt-5-mini\"}",
                        token);
        assertThat(invalidDefault.status).isEqualTo(400);
        assertThat(invalidDefault.body)
                .contains("PROVIDER_BAD_REQUEST")
                .contains("token=***")
                .doesNotContain("default-token-secret");

        HttpResult invalidFallback =
                request(
                        "PUT",
                        "/api/model/fallbacks",
                        "{\"fallbackProviders\":[{\"provider\":\"missing-fallback?token=fallback-token-secret\",\"model\":\"gpt-5-mini\"}]}",
                        token);
        assertThat(invalidFallback.status).isEqualTo(400);
        assertThat(invalidFallback.body)
                .contains("PROVIDER_BAD_REQUEST")
                .contains("token=***")
                .doesNotContain("fallback-token-secret");

        HttpResult invalidFallbackJson =
                request(
                        "PUT",
                        "/api/model/fallbacks",
                        "{\"fallbackProviders\":[{\"provider\":\"bad-fallback\",\"token\":\"ghp_invalidfallback12345\"}",
                        token);
        assertThat(invalidFallbackJson.status).isEqualTo(400);
        assertThat(invalidFallbackJson.body)
                .contains("PROVIDER_BAD_REQUEST")
                .contains("请求体 JSON 解析失败")
                .doesNotContain("ghp_invalidfallback12345")
                .doesNotContain("bad-fallback");

        HttpResult deleteDefault = request("DELETE", "/api/providers/default", null, token);
        assertThat(deleteDefault.status).isEqualTo(400);
        assertThat(deleteDefault.body)
                .contains("PROVIDER_BAD_REQUEST")
                .contains("当前默认 provider 不能删除");
    }

    @Test
    void shouldRejectBadGatewayInjectionWithoutBurningNonce() throws Exception {
        String body =
                "{\"platform\":\"MEMORY\",\"chatId\":\"signed-chat\",\"userId\":\"signed-user\",\"text\":\"/status\"}";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonce = "nonce-" + System.nanoTime();

        Map<String, String> badHeaders = signedHeaders(timestamp, nonce, body, "wrong-secret");
        HttpResult bad = request("POST", "/api/gateway/message", body, null, badHeaders);
        assertThat(bad.status).isEqualTo(401);

        Map<String, String> goodHeaders =
                signedHeaders(timestamp, nonce, body, "test-injection-secret");
        HttpResult good = request("POST", "/api/gateway/message", body, null, goodHeaders);
        assertThat(good.status).isEqualTo(200);

        HttpResult replay = request("POST", "/api/gateway/message", body, null, goodHeaders);
        assertThat(replay.status).isEqualTo(409);
    }

    private static void createSampleSkill() {
        File skillFile = FileUtil.file(runtimeHome, "skills", "sample-skill", "SKILL.md");
        String content =
                "---\nname: sample-skill\ndescription: Sample skill for dashboard tests\n---\n\n# Sample\n";
        FileUtil.writeUtf8String(content, skillFile);
        File references = FileUtil.file(runtimeHome, "skills", "sample-skill", "references");
        FileUtil.mkdir(references);
        FileUtil.writeUtf8String("supporting skill notes", FileUtil.file(references, "info.md"));
    }

    private static void seedDashboardGoalSession() throws Exception {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        config.getRuntime()
                .setStateDb(new File(new File(runtimeHome, "data"), "state.db").getAbsolutePath());
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteSessionRepository repository = new SqliteSessionRepository(database);
            SessionRecord record = repository.findById("dashboard-chat");
            if (record == null) {
                record = new SessionRecord();
                record.setSessionId("dashboard-chat");
                record.setSourceKey("MEMORY:dashboard-chat:dashboard-user");
                record.setBranchName("main");
                record.setTitle("Dashboard goal session");
                record.setNdjson("");
                record.setCreatedAt(System.currentTimeMillis());
                record.setUpdatedAt(record.getCreatedAt());
                repository.save(record);
            }
            new GoalService(repository).set(record, "完成 dashboard 会话目标展示", 3);
        } finally {
            database.shutdown();
        }
    }

    private static void seedPendingApproval(
            String sessionId, String sourceKey, String title, String command) throws Exception {
        seedPendingApproval(sessionId, sourceKey, title, command, "需要确认危险命令");
    }

    private static void seedPendingApproval(
            String sessionId, String sourceKey, String title, String command, String description)
            throws Exception {
        SessionRepository repository = bean(SessionRepository.class);
        DangerousCommandApprovalService approvalService =
                bean(DangerousCommandApprovalService.class);
        SessionRecord record = repository.findById(sessionId);
        long now = System.currentTimeMillis();
        if (record == null) {
            record = new SessionRecord();
            record.setSessionId(sessionId);
            record.setSourceKey(sourceKey);
            record.setBranchName("main");
            record.setTitle(title);
            record.setNdjson("");
            record.setCreatedAt(now);
        }
        record.setUpdatedAt(now);
        repository.save(record);
        repository.bindSource(sourceKey, sessionId);
        SqliteAgentSession agentSession = new SqliteAgentSession(record, repository);
        approvalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "rm_recursive_root",
                description,
                command);
    }

    private static <T> T bean(Class<T> type) {
        AppContext context = Solon.context();
        return context.getBean(type);
    }

    private static String extractToken(String html) {
        Matcher matcher = Pattern.compile("__APP_SESSION_TOKEN__=\\\"([^\\\"]+)\\\"").matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ONode findItemByStringField(ONode items, String field, String value) {
        for (int i = 0; i < items.size(); i++) {
            ONode item = items.get(i);
            if (value.equals(item.get(field).getString())) {
                return item;
            }
        }
        return null;
    }

    private static List<String> stringsAt(String body, String field) {
        List<String> result = new java.util.ArrayList<String>();
        ONode array = ONode.ofJson(body).get("data").get(field);
        for (int i = 0; i < array.size(); i++) {
            result.add(array.get(i).getString());
        }
        return result;
    }

    private static HttpResult request(String method, String path, String body, String token)
            throws Exception {
        return request(method, path, body, token, null);
    }

    private static HttpResult request(
            String method, String path, String body, String token, Map<String, String> headers)
            throws Exception {
        int attempts = isRetryableRequest(method, path) ? 3 : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return requestOnce(method, path, body, token, headers);
            } catch (SocketException e) {
                if (attempt >= attempts) {
                    throw e;
                }
                Thread.sleep(100L * attempt);
            }
        }
        throw new IllegalStateException("HTTP request failed without response");
    }

    private static boolean isRetryableRequest(String method, String path) {
        if ("PATCH".equalsIgnoreCase(method)) {
            return false;
        }
        String safePath = path == null ? "" : path;
        if (safePath.contains("/oauth/callback")) {
            return false;
        }
        return true;
    }

    private static HttpResult requestOnce(
            String method, String path, String body, String token, Map<String, String> headers)
            throws Exception {
        if ("PATCH".equalsIgnoreCase(method)) {
            return requestPatch(path, body, token, headers);
        }
        HttpURLConnection connection =
                (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Connection", "close");
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            OutputStream outputStream = connection.getOutputStream();
            try {
                outputStream.write(data);
            } finally {
                outputStream.close();
            }
        }

        int status = connection.getResponseCode();
        java.io.InputStream stream =
                status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return new HttpResult(status, "");
        }
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder buffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                buffer.append(line).append('\n');
            }
            return new HttpResult(status, buffer.toString());
        } finally {
            reader.close();
            connection.disconnect();
        }
    }

    private static HttpResult requestPatch(
            String path, String body, String token, Map<String, String> headers)
            throws Exception {
        java.net.Socket socket = new java.net.Socket("127.0.0.1", port);
        try {
            byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder request = new StringBuilder();
            request.append("PATCH ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: 127.0.0.1:").append(port).append("\r\n");
            request.append("Connection: close\r\n");
            request.append("Content-Type: application/json; charset=UTF-8\r\n");
            request.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            if (token != null) {
                request.append("Authorization: Bearer ").append(token).append("\r\n");
            }
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    request.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
                }
            }
            request.append("\r\n");
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(request.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes.length > 0) {
                outputStream.write(bodyBytes);
            }
            outputStream.flush();

            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.InputStream inputStream = socket.getInputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = inputStream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            String response = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            int status = parseHttpStatus(response);
            int bodyStart = response.indexOf("\r\n\r\n");
            return new HttpResult(status, bodyStart < 0 ? "" : response.substring(bodyStart + 4));
        } finally {
            socket.close();
        }
    }

    private static int parseHttpStatus(String response) {
        if (response == null || response.length() < 12) {
            return -1;
        }
        String firstLine = response.split("\\r?\\n", 2)[0];
        String[] parts = firstLine.split(" ");
        return parts.length >= 2 ? Integer.parseInt(parts[1]) : -1;
    }

    private static Map<String, String> signedHeaders(
            String timestamp, String nonce, String body, String secret) throws Exception {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-SolonClaw-Timestamp", timestamp);
        headers.put("X-SolonClaw-Nonce", nonce);
        headers.put(
                "X-SolonClaw-Signature",
                "sha256=" + hmacSha256(secret, timestamp + "." + nonce + "." + body));
        return headers;
    }

    private static String hmacSha256(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            String hex = Integer.toHexString(value & 0xff);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }

    private static HttpResult requestMultipart(
            String path, String token, String fileName, String content) throws Exception {
        String boundary = "----CodexBoundary" + System.currentTimeMillis();
        HttpURLConnection connection =
                (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }

        byte[] fileBytes = content.getBytes(StandardCharsets.UTF_8);
        OutputStream outputStream = connection.getOutputStream();
        try {
            outputStream.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write(
                    ("Content-Disposition: form-data; name=\"file\"; filename=\""
                                    + fileName
                                    + "\"\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            outputStream.write("Content-Type: text/plain\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            outputStream.write(fileBytes);
            outputStream.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } finally {
            outputStream.close();
        }

        int status = connection.getResponseCode();
        java.io.InputStream stream =
                status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return new HttpResult(status, "");
        }
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder buffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                buffer.append(line).append('\n');
            }
            return new HttpResult(status, buffer.toString());
        } finally {
            reader.close();
            connection.disconnect();
        }
    }

    private static ONode extractSseEvent(String sseBody, String eventName) {
        String[] lines = sseBody.split("\\r?\\n");
        String currentEvent = null;
        for (String line : lines) {
            if (line.startsWith("event:")) {
                currentEvent = line.substring(6).trim();
                continue;
            }
            if (line.startsWith("data:") && eventName.equals(currentEvent)) {
                return ONode.ofJson(line.substring(5).trim());
            }
        }
        throw new IllegalStateException("Missing SSE event: " + eventName + " in body: " + sseBody);
    }

    private static void waitForHealth() throws Exception {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResult response = request("GET", "/health", null, null);
                if (response.status == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // retry
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("health endpoint did not become ready");
    }

    private static int findFreePort() throws Exception {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private static class HttpResult {
        private final int status;
        private final String body;

        private HttpResult(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static class TokenEndpointStub {
        private final HttpServer server;
        private final int port;
        private volatile Map<String, String> lastForm = new LinkedHashMap<String, String>();
        private volatile Map<String, String> redirectForm = new LinkedHashMap<String, String>();
        private final List<Map<String, String>> forms = new CopyOnWriteArrayList<Map<String, String>>();
        private volatile boolean failNextTokenResponse;
        private volatile int refreshCount;
        private volatile String issuedRefreshToken;

        private TokenEndpointStub(HttpServer server, int port) {
            this.server = server;
            this.port = port;
        }

        private static TokenEndpointStub start() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TokenEndpointStub stub = new TokenEndpointStub(server, server.getAddress().getPort());
            server.createContext("/token", stub::handle);
            server.createContext("/redirect-token", stub::handleRedirect);
            server.start();
            return stub;
        }

        private String url() {
            return "http://127.0.0.1:" + port + "/token";
        }

        private String redirectUrl() {
            return "http://127.0.0.1:" + port + "/redirect-token";
        }

        private void stop() {
            server.stop(0);
        }

        private void failNextTokenResponse() {
            failNextTokenResponse = true;
        }

        private void handleRedirect(HttpExchange exchange) throws java.io.IOException {
            byte[] body = readBytes(exchange);
            redirectForm = parseForm(new String(body, StandardCharsets.UTF_8));
            exchange.getResponseHeaders()
                    .set(
                            "Location",
                            "http://169.254.169.254/latest/meta-data/?token=secret-redirect");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        }

        private void handle(HttpExchange exchange) throws java.io.IOException {
            byte[] body = readBytes(exchange);
            lastForm = parseForm(new String(body, StandardCharsets.UTF_8));
            forms.add(new LinkedHashMap<String, String>(lastForm));
            if (failNextTokenResponse) {
                failNextTokenResponse = false;
                String responseJson =
                        "{\"error\":\"client_secret=token-error-client access_token=ghp_tokenerror12345&callback=http://localhost/cb?api%255Fkey=token-error-encoded&token=token-error-secret https://example.test/callback#refresh_token=token-error-fragment\"}";
                byte[] response = responseJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(400, response.length);
                OutputStream outputStream = exchange.getResponseBody();
                try {
                    outputStream.write(response);
                } finally {
                    outputStream.close();
                }
                return;
            }
            String grantType = lastForm.get("grant_type");
            String responseJson;
            if ("refresh_token".equals(grantType)) {
                refreshCount++;
                issuedRefreshToken = "refresh-secret-" + (refreshCount + 1);
                responseJson =
                        "{\"access_token\":\"token-secret-"
                                + (refreshCount + 1)
                                + "\",\"refresh_token\":\""
                                + issuedRefreshToken
                                + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"repo read:user\"}";
            } else {
                issuedRefreshToken = "refresh-secret-1";
                responseJson =
                        "{\"access_token\":\"token-secret-1\",\"refresh_token\":\"refresh-secret-1\","
                                + "\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"repo read:user\"}";
            }
            byte[] response = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream outputStream = exchange.getResponseBody();
            try {
                outputStream.write(response);
            } finally {
                outputStream.close();
            }
        }

        private String lastIssuedRefreshToken() {
            return issuedRefreshToken;
        }

        private Map<String, String> firstFormByRefreshToken(String refreshToken) {
            for (Map<String, String> form : forms) {
                if ("refresh_token".equals(form.get("grant_type"))
                        && refreshToken.equals(form.get("refresh_token"))) {
                    return form;
                }
            }
            return null;
        }

        private static byte[] readBytes(HttpExchange exchange) throws java.io.IOException {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.InputStream inputStream = exchange.getRequestBody();
            try {
                byte[] data = new byte[1024];
                int read;
                while ((read = inputStream.read(data)) >= 0) {
                    buffer.write(data, 0, read);
                }
                return buffer.toByteArray();
            } finally {
                inputStream.close();
            }
        }

        private static Map<String, String> parseForm(String form) {
            Map<String, String> result = new LinkedHashMap<String, String>();
            if (form == null || form.length() == 0) {
                return result;
            }
            String[] pairs = form.split("&");
            for (String pair : pairs) {
                int index = pair.indexOf('=');
                if (index <= 0) {
                    continue;
                }
                try {
                    result.put(
                            URLDecoder.decode(pair.substring(0, index), "UTF-8"),
                            URLDecoder.decode(pair.substring(index + 1), "UTF-8"));
                } catch (Exception ignored) {
                    // Keep the test stub focused on OAuth form capture.
                }
            }
            return result;
        }
    }
}
