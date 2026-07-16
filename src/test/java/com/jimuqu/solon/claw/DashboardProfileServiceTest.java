package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.DashboardProfileController;
import com.jimuqu.solon.claw.web.DashboardProfileScope;
import com.jimuqu.solon.claw.web.DashboardProfileService;
import com.jimuqu.solon.claw.web.DashboardSkillsService;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.Props;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;
import org.noear.solon.core.handle.UploadedFile;

/** 验证 Dashboard Profile 基础管理契约与核心管理器保持一致。 */
public class DashboardProfileServiceTest {
    /** 验证 Dashboard 对外暴露完整的 Profile 基础 REST 契约。 */
    @Test
    void shouldExposeProfileRestMappings() throws Exception {
        assertMapping("list", "/api/profiles", MethodType.GET, Context.class);
        assertMapping("create", "/api/profiles", MethodType.POST, Context.class);
        assertMapping("active", "/api/profiles/active", MethodType.GET, Context.class);
        assertMapping("use", "/api/profiles/active", MethodType.POST, Context.class);
        assertMapping(
                "importArchive",
                "/api/profiles/import",
                MethodType.POST,
                Context.class,
                UploadedFile[].class);
        assertMapping(
                "installDistribution", "/api/profiles/install", MethodType.POST, Context.class);
        assertMapping("show", "/api/profiles/{name}", MethodType.GET, Context.class, String.class);
        assertMapping(
                "setupCommand",
                "/api/profiles/{name}/setup-command",
                MethodType.GET,
                Context.class,
                String.class);
        assertMapping(
                "openTerminal",
                "/api/profiles/{name}/open-terminal",
                MethodType.POST,
                Context.class,
                String.class);
        assertMapping(
                "updateDescription",
                "/api/profiles/{name}/description",
                MethodType.PUT,
                Context.class,
                String.class);
        assertMapping(
                "describeAutomatically",
                "/api/profiles/{name}/describe-auto",
                MethodType.POST,
                Context.class,
                String.class);
        assertMapping(
                "soul", "/api/profiles/{name}/soul", MethodType.GET, Context.class, String.class);
        assertMapping(
                "updateSoul",
                "/api/profiles/{name}/soul",
                MethodType.PUT,
                Context.class,
                String.class);
        assertMapping(
                "updateModel",
                "/api/profiles/{name}/model",
                MethodType.PUT,
                Context.class,
                String.class);
        assertMapping(
                "createAlias",
                "/api/profiles/{name}/alias",
                MethodType.PUT,
                Context.class,
                String.class);
        assertMapping(
                "removeAlias",
                "/api/profiles/{name}/alias",
                MethodType.DELETE,
                Context.class,
                String.class);
        assertMapping(
                "distribution",
                "/api/profiles/{name}/distribution",
                MethodType.GET,
                Context.class,
                String.class);
        assertMapping(
                "updateDistribution",
                "/api/profiles/{name}/distribution/update",
                MethodType.POST,
                Context.class,
                String.class);
        assertMapping(
                "rename", "/api/profiles/{name}", MethodType.PATCH, Context.class, String.class);
        assertMapping(
                "delete", "/api/profiles/{name}", MethodType.DELETE, Context.class, String.class);
        assertMapping(
                "export",
                "/api/profiles/{name}/export",
                MethodType.GET,
                Context.class,
                String.class);
        assertMapping(
                "gateway",
                "/api/profiles/{name}/gateway",
                MethodType.GET,
                Context.class,
                String.class);
        assertMapping(
                "startGateway",
                "/api/profiles/{name}/gateway/start",
                MethodType.POST,
                Context.class,
                String.class);
        assertMapping(
                "stopGateway",
                "/api/profiles/{name}/gateway/stop",
                MethodType.POST,
                Context.class,
                String.class);
        assertMapping(
                "restartGateway",
                "/api/profiles/{name}/gateway/restart",
                MethodType.POST,
                Context.class,
                String.class);
    }

    /** 验证 Dashboard 可编辑说明、SOUL、模型、setup 命令和别名且所有写入只落到目标 Profile。 */
    @Test
    void shouldManageProfilePresentationAndModelWithoutCrossWriting() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-dashboard-profile-content-");
        Path wrappers = Files.createTempDirectory("solonclaw-dashboard-profile-content-wrappers-");
        try {
            DashboardProfileService service =
                    new DashboardProfileService(new ProfileManager(root, wrappers, "solonclaw"));
            Map<String, Object> create = new LinkedHashMap<String, Object>();
            create.put("name", "writer");
            create.put("no_alias", Boolean.TRUE);
            service.createProfile(create);

            assertThat(service.updateDescription("writer", "Writes release notes."))
                    .containsEntry("description", "Writes release notes.")
                    .containsEntry("description_auto", Boolean.FALSE);
            assertThat(service.updateDescription("writer", ""))
                    .containsEntry("description", "")
                    .containsEntry("description_auto", Boolean.FALSE);

            assertThat(service.readSoul("writer")).containsEntry("exists", Boolean.TRUE);
            assertThat(String.valueOf(service.readSoul("writer").get("content")))
                    .contains("SOUL.md 工作区模板");
            service.updateSoul("writer", "You write concise release notes.\n");
            assertThat(service.readSoul("writer"))
                    .containsEntry("content", "You write concise release notes.\n")
                    .containsEntry("exists", Boolean.TRUE);
            assertThat(root.resolve("SOUL.md")).doesNotExist();

            Files.writeString(
                    root.resolve("config.yml"),
                    "model:\n"
                            + "  providerKey: default\n"
                            + "  default: default-model\n"
                            + "solonclaw:\n"
                            + "  llm:\n"
                            + "    contextWindowTokens: 8192\n");
            Path writerConfig = root.resolve("profiles/writer/config.yml");
            Files.writeString(
                    writerConfig,
                    "model:\n"
                            + "  providerKey: default\n"
                            + "  default: old-model\n"
                            + "providers:\n"
                            + "  default:\n"
                            + "    baseUrl: https://default.example/v1\n"
                            + "  anthropic:\n"
                            + "    baseUrl: https://anthropic.example/v1\n"
                            + "solonclaw:\n"
                            + "  llm:\n"
                            + "    contextWindowTokens: 32768\n");
            service.updateModel("writer", "anthropic", "claude-test");
            String profileConfig = Files.readString(writerConfig);
            assertThat(profileConfig)
                    .contains("providerKey: anthropic")
                    .contains("default: claude-test")
                    .contains("baseUrl: https://default.example/v1")
                    .contains("baseUrl: https://anthropic.example/v1")
                    .contains("contextWindowTokens: 0");
            assertThat(Files.readString(root.resolve("config.yml")))
                    .contains("default: default-model")
                    .contains("contextWindowTokens: 8192");
            assertThat(service.showProfile("writer"))
                    .containsEntry("provider", "anthropic")
                    .containsEntry("model", "claude-test");
            assertThat(service.setupCommand("writer")).containsEntry("command", "writer setup");
            assertThat(service.setupCommand("default")).containsEntry("command", "solonclaw setup");

            assertThat(service.createAlias("writer", "release-writer").get("aliases"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .contains("release-writer");
            assertThat(wrappers.resolve("release-writer")).exists();
            assertThat(service.removeAlias("writer", "release-writer").get("aliases"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .doesNotContain("release-writer");
            assertThat(wrappers.resolve("release-writer")).doesNotExist();
        } finally {
            deleteTree(root);
            deleteTree(wrappers);
        }
    }

    /** Builder 创建后模型、MCP、技能选择和 Hub PID 必须落在新 Profile，且返回精确计数。 */
    @Test
    void shouldApplyProfileBuilderPostProcessingBestEffort() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-profile-builder-");
        Path wrappers = Files.createTempDirectory("solonclaw-profile-builder-wrappers-");
        DashboardMcpService mcp = null;
        SqliteDatabase database = null;
        try {
            ProfileManager manager = new ProfileManager(root, wrappers, "solonclaw");
            AppConfig defaultConfig = profileConfig(root);
            database = new SqliteDatabase(defaultConfig);
            DashboardProfileScope scope =
                    new DashboardProfileScope(manager, "default", defaultConfig);
            mcp = new DashboardMcpService(defaultConfig, database, null, null, scope);
            SqlitePreferenceStore preferences = new SqlitePreferenceStore(database);
            DashboardSkillsService skills =
                    new DashboardSkillsService(
                            new LocalSkillService(defaultConfig, preferences), preferences, scope);
            BuilderProfileService service =
                    new BuilderProfileService(
                            manager, mcp, skills, 4101L, new IOException("spawn failed"));

            writeSkill(root, "keep");
            writeSkill(root, "drop");

            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("name", "builder");
            body.put("no_alias", Boolean.TRUE);
            body.put("clone_from", "default");
            body.put("provider", "default");
            body.put("model", "claude-test");
            body.put(
                    "mcp_servers",
                    java.util.Arrays.asList(
                            mcpServer("stdio-one", null, "echo"),
                            mcpServer("http-one", "https://example.com/mcp", null),
                            mcpServer("empty", null, null)));
            body.put("keep_skills", java.util.Collections.singletonList("keep"));
            body.put("hub_skills", java.util.Arrays.asList("source/one", "", "source/two"));

            Map<String, Object> created = service.createProfile(body);

            assertThat(created)
                    .containsEntry("ok", Boolean.TRUE)
                    .containsEntry("model_set", Boolean.TRUE)
                    .containsEntry("provider", "default")
                    .containsEntry("model", "claude-test")
                    .containsEntry("mcp_written", Integer.valueOf(2))
                    .containsEntry("skills_disabled", Integer.valueOf(1));
            assertThat((List<Map<String, Object>>) created.get("hub_installs"))
                    .containsExactly(
                            hubInstall("source/one", 4101L), hubInstall("source/two", null));
            Path home = root.resolve("profiles/builder");
            assertThat(Files.readString(home.resolve("config.yml")))
                    .contains("providerKey: default")
                    .contains("default: claude-test");
            assertThat(mcpServers(mcp.list("builder")))
                    .extracting("server_id")
                    .containsExactlyInAnyOrder("stdio-one", "http-one");
            DashboardSkillsService verifySkills =
                    new DashboardSkillsService(
                            new LocalSkillService(defaultConfig, preferences), preferences, scope);
            assertThat(skillEnabled(verifySkills.getSkills("builder"), "keep")).isTrue();
            assertThat(skillEnabled(verifySkills.getSkills("builder"), "drop")).isFalse();
            assertThat(service.openTerminal("builder"))
                    .containsEntry("ok", Boolean.TRUE)
                    .containsEntry("command", "builder setup");
            assertThat(service.terminalHome).isEqualTo(home);
            assertThat(service.terminalCommand)
                    .containsSubsequence("--profile", "builder", "setup")
                    .doesNotContain("sh", "-lc", "cmd.exe", "/c");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.openTerminal("builder;touch"))
                    .isInstanceOf(IllegalArgumentException.class);

            Map<String, Object> disableAll = new LinkedHashMap<String, Object>();
            disableAll.put("name", "disable-all");
            disableAll.put("no_alias", Boolean.TRUE);
            disableAll.put("clone_from", "default");
            disableAll.put("keep_skills", java.util.Collections.singletonList(""));
            assertThat(service.createProfile(disableAll))
                    .containsEntry("skills_disabled", Integer.valueOf(2));
            assertThat(skillEnabled(verifySkills.getSkills("disable-all"), "keep")).isFalse();
            assertThat(skillEnabled(verifySkills.getSkills("disable-all"), "drop")).isFalse();
        } finally {
            if (mcp != null) {
                mcp.shutdown();
            }
            if (database != null) {
                database.shutdown();
            }
            deleteTree(root);
            deleteTree(wrappers);
        }
    }

    /** 空保留列表保留默认技能，错误列表在创建目录前被拒绝。 */
    @Test
    void shouldKeepSkillsForEmptySelectionAndRejectMalformedBuilderLists() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-profile-builder-validation-");
        Path wrappers = Files.createTempDirectory("solonclaw-profile-builder-validation-wrappers-");
        try {
            ProfileManager manager = new ProfileManager(root, wrappers, "solonclaw");
            DashboardProfileService service = new DashboardProfileService(manager);
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("name", "empty-selection");
            empty.put("no_alias", Boolean.TRUE);
            empty.put("keep_skills", java.util.Collections.emptyList());
            assertThat(service.createProfile(empty))
                    .containsEntry("skills_disabled", Integer.valueOf(0))
                    .containsEntry("model_set", Boolean.FALSE)
                    .containsEntry("mcp_written", Integer.valueOf(0));

            writeSkill(root, "clone-default-skill");
            Map<String, Object> cloneDefault = new LinkedHashMap<String, Object>();
            cloneDefault.put("name", "clone-default");
            cloneDefault.put("no_alias", Boolean.TRUE);
            cloneDefault.put("clone_from_default", Boolean.TRUE);
            service.createProfile(cloneDefault);
            assertThat(root.resolve("profiles/clone-default/skills/clone-default-skill/SKILL.md"))
                    .exists();

            Map<String, Object> malformed = new LinkedHashMap<String, Object>();
            malformed.put("name", "malformed");
            malformed.put("hub_skills", java.util.Collections.singletonMap("bad", "value"));
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.createProfile(malformed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("hub_skills");
            assertThat(root.resolve("profiles/malformed")).doesNotExist();

            Map<String, Object> malformedMcp = new LinkedHashMap<String, Object>();
            malformedMcp.put("name", "malformed-mcp");
            Map<String, Object> server = mcpServer("bad", null, "echo");
            server.put("args", "not-a-list");
            malformedMcp.put("mcp_servers", java.util.Collections.singletonList(server));
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.createProfile(malformedMcp))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mcp_servers.args");
            assertThat(root.resolve("profiles/malformed-mcp")).doesNotExist();
        } finally {
            deleteTree(root);
            deleteTree(wrappers);
        }
    }

    /** open-terminal 必须拒绝指向 Profile 根外部的符号链接目录。 */
    @Test
    void shouldRejectSymlinkProfileForOpenTerminal() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-profile-terminal-symlink-");
        Path wrappers = Files.createTempDirectory("solonclaw-profile-terminal-symlink-wrappers-");
        Path outside = Files.createTempDirectory("solonclaw-profile-terminal-outside-");
        try {
            Files.createDirectories(root.resolve("profiles"));
            try {
                Files.createSymbolicLink(root.resolve("profiles/linked"), outside);
            } catch (UnsupportedOperationException | IOException unsupported) {
                Assumptions.assumeTrue(false, "Symbolic links are unavailable");
            }
            DashboardProfileService service =
                    new DashboardProfileService(new ProfileManager(root, wrappers, "solonclaw"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.openTerminal("linked"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Profile path");
        } finally {
            deleteTree(root);
            deleteTree(wrappers);
            deleteTree(outside);
        }
    }

    /** open-terminal 的平台候选必须保持上游顺序，且单字符串终端与 Windows fallback 安全引用 argv。 */
    @Test
    void shouldBuildCompleteSafeTerminalCandidateMatrix() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-profile-terminal-matrix-");
        Path wrappers = Files.createTempDirectory("solonclaw-profile-terminal-matrix-wrappers-");
        try {
            TerminalCandidateProfileService service =
                    new TerminalCandidateProfileService(
                            new ProfileManager(root, wrappers, "solonclaw"));
            List<String> application =
                    java.util.Arrays.asList(
                            "/tmp/java path", "value'; touch /tmp/unsafe", "100%done", "bang!");

            List<List<String>> linux = service.candidates(root, application, "linux");
            assertThat(linux)
                    .extracting(command -> command.get(0))
                    .containsExactly(
                            "x-terminal-emulator",
                            "gnome-terminal",
                            "konsole",
                            "xfce4-terminal",
                            "mate-terminal",
                            "lxterminal",
                            "tilix",
                            "alacritty",
                            "kitty",
                            "xterm");
            assertThat(linux.get(3)).hasSize(3);
            assertThat(linux.get(3).get(2)).startsWith("sh -lc '").contains("'\"'\"'");
            assertThat(linux.get(4).get(2)).startsWith("sh -lc '");
            assertThat(linux.get(5).get(2)).startsWith("sh -lc '");
            assertThat(linux.get(6)).containsSubsequence("tilix", "-e", "sh", "-lc");

            List<List<String>> windows = service.candidates(root, application, "windows 11");
            assertThat(windows).hasSize(1);
            assertThat(windows.get(0)).hasSize(5);
            assertThat(windows.get(0).subList(0, 4)).containsExactly("cmd.exe", "/c", "start", "");
            assertThat(windows.get(0))
                    .doesNotContain("/tmp/java path", "value'; touch /tmp/unsafe", "100%done");
            String batch = Files.readString(java.nio.file.Paths.get(windows.get(0).get(4)));
            assertThat(batch)
                    .contains("setlocal DisableDelayedExpansion")
                    .contains("\"value'; touch /tmp/unsafe\"")
                    .contains("\"100%%done\"");

            List<List<String>> mac = service.candidates(root, application, "mac os x");
            assertThat(mac).hasSize(1);
            assertThat(mac.get(0)).startsWith("osascript", "-e");
        } finally {
            deleteTree(root);
            deleteTree(wrappers);
        }
    }

    /** 验证 Dashboard 分发安装、信息读取和更新保留用户配置的完整生命周期。 */
    @Test
    void shouldInstallInspectAndUpdateProfileDistribution() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-dashboard-profile-distribution-");
        Path wrappers =
                Files.createTempDirectory("solonclaw-dashboard-profile-distribution-wrappers-");
        Path distribution = Files.createTempDirectory("solonclaw-dashboard-distribution-source-");
        try {
            Files.write(
                    distribution.resolve("distribution.yaml"),
                    "name: reviewer\nversion: 1.0.0\n"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(
                    distribution.resolve("SOUL.md"),
                    "First version\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            DashboardProfileService service =
                    new DashboardProfileService(new ProfileManager(root, wrappers, "solonclaw"));
            Map<String, Object> install = new LinkedHashMap<String, Object>();
            install.put("source", distribution.toString());
            install.put("alias", Boolean.FALSE);
            assertThat(service.installDistribution(install).get("name")).isEqualTo("reviewer");
            assertThat(service.distributionInfo("reviewer"))
                    .containsEntry("name", "reviewer")
                    .containsEntry("version", "1.0.0");

            Files.write(
                    distribution.resolve("distribution.yaml"),
                    "name: reviewer\nversion: 2.0.0\n"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(
                    distribution.resolve("SOUL.md"),
                    "Second version\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(service.updateDistribution("reviewer", false).get("name"))
                    .isEqualTo("reviewer");
            assertThat(service.distributionInfo("reviewer")).containsEntry("version", "2.0.0");
            assertThat(
                            new String(
                                    Files.readAllBytes(root.resolve("profiles/reviewer/SOUL.md")),
                                    java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("Second version\n");
        } finally {
            deleteTree(root);
            deleteTree(wrappers);
            deleteTree(distribution);
        }
    }

    /** 覆盖 list/show/create/use/rename/delete/import/export/gateway status 全部基础契约。 */
    @Test
    void shouldManageProfilesThroughDashboardContract() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-dashboard-profiles-");
        Path wrappers = Files.createTempDirectory("solonclaw-dashboard-profile-wrappers-");
        Path archive = null;
        try {
            DashboardProfileService service =
                    new DashboardProfileService(new ProfileManager(root, wrappers, "solonclaw"));

            Map<String, Object> create = new LinkedHashMap<String, Object>();
            create.put("name", "worker");
            create.put("description", "独立执行任务");
            create.put("no_alias", Boolean.TRUE);
            Map<String, Object> created = service.createProfile(create);
            assertThat(created.get("name")).isEqualTo("worker");
            assertThat(created.get("description")).isEqualTo("独立执行任务");

            Map<String, Object> listed = service.listProfiles();
            assertThat(profileNames(listed)).containsExactly("default", "worker");
            assertThat(listed.get("active")).isEqualTo("default");
            assertThat(listed.get("current")).isEqualTo("default");

            assertThat(service.showProfile("worker").get("home"))
                    .isEqualTo(root.resolve("profiles/worker").toString());
            assertThat(service.gatewayStatus("worker"))
                    .containsEntry("profile", "worker")
                    .containsEntry("running", Boolean.FALSE);

            assertThat(service.useProfile("worker").get("active")).isEqualTo("worker");
            assertThat(service.useProfile("default").get("active")).isEqualTo("default");

            Map<String, Object> renamed = service.renameProfile("worker", "coder");
            assertThat(renamed.get("name")).isEqualTo("coder");

            archive = service.exportProfile("coder");
            assertThat(archive).exists().isNotEmptyFile();
            Map<String, Object> imported = service.importProfile(archive, "restored");
            assertThat(imported.get("name")).isEqualTo("restored");
            assertThat(profileNames(service.listProfiles()))
                    .containsExactly("default", "coder", "restored");

            assertThat(service.deleteProfile("coder").get("name")).isEqualTo("coder");
            assertThat(service.deleteProfile("restored").get("name")).isEqualTo("restored");
            assertThat(profileNames(service.listProfiles())).containsExactly("default");
        } finally {
            if (archive != null) {
                Files.deleteIfExists(archive);
            }
            deleteTree(root);
            deleteTree(wrappers);
        }
    }

    /** Dashboard 启动命名网关与 CLI 使用同一复用保护。 */
    @Test
    void shouldGuardNamedGatewayUnderDefaultMultiplexer() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-dashboard-multiplex-");
        Path wrappers = Files.createTempDirectory("solonclaw-dashboard-multiplex-wrappers-");
        try {
            ProfileManager manager = new ProfileManager(root, wrappers, "solonclaw");
            DashboardProfileService service = new DashboardProfileService(manager);
            Map<String, Object> create = new LinkedHashMap<String, Object>();
            create.put("name", "worker");
            create.put("no_alias", Boolean.TRUE);
            service.createProfile(create);
            Files.writeString(
                    root.resolve("config.yml"),
                    "solonclaw:\n  gateway:\n    multiplexProfiles: true\n");
            AppConfig gatewayConfig = new AppConfig();
            gatewayConfig.getRuntime().setHome(root.toString());
            gatewayConfig.getDashboard().setBindPort(8080);
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeStatusService runtime =
                    new com.jimuqu.solon.claw.gateway.service.GatewayRuntimeStatusService(
                            gatewayConfig, "default");
            runtime.writePidFile();
            runtime.writeState("running", "test");

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    service.startGateway(
                                            "worker", new LinkedHashMap<String, Object>()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("profile multiplexer");

        } finally {
            deleteTree(root);
            deleteTree(wrappers);
        }
    }

    /** 从 Dashboard 列表响应提取按顺序返回的 Profile 名。 */
    @SuppressWarnings("unchecked")
    private List<String> profileNames(Map<String, Object> response) {
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        for (Map<String, Object> profile : (List<Map<String, Object>>) response.get("profiles")) {
            names.add(String.valueOf(profile.get("name")));
        }
        return names;
    }

    /** 为指定临时工作区加载不影响 JVM 全局解析器的配置。 */
    private AppConfig profileConfig(Path home) {
        Props props = new Props();
        props.put("solonclaw.workspace", home.toString());
        return AppConfig.loadDetached(props);
    }

    /** 构造 Builder MCP 请求项。 */
    private Map<String, Object> mcpServer(String name, String url, String command) {
        Map<String, Object> server = new LinkedHashMap<String, Object>();
        server.put("name", name);
        if (url != null) {
            server.put("url", url);
        }
        if (command != null) {
            server.put("command", command);
        }
        return server;
    }

    /** 构造期望的 Hub 安装启动结果。 */
    private Map<String, Object> hubInstall(String identifier, Long pid) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("identifier", identifier);
        result.put("pid", pid);
        return result;
    }

    /** 从 MCP 列表响应读取服务端项。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mcpServers(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("servers");
    }

    /** 读取测试技能启用状态。 */
    private boolean skillEnabled(List<Map<String, Object>> skills, String name) {
        for (Map<String, Object> skill : skills) {
            if (name.equals(skill.get("name"))) {
                return Boolean.TRUE.equals(skill.get("enabled"));
            }
        }
        throw new AssertionError("Skill not found: " + name);
    }

    /** 写入最小合法技能，供 Builder 的保留列表替换语义测试。 */
    private void writeSkill(Path home, String name) throws Exception {
        Path file = home.resolve("skills").resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file, "---\nname: " + name + "\ndescription: " + name + " skill\n---\n\n# Steps\n");
    }

    /** 暴露终端候选构造边界，测试不得启动真实终端。 */
    private static final class TerminalCandidateProfileService extends DashboardProfileService {
        /** 创建只读取候选矩阵的服务。 */
        private TerminalCandidateProfileService(ProfileManager manager) {
            super(manager);
        }

        /** 返回指定平台候选命令。 */
        private List<List<String>> candidates(Path home, List<String> application, String os)
                throws IOException {
            return terminalCandidates(home, application, os);
        }
    }

    /** Builder Hub 进程测试替身，按顺序返回 PID 或抛出失败。 */
    private static final class BuilderProfileService extends DashboardProfileService {
        /** 首次返回的 PID。 */
        private final Long firstPid;

        /** 后续调用抛出的异常；为空时返回 null PID。 */
        private final Exception laterFailure;

        /** 调用次数。 */
        private int calls;

        /** open-terminal 捕获到的工作目录。 */
        private Path terminalHome;

        /** open-terminal 捕获到的应用参数。 */
        private List<String> terminalCommand;

        /** 创建 Builder 进程边界替身。 */
        private BuilderProfileService(
                ProfileManager manager,
                DashboardMcpService mcpService,
                DashboardSkillsService skillsService,
                Long firstPid,
                Exception laterFailure) {
            super(manager, mcpService, skillsService);
            this.firstPid = firstPid;
            this.laterFailure = laterFailure;
        }

        /** 首项返回真实形状 PID，后续项模拟无法读取 PID 或启动失败。 */
        @Override
        protected Long spawnHubInstall(String profile, String identifier, Path home)
                throws Exception {
            calls++;
            if (calls == 1) {
                return firstPid;
            }
            if (laterFailure != null) {
                throw laterFailure;
            }
            return null;
        }

        /** 捕获安全 argv，不启动真实终端。 */
        @Override
        protected void launchProfileTerminal(Path home, List<String> applicationCommand) {
            terminalHome = home;
            terminalCommand = new java.util.ArrayList<String>(applicationCommand);
        }
    }

    /** 断言控制器方法的路径和 HTTP 方法不会在重构中漂移。 */
    private void assertMapping(
            String methodName, String path, MethodType methodType, Class<?>... parameterTypes)
            throws Exception {
        Method method = DashboardProfileController.class.getMethod(methodName, parameterTypes);
        Mapping mapping = method.getAnnotation(Mapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).isEqualTo(path);
        assertThat(mapping.method()).contains(methodType);
    }

    /** 递归清理测试临时目录。 */
    private void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (Exception ignored) {
                                    path.toFile().deleteOnExit();
                                }
                            });
        }
    }
}
