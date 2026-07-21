package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.web.DashboardSessionController;
import com.jimuqu.solon.claw.web.DashboardSessionService;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import com.jimuqu.solon.claw.web.profile.DashboardProfileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;

/** 验证机器 Dashboard 的 Profile 会话只读范围和聚合分页。 */
public class DashboardProfileSessionScopeTest {
    /** Profile 快照解析器必须拒绝未登记模型，不能绕过统一 Provider 校验。 */
    @Test
    void snapshotProviderServiceRejectsUnregisteredModel() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("registered-model");
        provider.setModels(java.util.Collections.singletonList("registered-model"));
        config.getProviders().put("configured", provider);
        config.getModel().setProviderKey("configured");
        config.getModel().setDefault("registered-model");

        assertThatThrownBy(
                        () ->
                                DashboardProfileContext.snapshotProviderService(config)
                                        .resolveProvider("configured", "missing-model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型未在 Provider configured 中登记")
                .hasMessageContaining("missing-model");
    }

    @Test
    void shouldReadAndAggregateSessionsWithoutWritingTargetProfile() throws Exception {
        Path root = Files.createTempDirectory("dashboard-profile-sessions-");
        Path workerHome = root.resolve("profiles").resolve("worker");
        Files.createDirectories(workerHome.resolve("data"));
        Path wrappers = Files.createTempDirectory("dashboard-profile-wrappers-");
        AppConfig currentConfig = config(root);
        AppConfig workerConfig = config(workerHome);
        SqliteDatabase currentDatabase = new SqliteDatabase(currentConfig);
        SqliteDatabase workerDatabase = new SqliteDatabase(workerConfig);
        try {
            SessionRepository currentRepository = new SqliteSessionRepository(currentDatabase);
            SessionRepository workerRepository = new SqliteSessionRepository(workerDatabase);
            save(currentRepository, "MEMORY:dashboard:default", "default session", 100L);
            SessionRecord workerRoot =
                    save(workerRepository, "MEMORY:dashboard:worker", "worker session", 200L);
            SessionRecord workerChild =
                    save(workerRepository, "MEMORY:dashboard:worker-child", "worker child", 300L);
            workerChild.setParentSessionId(workerRoot.getSessionId());
            workerChild.setCumulativeInputTokens(41L);
            workerChild.setCumulativeOutputTokens(17L);
            workerChild.setLastResolvedProvider("worker-provider");
            workerChild.setLastResolvedModel("worker-model");
            workerRepository.save(workerChild);

            ProfileManager manager = new ProfileManager(root, wrappers, "solonclaw");
            DashboardProfileContext context = new DashboardProfileContext(manager, currentConfig);
            DashboardProfileContext.Scope current = context.resolve("default");
            DashboardProfileContext.Scope worker = context.resolve("worker");
            DashboardSessionService service = new DashboardSessionService(currentRepository);

            Map<String, Object> workerPage = service.getSessions(20, 0, worker);
            assertThat(workerPage.get("total")).isEqualTo(1);
            assertThat(sessions(workerPage).get(0))
                    .containsEntry("title", "worker session")
                    .containsEntry("profile", "worker")
                    .containsEntry("is_default_profile", false);

            Map<String, Object> detail =
                    service.getSessionMessages(workerChild.getSessionId(), worker);
            assertThat(detail)
                    .containsEntry("profile", "worker")
                    .containsEntry("model", "worker-model")
                    .containsEntry("provider", "worker-provider")
                    .containsEntry("input_tokens", 41L)
                    .containsEntry("output_tokens", 17L);
            assertThat(messages(detail))
                    .extracting(row -> row.get("content"))
                    .containsExactly("worker child");

            Map<String, Object> latest =
                    service.latestDescendant(workerRoot.getSessionId(), worker);
            assertThat(latest)
                    .containsEntry("profile", "worker")
                    .containsEntry("session_id", workerChild.getSessionId())
                    .containsEntry("changed", true);

            Map<String, Object> tree = service.sessionTree(workerRoot.getSessionId(), worker);
            assertThat(tree).containsEntry("profile", "worker");
            assertThat(nodes(tree)).extracting(row -> row.get("profile")).containsOnly("worker");

            Map<String, Object> merged =
                    service.getProfilesSessions(20, 0, Arrays.asList(current, worker));
            assertThat(merged.get("total")).isEqualTo(2);
            Map<String, Integer> expectedTotals = new LinkedHashMap<String, Integer>();
            expectedTotals.put("default", Integer.valueOf(1));
            expectedTotals.put("worker", Integer.valueOf(1));
            assertThat(merged.get("profile_totals")).isEqualTo(expectedTotals);
            assertThat(sessions(merged))
                    .extracting(row -> row.get("title"))
                    .containsExactly("worker session", "default session");
            assertThat(sessions(merged))
                    .extracting(row -> row.get("profile"))
                    .containsExactly("worker", "default");
            assertThat(workerRepository.findById(sessions(workerPage).get(0).get("id").toString()))
                    .isNotNull();
            assertThat(currentRepository.findById(sessions(workerPage).get(0).get("id").toString()))
                    .isNull();

            Map<String, Object> update = new LinkedHashMap<String, Object>();
            update.put("title", "worker renamed");
            update.put("archived", Boolean.TRUE);
            assertThat(service.updateSession(workerRoot.getSessionId(), update, worker))
                    .containsEntry("ok", Boolean.TRUE)
                    .containsEntry("title", "worker renamed")
                    .containsEntry("archived", Boolean.TRUE);
            assertThat(workerRepository.findById(workerRoot.getSessionId()))
                    .extracting(SessionRecord::getTitle)
                    .isEqualTo("worker renamed");
            assertThat(workerRepository.findById(workerRoot.getSessionId()).getMetadataJson())
                    .contains("\"archived\":true");
            assertThat(service.getSessions(20, 0, worker)).containsEntry("total", 0);

            assertThat(service.deleteSession(workerChild.getSessionId(), worker))
                    .containsEntry("ok", Boolean.TRUE);
            assertThat(workerRepository.findById(workerChild.getSessionId())).isNull();
        } finally {
            currentDatabase.shutdown();
            workerDatabase.shutdown();
        }
    }

    @Test
    void shouldFailUnknownProfileAndReturnEmptyForUninitializedProfile() throws Exception {
        Path root = Files.createTempDirectory("dashboard-profile-empty-");
        Path wrappers = Files.createTempDirectory("dashboard-profile-empty-wrappers-");
        Files.createDirectories(root.resolve("profiles").resolve("empty"));
        AppConfig currentConfig = config(root);
        SqliteDatabase currentDatabase = new SqliteDatabase(currentConfig);
        try {
            ProfileManager manager = new ProfileManager(root, wrappers, "solonclaw");
            DashboardProfileContext context = new DashboardProfileContext(manager, currentConfig);
            DashboardSessionService service =
                    new DashboardSessionService(new SqliteSessionRepository(currentDatabase));

            assertThat(service.getSessions(20, 0, context.resolve("empty")))
                    .containsEntry("total", 0);
            assertThatThrownBy(
                            () -> service.getSessionMessages("missing", context.resolve("empty")))
                    .isInstanceOf(DashboardSessionService.SessionNotFoundException.class);
            assertThatThrownBy(() -> service.latestDescendant("missing", context.resolve("empty")))
                    .isInstanceOf(DashboardSessionService.SessionNotFoundException.class);
            assertThatThrownBy(() -> context.resolve("missing"))
                    .isInstanceOf(DashboardProfileNotFoundException.class)
                    .hasMessageContaining("missing");
        } finally {
            currentDatabase.shutdown();
        }
    }

    @Test
    void bodyProfileShouldWinOverQueryProfile() throws Exception {
        Path root = Files.createTempDirectory("dashboard-profile-priority-");
        Files.createDirectories(root.resolve("profiles").resolve("query"));
        Files.createDirectories(root.resolve("profiles").resolve("body"));
        ProfileManager manager =
                new ProfileManager(
                        root,
                        Files.createTempDirectory("dashboard-profile-priority-wrappers-"),
                        "solonclaw");
        DashboardProfileContext context = new DashboardProfileContext(manager, config(root));

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("profile", "body");
        String requested = DashboardProfileContext.requestedProfile(null, body);

        assertThat(requested).isEqualTo("body");
        assertThat(context.resolve(requested).getName()).isEqualTo("body");
    }

    @Test
    void controllerShouldReadTargetProfileWithoutRunningGatewayAndRejectUnknownProfile()
            throws Exception {
        Path root = Files.createTempDirectory("dashboard-profile-controller-");
        Path workerHome = root.resolve("profiles").resolve("worker");
        Files.createDirectories(workerHome.resolve("data"));
        AppConfig currentConfig = config(root);
        SqliteDatabase currentDatabase = new SqliteDatabase(currentConfig);
        SqliteDatabase workerDatabase = new SqliteDatabase(config(workerHome));
        try {
            SessionRepository currentRepository = new SqliteSessionRepository(currentDatabase);
            SessionRepository workerRepository = new SqliteSessionRepository(workerDatabase);
            save(currentRepository, "MEMORY:controller:default", "default root", 50L);
            SessionRecord workerRoot =
                    save(workerRepository, "MEMORY:controller:worker", "worker root", 100L);
            SessionRecord workerChild =
                    save(workerRepository, "MEMORY:controller:child", "worker child", 200L);
            workerChild.setParentSessionId(workerRoot.getSessionId());
            workerRepository.save(workerChild);

            ProfileManager manager =
                    new ProfileManager(
                            root,
                            Files.createTempDirectory("dashboard-profile-controller-wrappers-"),
                            "solonclaw");
            DashboardProfileContext profileContext =
                    new DashboardProfileContext(manager, currentConfig);
            DashboardSessionController controller =
                    new DashboardSessionController(
                            new DashboardSessionService(currentRepository),
                            profileContext,
                            manager);

            Context workerRequest = request("worker");
            Map<String, Object> detail =
                    data(controller.messages(workerChild.getSessionId(), workerRequest));
            assertThat(detail)
                    .containsEntry("profile", "worker")
                    .containsEntry("title", "worker child");
            assertThat(messages(detail))
                    .extracting(row -> row.get("content"))
                    .containsExactly("worker child");
            assertThat(data(controller.latestDescendant(workerRoot.getSessionId(), workerRequest)))
                    .containsEntry("profile", "worker")
                    .containsEntry("session_id", workerChild.getSessionId());
            assertThat(data(controller.detail(workerChild.getSessionId(), workerRequest)))
                    .containsEntry("profile", "worker")
                    .containsEntry("title", "worker child");

            Context defaultRequest = ContextEmpty.create();
            assertThat(sessions(data(controller.sessions(defaultRequest))).get(0))
                    .containsEntry("title", "default root")
                    .doesNotContainKeys("profile", "is_default_profile");

            Context invalidArchived = ContextEmpty.create();
            invalidArchived.paramMap().put("archived", "invalid");
            Map<String, Object> invalid = controller.sessions(invalidArchived);
            assertThat(invalidArchived.status()).isEqualTo(400);
            assertThat(invalid)
                    .containsEntry("success", false)
                    .containsEntry("code", "SESSION_BAD_REQUEST");

            Context missingSession = request("worker");
            Map<String, Object> missing = controller.messages("missing", missingSession);
            assertThat(missingSession.status()).isEqualTo(404);
            assertThat(missing)
                    .containsEntry("success", false)
                    .containsEntry("code", "SESSION_NOT_FOUND");

            Context unknownRequest = request("missing");
            Map<String, Object> unknown = controller.sessions(unknownRequest);
            assertThat(unknownRequest.status()).isEqualTo(404);
            assertThat(unknown)
                    .containsEntry("success", false)
                    .containsEntry("code", "PROFILE_NOT_FOUND");
        } finally {
            currentDatabase.shutdown();
            workerDatabase.shutdown();
        }
    }

    /** 创建绑定到单个临时 Profile 的最小运行配置。 */
    private AppConfig config(Path home) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getRuntime().setStateDb(home.resolve("data").resolve("state.db").toString());
        config.getRuntime().setConfigFile(home.resolve("config.yml").toString());
        config.getRuntime().setLogsDir(home.resolve("logs").toString());
        config.getWorkspace().setDir(home.toString());
        return config;
    }

    /** 保存带稳定标题、正文和更新时间的测试会话。 */
    private SessionRecord save(
            SessionRepository repository, String source, String title, long updatedAt)
            throws Exception {
        SessionRecord session = repository.bindNewSession(source);
        session.setTitle(title);
        session.setCreatedAt(updatedAt);
        session.setUpdatedAt(updatedAt);
        session.setNdjson(MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser(title))));
        repository.save(session);
        return session;
    }

    /** 读取 Dashboard 会话列表并保持测试中的泛型边界清晰。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sessions(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("sessions");
    }

    /** 读取 Dashboard 会话详情中的消息列表。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> messages(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("messages");
    }

    /** 读取 Dashboard 会话树中的节点列表。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodes(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("nodes");
    }

    /** 创建携带 Profile 查询参数的最小 Solon 请求上下文。 */
    private Context request(String profile) {
        Context context = ContextEmpty.create();
        context.paramMap().put("profile", profile);
        return context;
    }

    /** 解包 Dashboard 成功响应中的 data 对象。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<String, Object> response) {
        assertThat(response).containsEntry("success", true);
        return (Map<String, Object>) response.get("data");
    }
}
