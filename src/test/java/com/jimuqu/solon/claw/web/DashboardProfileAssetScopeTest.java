package com.jimuqu.solon.claw.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.storage.repository.SqliteCronJobRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.Props;

/** 验证 Dashboard 技能、上下文、MCP 与 Cron 按 Profile 独立读写。 */
class DashboardProfileAssetScopeTest {
    /** 每个测试独占的 Profile 根目录。 */
    @TempDir Path tempDir;

    /** 默认 Profile 根目录。 */
    private Path root;

    /** 命名 Profile 根目录。 */
    private Path worker;

    /** Profile 请求解析器。 */
    private DashboardProfileScope profileScope;

    /** 默认 Profile 配置。 */
    private AppConfig defaultConfig;

    /** 默认 Profile 数据库。 */
    private SqliteDatabase defaultDatabase;

    /** 测试后需要关闭的 MCP 根服务。 */
    private DashboardMcpService mcpService;

    /** 测试后需要关闭的 Cron 根服务。 */
    private DashboardCronService cronService;

    /** 创建默认与命名 Profile，并准备彼此不同的配置和技能。 */
    @BeforeEach
    void setUp() throws Exception {
        root = tempDir.resolve("workspace");
        worker = root.resolve("profiles/worker");
        Files.createDirectories(worker);
        write(root.resolve("config.yml"), "mcp:\n  enabled: true\n");
        write(worker.resolve("config.yml"), "mcp:\n  enabled: true\n");
        writeSkill(root, "default-skill", "default skill");
        writeSkill(worker, "worker-skill", "worker skill");

        ProfileManager manager = new ProfileManager(root, tempDir.resolve("bin"), "solonclaw");
        defaultConfig = config(root);
        profileScope = new DashboardProfileScope(manager, "default", defaultConfig);
        defaultDatabase = new SqliteDatabase(defaultConfig);
    }

    /** 释放测试创建的数据库和独立运行时。 */
    @AfterEach
    void tearDown() {
        if (mcpService != null) {
            mcpService.shutdown();
        }
        if (cronService != null) {
            cronService.shutdown();
        }
        if (defaultDatabase != null) {
            defaultDatabase.shutdown();
        }
    }

    /** 独立配置加载不得修改当前全局 RuntimeConfigResolver。 */
    @Test
    void detachedConfigDoesNotRetargetGlobalResolver() {
        com.jimuqu.solon.claw.config.RuntimeConfigResolver current =
                com.jimuqu.solon.claw.config.RuntimeConfigResolver.initialize(root.toString());

        AppConfig workerConfig = profileScope.loadConfig(profileScope.resolve("worker"));

        assertThat(workerConfig.getWorkspace().getDir()).isEqualTo(worker.toString());
        assertThat(com.jimuqu.solon.claw.config.RuntimeConfigResolver.getInstance())
                .isSameAs(current);
        assertThat(current.configFile().toPath()).isEqualTo(root.resolve("config.yml"));
    }

    /** Skills 列表、详情与 toggle 必须命中目标 Profile 的目录和数据库。 */
    @Test
    void skillsAreIsolatedByProfile() throws Exception {
        SqlitePreferenceStore defaultPreferences = new SqlitePreferenceStore(defaultDatabase);
        DashboardSkillsService service =
                new DashboardSkillsService(
                        new LocalSkillService(defaultConfig, defaultPreferences),
                        defaultPreferences,
                        profileScope);

        assertThat(names(service.getSkills(null)))
                .contains("default-skill")
                .doesNotContain("worker-skill");
        assertThat(names(service.getSkills("worker")))
                .contains("worker-skill")
                .doesNotContain("default-skill");
        assertThat(service.viewSkill("worker", "worker-skill", null).get("content"))
                .asString()
                .contains("worker skill");

        service.toggleSkill("worker", "worker-skill", false);

        assertThat(enabled(service.getSkills("worker"), "worker-skill")).isFalse();
        assertThat(enabled(service.getSkills(null), "default-skill")).isTrue();
    }

    /** MEMORY、USER、SOUL 文件写入必须留在目标 Profile 根目录。 */
    @Test
    void personaFilesAreIsolatedByProfile() throws Exception {
        DashboardWorkspaceService service =
                new DashboardWorkspaceService(
                        new PersonaWorkspaceService(defaultConfig), profileScope);

        service.saveFile("worker", "memory", "worker memory");
        service.saveFile("worker", "user", "worker user");
        service.saveFile("worker", "soul", "worker soul");

        assertThat(service.getFile("worker", "memory").get("content")).isEqualTo("worker memory");
        assertThat(service.getFile("worker", "user").get("content")).isEqualTo("worker user");
        assertThat(service.getFile("worker", "soul").get("content")).isEqualTo("worker soul");
        assertThat(worker.resolve("MEMORY.md")).hasContent("worker memory");
        assertThat(worker.resolve("USER.md")).hasContent("worker user");
        assertThat(worker.resolve("SOUL.md")).hasContent("worker soul");
        assertThat(Files.readString(root.resolve("MEMORY.md"))).doesNotContain("worker memory");
    }

    /** MCP 配置和 OAuth token 必须写入目标 Profile 的 state.db。 */
    @Test
    void mcpServersAndOAuthAreIsolatedByProfile() throws Exception {
        mcpService =
                new DashboardMcpService(defaultConfig, defaultDatabase, null, null, profileScope);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "worker-server");
        body.put("name", "worker-server");
        body.put("transport", "stdio");
        body.put("command", "echo");
        Map<String, Object> oauth = new LinkedHashMap<String, Object>();
        oauth.put("enabled", Boolean.TRUE);
        oauth.put("access_token", "worker-token");
        body.put("oauth", oauth);

        mcpService.save("worker", body);

        assertThat(servers(mcpService.list("worker")))
                .extracting("server_id")
                .containsExactly("worker-server");
        assertThat(servers(mcpService.list(null))).isEmpty();
        assertThat(mcpService.oauthStatus("worker", "worker-server"))
                .containsEntry("has_access_token", Boolean.TRUE)
                .doesNotContainValue("worker-token");
    }

    /** Cron jobs 与 runs 必须命中目标 Profile 的独立数据库。 */
    @Test
    void cronJobsAreIsolatedByProfile() throws Exception {
        CronJobService defaultJobs =
                new CronJobService(defaultConfig, new SqliteCronJobRepository(defaultDatabase));
        cronService = new DashboardCronService(defaultJobs, null, profileScope);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "worker-cron");
        body.put("schedule", "every 1h");
        body.put("prompt", "worker only");

        Map<String, Object> created = cronService.create("worker", body);

        assertThat(cronService.listJobs("worker"))
                .extracting("name")
                .containsExactly("worker-cron");
        assertThat(cronService.listJobs(null)).isEmpty();
        assertThat(cronService.history("worker", String.valueOf(created.get("id")), 20)).isEmpty();

        Map<String, Object> triggered =
                cronService.trigger(
                        "worker",
                        String.valueOf(created.get("id")),
                        java.util.Collections.<String, Object>emptyMap());
        assertThat(triggered.get("pending_trigger")).isEqualTo("manual");
    }

    /** 未知 Profile 必须明确失败，不能静默回落到当前 Profile。 */
    @Test
    void unknownProfileFailsExplicitly() {
        assertThatThrownBy(() -> profileScope.resolve("missing"))
                .isInstanceOf(DashboardProfileScope.ProfileNotFoundException.class)
                .hasMessageContaining("missing");
    }

    /** current 别名必须稳定解析到当前进程 Profile，不能被当作真实 Profile 名。 */
    @Test
    void currentAliasResolvesCurrentProfile() {
        DashboardProfileScope.Resolved resolved = profileScope.resolve("current");

        assertThat(resolved.getName()).isEqualTo("default");
        assertThat(resolved.getHome()).isEqualTo(root);
        assertThat(resolved.isCurrent()).isTrue();
    }

    /** 使用 detached loader 创建指定工作区配置。 */
    private AppConfig config(Path home) {
        Props props = new Props();
        props.put("solonclaw.workspace", home.toString());
        return AppConfig.loadDetached(props);
    }

    /** 写入一个最小合法技能。 */
    private void writeSkill(Path home, String name, String description) throws Exception {
        write(
                home.resolve("skills").resolve(name).resolve("SKILL.md"),
                "---\nname: "
                        + name
                        + "\ndescription: "
                        + description
                        + "\n---\n\n# Steps\n- run\n");
    }

    /** 写入 UTF-8 测试文件并补齐父目录。 */
    private void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 从技能列表提取规范名称。 */
    private List<String> names(List<Map<String, Object>> items) {
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        for (Map<String, Object> item : items) {
            names.add(String.valueOf(item.get("name")));
        }
        return names;
    }

    /** 读取指定技能启用状态。 */
    private boolean enabled(List<Map<String, Object>> items, String name) {
        for (Map<String, Object> item : items) {
            if (name.equals(item.get("name"))) {
                return Boolean.TRUE.equals(item.get("enabled"));
            }
        }
        throw new AssertionError("Skill not found: " + name);
    }

    /** 读取 MCP 服务列表。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> servers(Map<String, Object> listing) {
        return (List<Map<String, Object>>) listing.get("servers");
    }
}
