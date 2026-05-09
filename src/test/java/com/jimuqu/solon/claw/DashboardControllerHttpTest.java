package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
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
                .contains("\"security\"")
                .contains("\"approvals\"")
                .contains("\"allow_private_urls\"")
                .contains("\"credential_file_count\"")
                .contains("\"sudo_password_configured\"");
        assertThat(diagnostics.body)
                .doesNotContain("\"sudo_password\"")
                .doesNotContain("\"credential_files\"")
                .doesNotContain("\"env_passthrough\"");

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
                .contains("\"hardlineCommandBlocks\":true")
                .contains("\"urlSafety\":true")
                .contains("\"credentialFilePolicy\":true")
                .contains("\"mcpUrlSafety\":true")
                .contains("mcpOauthUrlSafety")
                .doesNotContain("\"sudo_password\"")
                .doesNotContain("\"credential_files\"")
                .doesNotContain("\"env_passthrough\"");

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
        HttpResult apiCronNext =
                request("GET", "/api/jobs/next?include_disabled=true&limit=1", null, token);
        assertThat(apiCronNext.status).isEqualTo(200);
        ONode apiCronNextData = ONode.ofJson(apiCronNext.body);
        assertThat(apiCronNextData.get("count").getInt()).isEqualTo(1);
        assertThat(apiCronNextData.get("include_disabled").getBoolean()).isTrue();
        assertThat(apiCronNextData.get("jobs").get(0).get("id").getString()).isEqualTo(dashboardCronId);

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
                request("GET", "/api/jobs/" + dashboardCronId + "/runs?limit=5", null, token);
        assertThat(apiCronRuns.status).isEqualTo(200);
        ONode apiCronRunsData = ONode.ofJson(apiCronRuns.body);
        assertThat(apiCronRunsData.get("job_id").getString()).isEqualTo(dashboardCronId);
        assertThat(apiCronRunsData.get("count").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(apiCronRunsData.get("runs").get(0).get("run_id").getString()).isNotBlank();
        assertThat(apiCronRunsData.get("runs").get(0).get("output").getString())
                .contains("dashboard trigger ok: daily summary");

        HttpResult apiPutCron =
                request(
                        "PUT",
                        "/api/jobs/" + dashboardCronId,
                        "{\"name\":\"Daily summary via API\",\"schedule\":\"0 10 * * *\"}",
                        token);
        assertThat(apiPutCron.status).isEqualTo(200);
        assertThat(apiPutCron.body)
                .contains("\"job\"")
                .contains("\"name\":\"Daily summary via API\"")
                .contains("\"schedule_display\":\"0 10 * * *\"");

        HttpResult apiTriggerCron =
                request("POST", "/api/jobs/" + dashboardCronId + "/trigger", "{}", token);
        assertThat(apiTriggerCron.status).isEqualTo(200);
        assertThat(apiTriggerCron.body).contains("\"job\"").contains("\"last_status\":\"ok\"");

        HttpResult apiCronHistory =
                request("GET", "/api/jobs/" + dashboardCronId + "/history?limit=2", null, token);
        assertThat(apiCronHistory.status).isEqualTo(200);
        ONode apiCronHistoryData = ONode.ofJson(apiCronHistory.body);
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
                request("GET", "/api/jobs/" + dashboardCronId + "/inspect?limit=1", null, token);
        assertThat(apiCronInspect.status).isEqualTo(200);
        ONode apiCronInspectData = ONode.ofJson(apiCronInspect.body);
        assertThat(apiCronInspectData.get("job").get("id").getString()).isEqualTo(dashboardCronId);
        assertThat(apiCronInspectData.get("run_count").getInt()).isEqualTo(1);
        assertThat(apiCronInspectData.get("runs").get(0).get("status").getString()).isEqualTo("ok");

        HttpResult apiCronShow =
                request("GET", "/api/jobs/" + dashboardCronId + "/show?limit=1", null, token);
        assertThat(apiCronShow.status).isEqualTo(200);
        ONode apiCronShowData = ONode.ofJson(apiCronShow.body);
        assertThat(apiCronShowData.get("job").get("id").getString()).isEqualTo(dashboardCronId);
        assertThat(apiCronShowData.get("run_count").getInt()).isEqualTo(1);

        HttpResult cronStatus = request("GET", "/api/cron/jobs/status?limit=2", null, token);
        assertThat(cronStatus.status).isEqualTo(200);
        ONode cronStatusData = ONode.ofJson(cronStatus.body).get("data");
        assertThat(cronStatusData.get("total").getInt()).isEqualTo(1);
        assertThat(cronStatusData.get("active").getInt()).isEqualTo(1);
        assertThat(cronStatusData.get("due").getInt()).isGreaterThanOrEqualTo(0);
        assertThat(cronStatusData.get("next").get(0).get("id").getString()).isEqualTo(dashboardCronId);

        HttpResult apiJobsStatus = request("GET", "/api/jobs/status?limit=2", null, token);
        assertThat(apiJobsStatus.status).isEqualTo(200);
        ONode apiJobsStatusData = ONode.ofJson(apiJobsStatus.body);
        assertThat(apiJobsStatusData.get("total").getInt()).isEqualTo(1);
        assertThat(apiJobsStatusData.get("active").getInt()).isEqualTo(1);
        assertThat(apiJobsStatusData.get("next").get(0).get("id").getString()).isEqualTo(dashboardCronId);

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
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/enable", "{}", token);
        assertThat(enableCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(enableCron.body).get("data").get("enabled").getBoolean()).isTrue();
        HttpResult stopCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/stop", "{}", token);
        assertThat(stopCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(stopCron.body).get("data").get("enabled").getBoolean()).isFalse();
        HttpResult apiJobsStatusWithStopped =
                request("GET", "/api/jobs/status?include_disabled=true&limit=2", null, token);
        assertThat(apiJobsStatusWithStopped.status).isEqualTo(200);
        ONode apiJobsStatusWithStoppedData = ONode.ofJson(apiJobsStatusWithStopped.body);
        assertThat(apiJobsStatusWithStoppedData.get("total").getInt()).isEqualTo(1);
        assertThat(apiJobsStatusWithStoppedData.get("active").getInt()).isEqualTo(0);
        assertThat(apiJobsStatusWithStoppedData.get("paused").getInt()).isEqualTo(1);

        HttpResult startCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/start", "{}", token);
        assertThat(startCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(startCron.body).get("data").get("enabled").getBoolean()).isTrue();
        HttpResult disableCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/disable", "{}", token);
        assertThat(disableCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(disableCron.body).get("data").get("enabled").getBoolean()).isFalse();
        HttpResult resumeCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/resume", "{}", token);
        assertThat(resumeCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(resumeCron.body).get("data").get("enabled").getBoolean()).isTrue();
        HttpResult runCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/run", "{}", token);
        assertThat(runCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(runCron.body).get("data").get("id").getString()).isEqualTo(dashboardCronId);
        HttpResult retryCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/retry", "{}", token);
        assertThat(retryCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(retryCron.body).get("data").get("id").getString()).isEqualTo(dashboardCronId);
        HttpResult rerunCron =
                request("POST", "/api/cron/jobs/" + dashboardCronId + "/rerun", "{}", token);
        assertThat(rerunCron.status).isEqualTo(200);
        assertThat(ONode.ofJson(rerunCron.body).get("data").get("id").getString()).isEqualTo(dashboardCronId);

        HttpResult acpStatus = request("GET", "/api/jimuqu/acp/status", null, token);
        assertThat(acpStatus.status).isEqualTo(200);
        assertThat(acpStatus.body)
                .contains("\"transport\":\"stdio\"")
                .contains("\"command\":\"java -jar jimuqu-agent.jar acp\"")
                .contains("\"methods\":[\"initialize\",\"authenticate\",\"session/new\"")
                .contains("\"permissions/respond\"")
                .contains("\"mcp_servers\":true")
                .contains("\"commands\":[")
                .contains("\"name\":\"acp\"");

        HttpResult createMcp =
                request(
                        "POST",
                        "/api/jimuqu/mcp",
                        "{\"serverId\":\"dashboard-local-docs\",\"name\":\"Local Docs\",\"transport\":\"stdio\",\"command\":\"docs-mcp\",\"args\":[\"--stdio\"],\"oauth\":{\"enabled\":true,\"provider\":\"github\",\"status\":\"pending\"},\"capabilities\":{\"resources\":true,\"tools\":true},\"tools\":[{\"name\":\"docs_search\",\"description\":\"Search docs\"}]}",
                        token);
        assertThat(createMcp.status).isEqualTo(200);

        HttpResult checkMcp =
                request("POST", "/api/jimuqu/mcp/dashboard-local-docs/check", "{}", token);
        assertThat(checkMcp.status).isEqualTo(200);
        assertThat(checkMcp.body).contains("\"status\":\"disabled\"");
        assertThat(checkMcp.body).contains("\"tool_changed_notification\":true");
        assertThat(stringsAt(checkMcp.body, "added_tools")).containsExactly("docs_search");
        assertThat(stringsAt(checkMcp.body, "removed_tools")).isEmpty();
        assertThat(checkMcp.body).contains("\"schema_sanitizer\":\"snack4\"");

        HttpResult checkMcpAgain =
                request("POST", "/api/jimuqu/mcp/dashboard-local-docs/check", "{}", token);
        assertThat(checkMcpAgain.status).isEqualTo(200);
        assertThat(checkMcpAgain.body).contains("\"tool_changed_notification\":false");

        HttpResult updateMcp =
                request(
                        "POST",
                        "/api/jimuqu/mcp",
                        "{\"serverId\":\"dashboard-local-docs\",\"name\":\"Local Docs\",\"transport\":\"stdio\",\"command\":\"docs-mcp\",\"args\":[\"--stdio\"],\"oauth\":{\"enabled\":true,\"provider\":\"github\",\"status\":\"pending\"},\"capabilities\":{\"resources\":true,\"tools\":true},\"tools\":[{\"name\":\"docs_search\",\"description\":\"Search docs\"},{\"name\":\"docs_fetch\",\"description\":\"Fetch docs\"}]}",
                        token);
        assertThat(updateMcp.status).isEqualTo(200);

        HttpResult changedMcp =
                request("POST", "/api/jimuqu/mcp/dashboard-local-docs/check", "{}", token);
        assertThat(changedMcp.status).isEqualTo(200);
        assertThat(changedMcp.body).contains("\"status\":\"disabled\"");
        assertThat(changedMcp.body).contains("\"tool_changed_notification\":true");
        assertThat(stringsAt(changedMcp.body, "added_tools"))
                .containsExactly("docs_fetch", "docs_search");
        assertThat(stringsAt(changedMcp.body, "removed_tools")).isEmpty();

        HttpResult updateMcpForReloadAll =
                request(
                        "POST",
                        "/api/jimuqu/mcp",
                        "{\"serverId\":\"dashboard-local-docs\",\"name\":\"Local Docs\",\"transport\":\"stdio\",\"command\":\"docs-mcp\",\"args\":[\"--stdio\"],\"oauth\":{\"enabled\":true,\"provider\":\"github\",\"status\":\"pending\"},\"capabilities\":{\"resources\":true,\"tools\":true},\"tools\":[{\"name\":\"docs_search\",\"description\":\"Search docs\"},{\"name\":\"docs_fetch\",\"description\":\"Fetch docs\"},{\"name\":\"docs_rank\",\"description\":\"Rank docs\"}]}",
                        token);
        assertThat(updateMcpForReloadAll.status).isEqualTo(200);

        HttpResult reloadAllMcp =
                request("POST", "/api/jimuqu/mcp/reload", "{}", token);
        assertThat(reloadAllMcp.status).isEqualTo(200);
        ONode reloadAllMcpData = ONode.ofJson(reloadAllMcp.body).get("data");
        assertThat(reloadAllMcpData.get("tool_count").getInt()).isGreaterThanOrEqualTo(3);
        assertThat(reloadAllMcpData.get("server_count").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(stringsAt(reloadAllMcp.body, "changed_servers")).contains("dashboard-local-docs");
        assertThat(reloadAllMcp.body).contains("\"tool_changed_notification\":true");

        HttpResult reloadAllMcpAgain =
                request("POST", "/api/jimuqu/mcp/reload", "{}", token);
        assertThat(reloadAllMcpAgain.status).isEqualTo(200);
        assertThat(stringsAt(reloadAllMcpAgain.body, "unchanged_servers")).contains("dashboard-local-docs");

        HttpResult mcpList = request("GET", "/api/jimuqu/mcp", null, token);
        assertThat(mcpList.status).isEqualTo(200);
        assertThat(mcpList.body).contains("Local Docs");
        assertThat(mcpList.body).contains("\"oauth\"");
        assertThat(mcpList.body).contains("\"capabilities\"");
        assertThat(mcpList.body).contains("\"last_tools_hash\"");

        HttpResult secretMcp =
                request(
                        "POST",
                        "/api/jimuqu/mcp",
                        "{\"serverId\":\"secret-stdio-docs\",\"name\":\"Secret Stdio\",\"transport\":\"http\",\"endpoint\":\"https://example.com/sse?token=secret-endpoint-token\",\"command\":\"OPENAI_API_KEY=sk-test-dashboard-secret docs-mcp\",\"args\":[\"--token=secret-arg-value\",\"--stdio\"],\"auth\":{\"header\":\"Authorization: Bearer ghp_mcpsecret12345\"}}",
                        token);
        assertThat(secretMcp.status).isEqualTo(200);
        HttpResult userInfoMcp =
                request(
                        "POST",
                        "/api/jimuqu/mcp",
                        "{\"serverId\":\"userinfo-docs\",\"name\":\"Userinfo Docs\",\"transport\":\"http\",\"endpoint\":\"https://user:secret-endpoint-pass@example.com/sse?token=secret-userinfo-token\",\"tools\":[{\"name\":\"docs_search\"}]}",
                        token);
        assertThat(userInfoMcp.status).isGreaterThanOrEqualTo(400);
        assertThat(userInfoMcp.body)
                .doesNotContain("secret-endpoint-pass")
                .doesNotContain("secret-userinfo-token");
        HttpResult secretMcpList = request("GET", "/api/jimuqu/mcp", null, token);
        assertThat(secretMcpList.status).isEqualTo(200);
        assertThat(secretMcpList.body)
                .contains("OPENAI_API_KEY=***")
                .contains("--token=***")
                .contains("Authorization: Bearer ***")
                .contains("https://example.com/sse?token=***")
                .doesNotContain("sk-test-dashboard-secret")
                .doesNotContain("secret-arg-value")
                .doesNotContain("secret-endpoint-pass")
                .doesNotContain("secret-userinfo-token")
                .doesNotContain("secret-endpoint-token")
                .doesNotContain("ghp_mcpsecret12345");

        HttpResult updateMcpOAuth =
                request(
                        "POST",
                        "/api/jimuqu/mcp",
                        "{\"serverId\":\"oauth-docs\",\"name\":\"OAuth Docs\",\"transport\":\"http\",\"endpoint\":\"https://example.com/sse\",\"oauth\":{\"enabled\":true,\"provider\":\"github\",\"auth_type\":\"oauth_pkce\",\"access_token\":\"secret-access\",\"refresh_token\":\"secret-refresh\",\"client_secret\":\"secret-client\",\"expires_at\":4102444800000,\"scopes\":[\"repo\"]},\"tools\":[{\"name\":\"docs_search\"}]}",
                        token);
        assertThat(updateMcpOAuth.status).isEqualTo(200);

        HttpResult oauthStatus =
                request("GET", "/api/jimuqu/mcp/oauth-docs/oauth/status", null, token);
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
                        "/api/jimuqu/mcp",
                        "{\"serverId\":\"oauth-error-docs\",\"name\":\"OAuth Error Docs\",\"transport\":\"http\",\"endpoint\":\"https://example.com/sse\",\"oauth\":{\"enabled\":true,\"status\":\"pending\",\"error\":\"access_token=ghp_oautherror12345&redirect_uri=http://localhost/cb?token=secret-oauth-error\",\"message\":\"client_secret=oauth-message-secret\"},\"tools\":[{\"name\":\"docs_search\"}]}",
                        token);
        assertThat(oauthErrorServer.status).isEqualTo(200);
        HttpResult oauthErrorStatus =
                request("GET", "/api/jimuqu/mcp/oauth-error-docs/oauth/status", null, token);
        assertThat(oauthErrorStatus.status).isEqualTo(200);
        assertThat(oauthErrorStatus.body).contains("access_token=***");
        assertThat(oauthErrorStatus.body).contains("client_secret=***");
        assertThat(oauthErrorStatus.body)
                .doesNotContain("ghp_oautherror12345")
                .doesNotContain("secret-oauth-error")
                .doesNotContain("oauth-message-secret");

        HttpResult mcpListWithOAuth = request("GET", "/api/jimuqu/mcp", null, token);
        assertThat(mcpListWithOAuth.body).contains("\"has_access_token\":true");
        assertThat(mcpListWithOAuth.body).doesNotContain("secret-access");

        HttpResult beginOAuth =
                request(
                        "POST",
                        "/api/jimuqu/mcp/oauth-docs/oauth/begin",
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
                        "/api/jimuqu/mcp/oauth-docs/oauth/callback?error="
                                + URLEncoder.encode(
                                        "access_token=ghp_callbackerror12345&redirect_uri=http://localhost/cb?token=secret-callback-error",
                                        "UTF-8"),
                        null,
                        null);
        assertThat(oauthCallbackError.status).isGreaterThanOrEqualTo(400);
        assertThat(oauthCallbackError.body)
                .doesNotContain("ghp_callbackerror12345")
                .doesNotContain("secret-callback-error");

        HttpResult blockedAuthorizationEndpoint =
                request(
                        "POST",
                        "/api/jimuqu/mcp/oauth-docs/oauth/begin",
                        "{\"authorization_endpoint\":\"http://169.254.169.254/latest/meta-data/?token=secret-auth\",\"client_id\":\"client-1\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\"}",
                        token);
        assertThat(blockedAuthorizationEndpoint.status).isGreaterThanOrEqualTo(400);
        assertThat(blockedAuthorizationEndpoint.body).doesNotContain("secret-auth");

        HttpResult pendingStatus =
                request("GET", "/api/jimuqu/mcp/oauth-docs/oauth/status", null, token);
        assertThat(pendingStatus.body).contains("\"status\":\"pending\"");
        assertThat(pendingStatus.body).contains("\"has_access_token\":false");
        assertThat(pendingStatus.body).contains("\"has_code_verifier\":true");

        String pendingState = ONode.ofJson(pendingStatus.body).get("data").get("oauth").get("state").getString();
        assertThat(pendingState).isNotBlank();

        HttpResult blockedTokenEndpoint =
                request(
                        "POST",
                        "/api/jimuqu/mcp/oauth-docs/oauth/callback",
                        "{\"code\":\"auth-code-blocked\",\"state\":\""
                                + pendingState
                                + "\",\"token_endpoint\":\"http://169.254.169.254/latest/meta-data/?token=secret-token\"}",
                        token);
        assertThat(blockedTokenEndpoint.status).isGreaterThanOrEqualTo(400);
        assertThat(blockedTokenEndpoint.body).doesNotContain("secret-token");

        HttpResult stateMismatch =
                request(
                        "POST",
                        "/api/jimuqu/mcp/oauth-docs/oauth/callback",
                        "{\"code\":\"bad-code\",\"state\":\"wrong\",\"token_endpoint\":\"http://127.0.0.1:1/token\"}",
                        token);
        assertThat(stateMismatch.status).isGreaterThanOrEqualTo(400);

        TokenEndpointStub tokenEndpoint = TokenEndpointStub.start();
        try {
            beginOAuth =
                    request(
                            "POST",
                            "/api/jimuqu/mcp/oauth-docs/oauth/begin",
                            "{\"authorization_endpoint\":\"https://example.com/oauth/authorize\",\"token_endpoint\":\""
                                    + tokenEndpoint.url()
                                    + "\",\"client_id\":\"client-1\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\",\"scopes\":[\"repo\",\"read:user\"]}",
                            token);
            pendingStatus =
                    request("GET", "/api/jimuqu/mcp/oauth-docs/oauth/status", null, token);
            pendingState =
                    ONode.ofJson(pendingStatus.body).get("data").get("oauth").get("state").getString();
            HttpResult completeOAuth =
                    request(
                            "GET",
                            "/api/jimuqu/mcp/oauth-docs/oauth/callback"
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
                    request("GET", "/api/jimuqu/mcp/oauth-docs/oauth/status", null, token);
            assertThat(authenticatedStatus.body).contains("\"status\":\"authenticated\"");
            assertThat(authenticatedStatus.body).contains("\"has_access_token\":true");
            assertThat(authenticatedStatus.body).doesNotContain("token-secret-1");

            HttpResult refreshOAuth =
                    request(
                            "POST",
                            "/api/jimuqu/mcp/oauth-docs/oauth/refresh",
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

            HttpResult handle401 =
                    request(
                            "POST",
                            "/api/jimuqu/mcp/oauth-docs/oauth/handle-401",
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
                            "/api/jimuqu/mcp/oauth-docs/oauth/begin",
                            "{\"authorization_endpoint\":\"https://example.com/oauth/authorize\",\"token_endpoint\":\""
                                    + tokenEndpoint.redirectUrl()
                                    + "\",\"client_id\":\"client-1\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\",\"scopes\":[\"repo\",\"read:user\"]}",
                            token);
            pendingStatus =
                    request("GET", "/api/jimuqu/mcp/oauth-docs/oauth/status", null, token);
            pendingState =
                    ONode.ofJson(pendingStatus.body).get("data").get("oauth").get("state").getString();
            HttpResult redirectedTokenEndpoint =
                    request(
                            "GET",
                            "/api/jimuqu/mcp/oauth-docs/oauth/callback"
                                    + "?code=auth-code-redirect&state="
                                    + URLEncoder.encode(pendingState, "UTF-8"),
                            null,
                            null);
            assertThat(redirectedTokenEndpoint.status).isGreaterThanOrEqualTo(400);
            assertThat(redirectedTokenEndpoint.body).doesNotContain("secret-redirect");
            assertThat(tokenEndpoint.redirectForm.get("grant_type"))
                    .isEqualTo("authorization_code");
            assertThat(tokenEndpoint.redirectForm.get("code")).isEqualTo("auth-code-redirect");
        } finally {
            tokenEndpoint.stop();
        }

        HttpResult blockedRefreshServer =
                request(
                        "POST",
                        "/api/jimuqu/mcp",
                        "{\"serverId\":\"blocked-oauth-docs\",\"name\":\"Blocked OAuth Docs\",\"transport\":\"http\",\"endpoint\":\"https://example.com/sse\",\"oauth\":{\"enabled\":true,\"status\":\"authenticated\",\"client_id\":\"client-1\",\"access_token\":\"secret-access\",\"refresh_token\":\"refresh-secret\",\"token_endpoint\":\"http://169.254.169.254/latest/meta-data/?token=secret-refresh-url\"},\"tools\":[{\"name\":\"docs_search\"}]}",
                        token);
        assertThat(blockedRefreshServer.status).isEqualTo(200);
        HttpResult blockedRefresh =
                request(
                        "POST",
                        "/api/jimuqu/mcp/blocked-oauth-docs/oauth/refresh",
                        "{}",
                        token);
        assertThat(blockedRefresh.status).isGreaterThanOrEqualTo(400);
        assertThat(blockedRefresh.body).doesNotContain("secret-refresh-url");

        HttpResult clearOAuth =
                request("POST", "/api/jimuqu/mcp/oauth-docs/oauth/clear", "{}", token);
        assertThat(clearOAuth.status).isEqualTo(200);
        assertThat(clearOAuth.body).contains("\"cleared\":true");
        assertThat(clearOAuth.body).doesNotContain("secret-refresh");

        HttpResult clearedStatus =
                request("GET", "/api/jimuqu/mcp/oauth-docs/oauth/status", null, token);
        assertThat(clearedStatus.body).contains("\"status\":\"cleared\"");
        assertThat(clearedStatus.body).contains("\"has_access_token\":false");

        HttpResult handle401AfterClear =
                request(
                        "POST",
                        "/api/jimuqu/mcp/oauth-docs/oauth/handle-401",
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

        HttpResult createAgent =
                request(
                        "POST",
                        "/api/agents",
                        "{\"name\":\"next\",\"role_prompt\":\"HTTP Kanban worker\"}",
                        token);
        assertThat(createAgent.status).isEqualTo(200);
        assertThat(createAgent.body).contains("\"name\":\"next\"");

        HttpResult renameBoard =
                request(
                        "PUT",
                        "/api/kanban/boards/dashboard-board",
                        "{\"name\":\"Dashboard renamed\"}",
                        token);
        assertThat(renameBoard.status).isEqualTo(200);
        assertThat(renameBoard.body).contains("Dashboard renamed");

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

        HttpResult stepTask =
                request(
                        "POST",
                        "/api/kanban/tasks/" + taskId + "/step",
                        "{\"step_key\":\"review\",\"workflow_template_id\":\"delivery\",\"note\":\"dashboard step\",\"actor\":\"dashboard\"}",
                        token);
        assertThat(stepTask.status).isEqualTo(200);
        assertThat(stepTask.body)
                .contains("\"current_step_key\":\"review\"")
                .contains("\"workflow_template_id\":\"delivery\"")
                .contains("step_changed")
                .contains("dashboard step");

        HttpResult createChildTask =
                request(
                        "POST",
                        "/api/kanban/tasks",
                        "{\"title\":\"Kanban child\",\"assignee\":\"local\",\"status\":\"todo\"}",
                        token);
        assertThat(createChildTask.status).isEqualTo(200);
        String childTaskId = ONode.ofJson(createChildTask.body).get("data").get("id").getString();
        HttpResult linkTask =
                request(
                        "POST",
                        "/api/kanban/links",
                        "{\"parent_id\":\"" + taskId + "\",\"child_id\":\"" + childTaskId + "\"}",
                        token);
        assertThat(linkTask.status).isEqualTo(200);
        assertThat(linkTask.body).contains(taskId).contains(childTaskId).contains("parents");
        HttpResult unlinkTask =
                request(
                        "POST",
                        "/api/kanban/links/remove",
                        "{\"parent_id\":\"" + taskId + "\",\"child_id\":\"" + childTaskId + "\"}",
                        token);
        assertThat(unlinkTask.status).isEqualTo(200);
        assertThat(unlinkTask.body).contains(childTaskId).contains("unlinked");

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

        HttpResult kanbanBlocked =
                request(
                        "POST",
                        "/api/kanban/tasks/" + taskId + "/status",
                        "{\"status\":\"blocked\",\"result\":\"dashboard unblock wait\"}",
                        token);
        assertThat(kanbanBlocked.status).isEqualTo(200);
        assertThat(kanbanBlocked.body).contains("\"status\":\"blocked\"");

        HttpResult kanbanUnblock =
                request(
                        "POST",
                        "/api/kanban/tasks/" + taskId + "/unblock",
                        "{}",
                        token);
        assertThat(kanbanUnblock.status).isEqualTo(200);
        assertThat(kanbanUnblock.body)
                .contains("\"status\":\"ready\"")
                .contains("unblocked")
                .contains("dashboard unblock wait");

        HttpResult kanbanCompleteForEdit =
                request(
                        "POST",
                        "/api/kanban/tasks/" + taskId + "/status",
                        "{\"status\":\"done\",\"result\":\"dashboard edit seed\",\"summary\":\"dashboard seed summary\"}",
                        token);
        assertThat(kanbanCompleteForEdit.status).isEqualTo(200);
        assertThat(kanbanCompleteForEdit.body).contains("\"status\":\"done\"");

        HttpResult kanbanEdit =
                request(
                        "POST",
                        "/api/kanban/tasks/" + taskId + "/edit",
                        "{\"result\":\"dashboard edited result\",\"summary\":\"dashboard edited summary\",\"metadata\":{\"tests_run\":3}}",
                        token);
        assertThat(kanbanEdit.status).isEqualTo(200);
        assertThat(kanbanEdit.body)
                .contains("dashboard edited result")
                .contains("dashboard edited summary")
                .contains("tests_run")
                .contains("edited");

        HttpResult kanbanEvents =
                request("GET", "/api/kanban/tasks/" + taskId + "/events", null, token);
        assertThat(kanbanEvents.status).isEqualTo(200);
        assertThat(kanbanEvents.body).contains("step_changed").contains("reassigned").contains("unblocked").contains("edited");

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

        HttpResult kanbanGuide = request("GET", "/api/kanban/guide?board=dashboard-board", null, token);
        assertThat(kanbanGuide.status).isEqualTo(200);
        assertThat(kanbanGuide.body)
                .contains("\"drawer_sections\"")
                .contains("\"pipeline_overview\"")
                .contains("\"automation_actions\"")
                .contains("dashboard-board");

        HttpResult kanbanAssignees = request("GET", "/api/kanban/assignees", null, token);
        assertThat(kanbanAssignees.status).isEqualTo(200);
        assertThat(kanbanAssignees.body)
                .contains("\"name\":\"next\"")
                .contains("\"configured\":true")
                .contains("\"on_disk\":true")
                .contains("\"name\":\"local\"")
                .contains("\"configured\":false")
                .contains("\"counts\"");

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

        HttpResult kanbanDrawer =
                request("GET", "/api/kanban/tasks/" + taskId + "/drawer?tail=80", null, token);
        assertThat(kanbanDrawer.status).isEqualTo(200);
        assertThat(kanbanDrawer.body)
                .contains("\"task\"")
                .contains("\"runs\"")
                .contains("\"events\"")
                .contains("\"pipeline_overview\"")
                .contains("\"context\"")
                .contains("\"notifications\"")
                .contains("\"log\"")
                .contains("\"actions\"")
                .contains("\"supports_history\":true")
                .contains("\"supports_reassign\":true")
                .contains("\"latest_run\"")
                .contains("\"worker_id\":\"http-worker\"")
                .contains("thread-http")
                .contains("worker_context")
                .contains("can_reassign");

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

        HttpResult filteredKanbanTasks =
                request("GET", "/api/kanban/tasks?assignee=next&status=done", null, token);
        assertThat(filteredKanbanTasks.status).isEqualTo(200);
        assertThat(filteredKanbanTasks.body).contains("Kanban task").doesNotContain("Kanban child");

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

        HttpResult archiveBoard =
                request(
                        "DELETE",
                        "/api/kanban/boards/dashboard-board",
                        null,
                        token);
        assertThat(archiveBoard.status).isEqualTo(200);
        assertThat(archiveBoard.body).contains("\"action\":\"archived\"");

        HttpResult archivedBoards = request("GET", "/api/kanban/boards?archived=true", null, token);
        assertThat(archivedBoards.status).isEqualTo(200);
        assertThat(archivedBoards.body).contains("dashboard-board").contains("\"archived\":true");

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
                "printf api_key=sk-test-secret-token-value",
                "需要确认危险命令 Authorization: Bearer ghp_dashboardsecret12345");

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
        String approvalKey = pendingData.get("approval_key").getString();
        assertThat(selector).isNotBlank();
        assertThat(selector).isEqualTo(pendingData.get("approval_id").getString());
        assertThat(selector).doesNotContain("execute_shell:");
        assertThat(approvalKey).startsWith("execute_shell:").endsWith(":***");

        HttpResult historyBefore =
                request("GET", "/api/diagnostics/approvals/history?limit=20", null, token);
        assertThat(historyBefore.status).isEqualTo(200);
        assertThat(historyBefore.body)
                .contains("\"event_type\":\"request\"")
                .contains("\"command_preview\":\"printf api_key=***\"")
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
                .doesNotContain("sk-test-secret-token-value")
                .doesNotContain("ghp_dashboardsecret12345")
                .contains("Authorization: Bearer ***");

        HttpResult after =
                request("GET", "/api/diagnostics/approvals?limit=20", null, token);
        assertThat(after.status).isEqualTo(200);
        assertThat(after.body).doesNotContain("\"session_id\":\"dashboard-approval-chat\"");
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
                .contains("\"tool_name\":\"execute_shell\"");
        ONode alwaysData = ONode.ofJson(always.body).get("data").get("items").get(0);
        String approval = alwaysData.get("approval").getString();
        String approvalId = alwaysData.get("approval_id").getString();
        String patternKey = alwaysData.get("pattern_key").getString();
        assertThat(approval).startsWith("execute_shell:").endsWith(":***");
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
                .contains("\"approval_id\":\"" + jsonEscape(approvalId) + "\"")
                .contains("长期授权已撤销")
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
                .contains("\"approval_key\":\"execute_shell:")
                .contains(":***\"")
                .doesNotContain("execute_shell:rm_recursive_root:97c852eaef0753db")
                .contains("撤销长期审批授权");

        HttpResult after =
                request("GET", "/api/diagnostics/approvals/always?limit=20", null, token);
        assertThat(after.status).isEqualTo(200);
        assertThat(after.body).doesNotContain(approvalId);
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
                                + jsonEscape(confirmId)
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
    void shouldExposeApiServerCronJobCompatibilityRoutes() throws Exception {
        String token = extractToken(request("GET", "/", null, null).body);

        HttpResult missingName =
                request("POST", "/api/jobs", "{\"schedule\":\"every 1h\",\"prompt\":\"missing name\"}", token);
        assertThat(missingName.status).isEqualTo(400);
        assertThat(missingName.body).contains("name");

        HttpResult invalidRepeat =
                request(
                        "POST",
                        "/api/jobs",
                        "{\"name\":\"bad-repeat\",\"schedule\":\"every 1h\",\"prompt\":\"x\",\"repeat\":0}",
                        token);
        assertThat(invalidRepeat.status).isEqualTo(400);
        assertThat(invalidRepeat.body).contains("repeat");

        HttpResult create =
                request(
                        "POST",
                        "/api/jobs",
                        "{\"name\":\"compat-cron\",\"schedule\":\"every 1h\",\"prompt\":\"compat prompt\",\"repeat\":2,\"no_agent\":true,\"script\":\"compat-run.py\"}",
                        token);
        assertThat(create.status).isEqualTo(200);
        ONode created = ONode.ofJson(create.body).get("job");
        String jobId = created.get("id").getString();
        assertThat(jobId).isNotBlank();
        assertThat(created.get("name").getString()).isEqualTo("compat-cron");

        HttpResult list = request("GET", "/api/jobs", null, token);
        assertThat(list.status).isEqualTo(200);
        assertThat(list.body).contains("\"jobs\"").contains("compat-cron");

        HttpResult dashboardGuide = request("GET", "/api/cron/jobs/guide", null, token);
        assertThat(dashboardGuide.status).isEqualTo(200);
        ONode dashboardGuideData = ONode.ofJson(dashboardGuide.body).get("data");
        assertThat(dashboardGuideData.get("editable_fields").toJson())
                .contains("deliver_chat_id")
                .contains("wrap_response");
        assertThat(dashboardGuideData.get("skill_binding").get("remove").toJson()).contains("--remove-skill");
        assertThat(dashboardGuideData.get("skill_binding").get("dependency_flags").toJson())
                .contains("--context-from job-id")
                .contains("--clear-context-from");
        assertThat(dashboardGuideData.get("runtime_modes").get("clear_flags").toJson())
                .contains("--clear-script")
                .contains("--clear-enabled-toolsets");
        assertThat(dashboardGuideData.get("security").get("prompt_scan").toJson()).contains("prompt_injection");

        HttpResult apiGuide = request("GET", "/api/jobs/guide", null, token);
        assertThat(apiGuide.status).isEqualTo(200);
        ONode apiGuideData = ONode.ofJson(apiGuide.body);
        assertThat(apiGuideData.get("aliases").get("run").toJson()).contains("retry").contains("rerun");
        assertThat(apiGuideData.get("delivery").get("targets").toJson()).contains("feishu").contains("yuanbao");
        assertThat(apiGuideData.get("delivery").get("target_forms").toJson()).contains("platform:chat_id:thread_id");
        assertThat(apiGuideData.get("delivery").get("wrap_flags").toJson()).contains("--raw");

        HttpResult get = request("GET", "/api/jobs/" + jobId, null, token);
        assertThat(get.status).isEqualTo(200);
        assertThat(get.body).contains("\"job\"").contains("compat prompt");

        HttpResult next = request("GET", "/api/jobs/next?limit=1", null, token);
        assertThat(next.status).isEqualTo(200);
        ONode nextJob = ONode.ofJson(next.body).get("jobs").get(0);
        assertThat(nextJob.get("id").getString()).isEqualTo(jobId);
        assertThat(nextJob.get("name").getString()).isEqualTo("compat-cron");

        HttpResult invalidId = request("GET", "/api/jobs/not-a-valid-hex!", null, token);
        assertThat(invalidId.status).isEqualTo(400);
        assertThat(invalidId.body).contains("Invalid");

        HttpResult patchUnknown =
                request("PATCH", "/api/jobs/" + jobId, "{\"evil_field\":\"ignored\"}", token);
        assertThat(patchUnknown.status).isEqualTo(400);
        assertThat(patchUnknown.body).contains("No valid fields");

        HttpResult patch =
                request(
                        "PATCH",
                        "/api/jobs/" + jobId,
                        "{\"name\":\"compat-renamed\",\"evil_field\":\"ignored\",\"__proto__\":\"ignored\"}",
                        token);
        assertThat(patch.status).isEqualTo(200);
        assertThat(patch.body).contains("compat-renamed");
        assertThat(patch.body).doesNotContain("evil_field");
        assertThat(patch.body).doesNotContain("__proto__");

        HttpResult clearRepeat =
                request("PATCH", "/api/jobs/" + jobId, "{\"repeat\":0}", token);
        assertThat(clearRepeat.status).isEqualTo(200);
        assertThat(clearRepeat.body).contains("\"times\":null");

        HttpResult patchPaused =
                request(
                        "PATCH",
                        "/api/jobs/" + jobId,
                        "{\"status\":\"paused\",\"paused_reason\":\"maintenance\"}",
                        token);
        assertThat(patchPaused.status).isEqualTo(200);
        assertThat(patchPaused.body)
                .contains("\"enabled\":false")
                .contains("\"paused_reason\":\"maintenance\"");

        HttpResult patchResumed =
                request("PATCH", "/api/jobs/" + jobId, "{\"state\":\"active\"}", token);
        assertThat(patchResumed.status).isEqualTo(200);
        assertThat(patchResumed.body)
                .contains("\"enabled\":true")
                .doesNotContain("maintenance");

        HttpResult pause = request("POST", "/api/jobs/" + jobId + "/pause", "{}", token);
        assertThat(pause.status).isEqualTo(200);
        assertThat(pause.body).contains("\"enabled\":false");

        HttpResult enable = request("POST", "/api/jobs/" + jobId + "/enable", "{}", token);
        assertThat(enable.status).isEqualTo(200);
        assertThat(enable.body).contains("\"enabled\":true");

        HttpResult disable = request("POST", "/api/jobs/" + jobId + "/disable", "{}", token);
        assertThat(disable.status).isEqualTo(200);
        assertThat(disable.body).contains("\"enabled\":false");

        HttpResult listDefault = request("GET", "/api/jobs", null, token);
        assertThat(listDefault.body).doesNotContain("compat-renamed");

        HttpResult listAll = request("GET", "/api/jobs?include_disabled=true", null, token);
        assertThat(listAll.body).contains("compat-renamed");

        HttpResult resume = request("POST", "/api/jobs/" + jobId + "/resume", "{}", token);
        assertThat(resume.status).isEqualTo(200);
        assertThat(resume.body).contains("\"enabled\":true");

        HttpResult stop = request("POST", "/api/jobs/" + jobId + "/stop", "{}", token);
        assertThat(stop.status).isEqualTo(200);
        assertThat(stop.body).contains("\"enabled\":false");

        HttpResult start = request("POST", "/api/jobs/" + jobId + "/start", "{}", token);
        assertThat(start.status).isEqualTo(200);
        assertThat(start.body).contains("\"enabled\":true");

        File scriptsDir = new File(runtimeHome, "scripts");
        FileUtil.mkdir(scriptsDir);
        FileUtil.writeUtf8String("print('compat cron run')\n", new File(scriptsDir, "compat-run.py"));
        HttpResult run = request("POST", "/api/jobs/" + jobId + "/run", "{}", token);
        assertThat(run.status).isEqualTo(200);
        assertThat(run.body).contains("\"job\"").contains("compat-renamed");

        HttpResult retry = request("POST", "/api/jobs/" + jobId + "/retry", "{}", token);
        assertThat(retry.status).isEqualTo(200);
        assertThat(retry.body).contains("\"job\"").contains("compat-renamed");

        HttpResult rerun = request("POST", "/api/jobs/" + jobId + "/rerun", "{}", token);
        assertThat(rerun.status).isEqualTo(200);
        assertThat(rerun.body).contains("\"job\"").contains("compat-renamed");

        HttpResult delete = request("DELETE", "/api/jobs/" + jobId, null, token);
        assertThat(delete.status).isEqualTo(200);
        assertThat(delete.body).contains("\"ok\":true");

        HttpResult missing = request("GET", "/api/jobs/" + jobId, null, token);
        assertThat(missing.status).isEqualTo(404);
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

        HttpResult run = request("POST", "/api/jimuqu/curator/run?force=true", "{}", token);
        assertThat(run.status).isEqualTo(200);
        assertThat(run.body).contains("curator://report");
        assertThat(run.body).contains("skill://sample-skill");
        assertThat(run.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult list = request("GET", "/api/jimuqu/curator?limit=5", null, token);
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

        HttpResult detail = request("GET", "/api/jimuqu/curator/" + reportId, null, token);
        assertThat(detail.status).isEqualTo(200);
        assertThat(detail.body).contains("curator://report");
        assertThat(detail.body).contains("skill://sample-skill");
        assertThat(detail.body).doesNotContain(runtimeHome.getAbsolutePath());
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
        assertThat(rejectedDiary.body).doesNotContain(runtimeHome.getAbsolutePath());
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
                        "/api/jimuqu/media/index",
                        "{\"mediaId\":\"dashboard-media-secret\",\"platform\":\"MEMORY\","
                                + "\"localPath\":\""
                                + jsonEscape(cached.getAbsolutePath())
                                + "\",\"originalName\":\"dashboard-secret-token.txt\","
                                + "\"kind\":\"file\",\"mimeType\":\"text/plain\","
                                + "\"remoteId\":\"token=ghp_mediasecret123\"}",
                        token);
        assertThat(index.status).isEqualTo(200);

        HttpResult detail =
                request("GET", "/api/jimuqu/media/dashboard-media-secret", null, token);
        assertThat(detail.status).isEqualTo(200);
        assertThat(detail.body).contains("media://MEMORY/dashboard-secret-token.txt");
        assertThat(detail.body).doesNotContain(runtimeHome.getAbsolutePath());
        assertThat(detail.body).doesNotContain("ghp_mediasecret123");

        HttpResult download =
                request(
                        "POST",
                        "/api/jimuqu/media/dashboard-media-secret/download",
                        "{}",
                        token);
        assertThat(download.status).isEqualTo(200);
        assertThat(download.body).contains("media://MEMORY/dashboard-secret-token.txt");
        assertThat(download.body).doesNotContain(runtimeHome.getAbsolutePath());

        HttpResult reference =
                request(
                        "POST",
                        "/api/jimuqu/media/dashboard-media-secret/reference",
                        "{}",
                        token);
        assertThat(reference.status).isEqualTo(200);
        assertThat(reference.body).contains("media://MEMORY/dashboard-secret-token.txt");
        assertThat(reference.body).doesNotContain(runtimeHome.getAbsolutePath());
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

        HttpResult createProvider =
                request(
                        "POST",
                        "/api/providers",
                        "{\"providerKey\":\"placeholder-provider\",\"name\":\"占位 Provider\",\"baseUrl\":\"https://api.example.com\",\"apiKey\":\"  Your-API-Key  \",\"defaultModel\":\"gpt-5-mini\",\"dialect\":\"openai\"}",
                        token);
        assertThat(createProvider.status).isEqualTo(500);
        assertThat(request("GET", "/api/providers", null, token).body)
                .doesNotContain("placeholder-provider");

        HttpResult saveRuntimeConfig =
                request(
                        "PUT",
                        "/api/runtime-config",
                        "{\"key\":\"providers.default.apiKey\",\"value\":\"NONE\"}",
                        token);
        assertThat(saveRuntimeConfig.status).isEqualTo(500);

        HttpResult saveChannelToken =
                request(
                        "PUT",
                        "/api/runtime-config",
                        "{\"key\":\"solonclaw.channels.weixin.token\",\"value\":\"dummy\"}",
                        token);
        assertThat(saveChannelToken.status).isEqualTo(500);
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
