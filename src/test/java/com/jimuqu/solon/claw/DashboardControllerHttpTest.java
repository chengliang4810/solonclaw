package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
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
import java.util.Map;
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
        assertThat(authorizedRuntimeConfig.body).contains("providers.default.apiKey");

        HttpResult unauthorizedDoctor = request("GET", "/api/gateway/doctor", null, null);
        assertThat(unauthorizedDoctor.status).isEqualTo(401);

        HttpResult authorizedDoctor = request("GET", "/api/gateway/doctor", null, token);
        assertThat(authorizedDoctor.status).isEqualTo(200);
        assertThat(authorizedDoctor.body).contains("\"platforms\"");

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

        HttpResult saveRuntimeConfig =
                request(
                        "PUT",
                        "/api/runtime-config",
                        "{\"key\":\"providers.default.apiKey\",\"value\":\"secret12345678\"}",
                        token);
        assertThat(saveRuntimeConfig.status).isEqualTo(200);
        assertThat(overrideFile).exists();
        assertThat(FileUtil.readUtf8String(overrideFile)).contains("apiKey: secret12345678");

        HttpResult revealRuntimeConfig =
                request(
                        "POST",
                        "/api/runtime-config/reveal",
                        "{\"key\":\"providers.default.apiKey\"}",
                        token);
        assertThat(revealRuntimeConfig.status).isEqualTo(200);
        assertThat(revealRuntimeConfig.body).contains("secret12345678");

        request(
                "POST",
                "/api/gateway/message",
                "{\"platform\":\"MEMORY\",\"chatId\":\"dashboard-chat\",\"userId\":\"dashboard-user\",\"chatType\":\"dm\",\"chatName\":\"dashboard-chat\",\"userName\":\"dashboard-user\",\"text\":\"hello\"}",
                null);

        HttpResult sessions = request("GET", "/api/sessions?limit=20&offset=0", null, token);
        assertThat(sessions.status).isEqualTo(200);
        assertThat(sessions.body).contains("\"total\"");

        HttpResult runs = request("GET", "/api/sessions/dashboard-chat/runs", null, token);
        assertThat(runs.status).isEqualTo(200);
        assertThat(runs.body).contains("\"runs\"");

        HttpResult tree = request("GET", "/api/sessions/dashboard-chat/tree", null, token);
        assertThat(tree.status).isEqualTo(200);
        assertThat(tree.body).contains("\"nodes\"");

        HttpResult checkpoints =
                request("GET", "/api/sessions/dashboard-chat/checkpoints", null, token);
        assertThat(checkpoints.status).isEqualTo(200);
        assertThat(checkpoints.body).contains("\"checkpoints\"");

        HttpResult diagnostics = request("GET", "/api/diagnostics", null, token);
        assertThat(diagnostics.status).isEqualTo(200);
        assertThat(diagnostics.body).contains("\"providers\"").contains("\"channels\"");

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

        HttpResult createCron =
                request(
                        "POST",
                        "/api/cron/jobs",
                        "{\"prompt\":\"daily summary\",\"schedule\":\"0 9 * * *\",\"name\":\"Daily summary\",\"deliver\":\"local\",\"model\":\"gpt-5-mini\",\"base_url\":\"https://api.cron.example/v1/\"}",
                        token);
        assertThat(createCron.status).isEqualTo(200);
        assertThat(createCron.body).contains("\"model\":\"gpt-5-mini\"");
        assertThat(createCron.body).contains("\"provider\":\"openai-direct\"");
        assertThat(createCron.body).contains("\"base_url\":\"https://api.cron.example/v1\"");
        HttpResult cronJobs = request("GET", "/api/cron/jobs", null, token);
        assertThat(cronJobs.body).contains("Daily summary");
        assertThat(cronJobs.body).contains("\"model\":\"gpt-5-mini\"");

        HttpResult createMcp =
                request(
                        "POST",
                        "/api/hermes/mcp",
                        "{\"serverId\":\"local-docs\",\"name\":\"Local Docs\",\"transport\":\"stdio\",\"command\":\"docs-mcp\",\"args\":[\"--stdio\"],\"oauth\":{\"enabled\":true,\"provider\":\"github\",\"status\":\"pending\"},\"capabilities\":{\"resources\":true,\"tools\":true},\"tools\":[{\"name\":\"docs_search\",\"description\":\"Search docs\"}]}",
                        token);
        assertThat(createMcp.status).isEqualTo(200);

        HttpResult checkMcp = request("POST", "/api/hermes/mcp/local-docs/check", "{}", token);
        assertThat(checkMcp.status).isEqualTo(200);
        assertThat(checkMcp.body).contains("\"tool_changed_notification\":true");
        assertThat(checkMcp.body).contains("\"schema_sanitizer\":\"snack4\"");

        HttpResult checkMcpAgain = request("POST", "/api/hermes/mcp/local-docs/check", "{}", token);
        assertThat(checkMcpAgain.status).isEqualTo(200);
        assertThat(checkMcpAgain.body).contains("\"tool_changed_notification\":false");

        HttpResult updateMcp =
                request(
                        "POST",
                        "/api/hermes/mcp",
                        "{\"serverId\":\"local-docs\",\"name\":\"Local Docs\",\"transport\":\"stdio\",\"command\":\"docs-mcp\",\"args\":[\"--stdio\"],\"oauth\":{\"enabled\":true,\"provider\":\"github\",\"status\":\"pending\"},\"capabilities\":{\"resources\":true,\"tools\":true},\"tools\":[{\"name\":\"docs_search\",\"description\":\"Search docs\"},{\"name\":\"docs_fetch\",\"description\":\"Fetch docs\"}]}",
                        token);
        assertThat(updateMcp.status).isEqualTo(200);

        HttpResult changedMcp = request("POST", "/api/hermes/mcp/local-docs/check", "{}", token);
        assertThat(changedMcp.status).isEqualTo(200);
        assertThat(changedMcp.body).contains("\"tool_changed_notification\":true");

        HttpResult mcpList = request("GET", "/api/hermes/mcp", null, token);
        assertThat(mcpList.status).isEqualTo(200);
        assertThat(mcpList.body).contains("Local Docs");
        assertThat(mcpList.body).contains("\"oauth\"");
        assertThat(mcpList.body).contains("\"capabilities\"");
        assertThat(mcpList.body).contains("\"last_tools_hash\"");

        HttpResult updateMcpOAuth =
                request(
                        "POST",
                        "/api/hermes/mcp",
                        "{\"serverId\":\"oauth-docs\",\"name\":\"OAuth Docs\",\"transport\":\"http\",\"endpoint\":\"https://mcp.example/sse\",\"oauth\":{\"enabled\":true,\"provider\":\"github\",\"auth_type\":\"oauth_pkce\",\"access_token\":\"secret-access\",\"refresh_token\":\"secret-refresh\",\"client_secret\":\"secret-client\",\"expires_at\":4102444800000,\"scopes\":[\"repo\"]},\"tools\":[{\"name\":\"docs_search\"}]}",
                        token);
        assertThat(updateMcpOAuth.status).isEqualTo(200);

        HttpResult oauthStatus =
                request("GET", "/api/hermes/mcp/oauth-docs/oauth/status", null, token);
        assertThat(oauthStatus.status).isEqualTo(200);
        assertThat(oauthStatus.body).contains("\"status\":\"authenticated\"");
        assertThat(oauthStatus.body).contains("\"has_access_token\":true");
        assertThat(oauthStatus.body).contains("\"has_refresh_token\":true");
        assertThat(oauthStatus.body).contains("\"has_client_secret\":true");
        assertThat(oauthStatus.body).doesNotContain("secret-access");
        assertThat(oauthStatus.body).doesNotContain("secret-refresh");

        HttpResult mcpListWithOAuth = request("GET", "/api/hermes/mcp", null, token);
        assertThat(mcpListWithOAuth.body).contains("\"has_access_token\":true");
        assertThat(mcpListWithOAuth.body).doesNotContain("secret-access");

        HttpResult beginOAuth =
                request(
                        "POST",
                        "/api/hermes/mcp/oauth-docs/oauth/begin",
                        "{\"authorization_endpoint\":\"https://auth.example/oauth/authorize\",\"token_endpoint\":\""
                                + "https://auth.example/oauth/token"
                                + "\",\"client_id\":\"client-1\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\",\"scopes\":[\"repo\",\"read:user\"]}",
                        token);
        assertThat(beginOAuth.status).isEqualTo(200);
        assertThat(beginOAuth.body).contains("\"status\":\"pending\"");
        assertThat(beginOAuth.body).contains("https://auth.example/oauth/authorize");
        assertThat(beginOAuth.body).contains("code_challenge_method=S256");
        assertThat(beginOAuth.body).contains("scope=repo%20read%3Auser");
        assertThat(beginOAuth.body).contains("\"has_code_verifier\":true");
        assertThat(beginOAuth.body).doesNotContain("\"code_verifier\":\"");

        HttpResult pendingStatus =
                request("GET", "/api/hermes/mcp/oauth-docs/oauth/status", null, token);
        assertThat(pendingStatus.body).contains("\"status\":\"pending\"");
        assertThat(pendingStatus.body).contains("\"has_access_token\":false");
        assertThat(pendingStatus.body).contains("\"has_code_verifier\":true");

        String pendingState = ONode.ofJson(pendingStatus.body).get("data").get("oauth").get("state").getString();
        assertThat(pendingState).isNotBlank();

        HttpResult stateMismatch =
                request(
                        "POST",
                        "/api/hermes/mcp/oauth-docs/oauth/callback",
                        "{\"code\":\"bad-code\",\"state\":\"wrong\",\"token_endpoint\":\"http://127.0.0.1:1/token\"}",
                        token);
        assertThat(stateMismatch.status).isGreaterThanOrEqualTo(400);

        TokenEndpointStub tokenEndpoint = TokenEndpointStub.start();
        try {
            beginOAuth =
                    request(
                            "POST",
                            "/api/hermes/mcp/oauth-docs/oauth/begin",
                            "{\"authorization_endpoint\":\"https://auth.example/oauth/authorize\",\"token_endpoint\":\""
                                    + tokenEndpoint.url()
                                    + "\",\"client_id\":\"client-1\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\",\"scopes\":[\"repo\",\"read:user\"]}",
                            token);
            pendingStatus =
                    request("GET", "/api/hermes/mcp/oauth-docs/oauth/status", null, token);
            pendingState =
                    ONode.ofJson(pendingStatus.body).get("data").get("oauth").get("state").getString();
            HttpResult completeOAuth =
                    request(
                            "GET",
                            "/api/hermes/mcp/oauth-docs/oauth/callback"
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
                    request("GET", "/api/hermes/mcp/oauth-docs/oauth/status", null, token);
            assertThat(authenticatedStatus.body).contains("\"status\":\"authenticated\"");
            assertThat(authenticatedStatus.body).contains("\"has_access_token\":true");
            assertThat(authenticatedStatus.body).doesNotContain("token-secret-1");

            HttpResult refreshOAuth =
                    request(
                            "POST",
                            "/api/hermes/mcp/oauth-docs/oauth/refresh",
                            "{}",
                            token);
            assertThat(refreshOAuth.status).isEqualTo(200);
            assertThat(refreshOAuth.body).contains("\"refreshed\":true");
            assertThat(refreshOAuth.body).contains("\"reconnect_required\":true");
            assertThat(refreshOAuth.body).contains("\"has_access_token\":true");
            assertThat(refreshOAuth.body).doesNotContain("token-secret-2");
            assertThat(refreshOAuth.body).doesNotContain("refresh-secret-2");
            assertThat(tokenEndpoint.lastForm.get("grant_type")).isEqualTo("refresh_token");
            assertThat(tokenEndpoint.lastForm.get("refresh_token")).isEqualTo("refresh-secret-1");

            HttpResult handle401 =
                    request(
                            "POST",
                            "/api/hermes/mcp/oauth-docs/oauth/handle-401",
                            "{}",
                            token);
            assertThat(handle401.status).isEqualTo(200);
            assertThat(handle401.body).contains("\"recovered\":true");
            assertThat(handle401.body).contains("\"needs_reauth\":false");
            assertThat(handle401.body).contains("\"reconnect_required\":true");
            assertThat(handle401.body).doesNotContain("token-secret-3");
            assertThat(tokenEndpoint.lastForm.get("refresh_token")).isEqualTo("refresh-secret-2");
        } finally {
            tokenEndpoint.stop();
        }

        HttpResult clearOAuth =
                request("POST", "/api/hermes/mcp/oauth-docs/oauth/clear", "{}", token);
        assertThat(clearOAuth.status).isEqualTo(200);
        assertThat(clearOAuth.body).contains("\"cleared\":true");
        assertThat(clearOAuth.body).doesNotContain("secret-refresh");

        HttpResult clearedStatus =
                request("GET", "/api/hermes/mcp/oauth-docs/oauth/status", null, token);
        assertThat(clearedStatus.body).contains("\"status\":\"cleared\"");
        assertThat(clearedStatus.body).contains("\"has_access_token\":false");

        HttpResult handle401AfterClear =
                request(
                        "POST",
                        "/api/hermes/mcp/oauth-docs/oauth/handle-401",
                        "{}",
                        token);
        assertThat(handle401AfterClear.status).isEqualTo(200);
        assertThat(handle401AfterClear.body).contains("\"needs_reauth\":true");
        assertThat(handle401AfterClear.body).contains("\"reconnect_required\":false");

        HttpResult kanbanBoards = request("GET", "/api/kanban/boards", null, token);
        assertThat(kanbanBoards.status).isEqualTo(200);
        assertThat(kanbanBoards.body).contains("default");

        HttpResult createBoard =
                request(
                        "POST",
                        "/api/kanban/boards",
                        "{\"slug\":\"dashboard-board\",\"name\":\"Dashboard 看板\",\"switch\":true}",
                        token);
        assertThat(createBoard.status).isEqualTo(200);
        assertThat(createBoard.body).contains("dashboard-board");

        HttpResult createTask =
                request(
                        "POST",
                        "/api/kanban/tasks",
                        "{\"title\":\"Kanban task\",\"assignee\":\"local\",\"status\":\"todo\"}",
                        token);
        assertThat(createTask.status).isEqualTo(200);
        String taskId = ONode.ofJson(createTask.body).get("data").get("id").getString();
        assertThat(taskId).isNotBlank();

        HttpResult moveTask =
                request(
                        "POST",
                        "/api/kanban/tasks/" + taskId + "/status",
                        "{\"status\":\"ready\"}",
                        token);
        assertThat(moveTask.status).isEqualTo(200);
        assertThat(moveTask.body).contains("\"status\":\"ready\"");

        HttpResult claimTask =
                request(
                        "PUT",
                        "/api/kanban/tasks/" + taskId,
                        "{\"status\":\"running\",\"claim_lock\":\"http-lock\",\"worker_id\":\"http-worker\"}",
                        token);
        assertThat(claimTask.status).isEqualTo(200);
        assertThat(claimTask.body).contains("\"status\":\"running\"");

        HttpResult reassignTask =
                request(
                        "POST",
                        "/api/kanban/tasks/" + taskId + "/reassign",
                        "{\"assignee\":\"next\",\"reclaim_first\":true,\"reason\":\"http test\"}",
                        token);
        assertThat(reassignTask.status).isEqualTo(200);
        assertThat(reassignTask.body).contains("\"assignee\":\"next\"");
        assertThat(reassignTask.body).contains("reassigned");

        HttpResult kanbanRuns =
                request("GET", "/api/kanban/tasks/" + taskId + "/runs", null, token);
        assertThat(kanbanRuns.status).isEqualTo(200);
        assertThat(kanbanRuns.body).contains("http-worker").contains("reclaimed");

        HttpResult kanbanEvents =
                request("GET", "/api/kanban/tasks/" + taskId + "/events", null, token);
        assertThat(kanbanEvents.status).isEqualTo(200);
        assertThat(kanbanEvents.body).contains("reassigned");

        HttpResult kanbanContext =
                request("GET", "/api/kanban/tasks/" + taskId + "/context", null, token);
        assertThat(kanbanContext.status).isEqualTo(200);
        assertThat(kanbanContext.body).contains("worker_context").contains("Prior attempts");

        HttpResult kanbanDiagnostics =
                request("GET", "/api/kanban/diagnostics?task=" + taskId, null, token);
        assertThat(kanbanDiagnostics.status).isEqualTo(200);

        HttpResult kanbanStats = request("GET", "/api/kanban/stats", null, token);
        assertThat(kanbanStats.status).isEqualTo(200);
        assertThat(kanbanStats.body).contains("by_status").contains("next");

        HttpResult kanbanWatch =
                request("GET", "/api/kanban/watch?kinds=reassigned&limit=20", null, token);
        assertThat(kanbanWatch.status).isEqualTo(200);
        assertThat(kanbanWatch.body).contains("reassigned");

        HttpResult notifySubscribe =
                request(
                        "POST",
                        "/api/kanban/notify-subscriptions",
                        "{\"task_id\":\""
                                + taskId
                                + "\",\"platform\":\"feishu\",\"chat_id\":\"chat-http\",\"thread_id\":\"thread-http\"}",
                        token);
        assertThat(notifySubscribe.status).isEqualTo(200);
        assertThat(notifySubscribe.body).contains("chat-http");

        HttpResult notifyList =
                request("GET", "/api/kanban/notify-subscriptions?task=" + taskId, null, token);
        assertThat(notifyList.status).isEqualTo(200);
        assertThat(notifyList.body).contains("thread-http");

        HttpResult notifyRemove =
                request(
                        "POST",
                        "/api/kanban/notify-subscriptions/remove",
                        "{\"task_id\":\""
                                + taskId
                                + "\",\"platform\":\"feishu\",\"chat_id\":\"chat-http\",\"thread_id\":\"thread-http\"}",
                        token);
        assertThat(notifyRemove.status).isEqualTo(200);
        assertThat(notifyRemove.body).contains("\"removed\":true");

        HttpResult kanbanLog =
                request("GET", "/api/kanban/tasks/" + taskId + "/log?tail=80", null, token);
        assertThat(kanbanLog.status).isEqualTo(200);
        assertThat(kanbanLog.body).contains("\"exists\":false").contains(taskId);

        HttpResult kanbanGc =
                request(
                        "POST",
                        "/api/kanban/gc",
                        "{\"event_retention_days\":30,\"log_retention_days\":30}",
                        token);
        assertThat(kanbanGc.status).isEqualTo(200);
        assertThat(kanbanGc.body).contains("removed_events").contains("removed_logs");

        HttpResult commentTask =
                request(
                        "POST",
                        "/api/kanban/tasks/" + taskId + "/comments",
                        "{\"author\":\"tester\",\"body\":\"ready to run\"}",
                        token);
        assertThat(commentTask.status).isEqualTo(200);
        assertThat(commentTask.body).contains("ready to run");

        HttpResult kanbanTasks = request("GET", "/api/kanban/tasks", null, token);
        assertThat(kanbanTasks.status).isEqualTo(200);
        assertThat(kanbanTasks.body).contains("Kanban task");

        HttpResult daemonStatus = request("GET", "/api/kanban/daemon", null, token);
        assertThat(daemonStatus.status).isEqualTo(200);
        assertThat(daemonStatus.body).contains("\"running\":false");

        HttpResult startDaemon =
                request(
                        "POST",
                        "/api/kanban/daemon/start",
                        "{\"interval_seconds\":30,\"max_spawn\":1,\"board\":\"dashboard-board\",\"dry_run\":true}",
                        token);
        assertThat(startDaemon.status).isEqualTo(200);
        assertThat(startDaemon.body).contains("\"running\":true");
        assertThat(startDaemon.body).contains("\"board\":\"dashboard-board\"");

        HttpResult stopDaemon = request("POST", "/api/kanban/daemon/stop", "{}", token);
        assertThat(stopDaemon.status).isEqualTo(200);
        assertThat(stopDaemon.body).contains("\"running\":false");

        HttpResult logs = request("GET", "/api/logs?file=agent&lines=20", null, token);
        assertThat(logs.status).isEqualTo(200);
        assertThat(logs.body).contains("\"lines\"");
    }

    @Test
    void shouldSupportDashboardChatRunsAndUploads() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult upload =
                requestMultipart("/api/chat/uploads", token, "hello.txt", "hello world");
        assertThat(upload.status).isEqualTo(200);
        assertThat(upload.body).contains("\"local_path\"");
        assertThat(upload.body).contains("\"mime_type\"");

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
    void shouldNotExposeTodoApis() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        assertThat(request("GET", "/api/todos", null, token).status).isEqualTo(404);
        assertThat(request("POST", "/api/todos", "{\"title\":\"removed\"}", token).status)
                .isEqualTo(404);
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

    private static String extractToken(String html) {
        Matcher matcher = Pattern.compile("__APP_SESSION_TOKEN__=\\\"([^\\\"]+)\\\"").matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static HttpResult request(String method, String path, String body, String token)
            throws Exception {
        return request(method, path, body, token, null);
    }

    private static HttpResult request(
            String method, String path, String body, String token, Map<String, String> headers)
            throws Exception {
        int attempts = 3;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return requestOnce(method, path, body, token, headers);
            } catch (SocketException e) {
                if (attempt >= attempts || e.getMessage() == null || !e.getMessage().contains("Connection reset")) {
                    throw e;
                }
                Thread.sleep(100L * attempt);
            }
        }
        throw new IllegalStateException("HTTP request failed without response");
    }

    private static HttpResult requestOnce(
            String method, String path, String body, String token, Map<String, String> headers)
            throws Exception {
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
        private volatile int refreshCount;

        private TokenEndpointStub(HttpServer server, int port) {
            this.server = server;
            this.port = port;
        }

        private static TokenEndpointStub start() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TokenEndpointStub stub = new TokenEndpointStub(server, server.getAddress().getPort());
            server.createContext("/token", stub::handle);
            server.start();
            return stub;
        }

        private String url() {
            return "http://127.0.0.1:" + port + "/token";
        }

        private void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws java.io.IOException {
            byte[] body = readBytes(exchange);
            lastForm = parseForm(new String(body, StandardCharsets.UTF_8));
            String grantType = lastForm.get("grant_type");
            String responseJson;
            if ("refresh_token".equals(grantType)) {
                refreshCount++;
                responseJson =
                        "{\"access_token\":\"token-secret-"
                                + (refreshCount + 1)
                                + "\",\"refresh_token\":\"refresh-secret-"
                                + (refreshCount + 1)
                                + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"repo read:user\"}";
            } else {
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
