package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.profile.ProfileCreateOptions;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.storage.repository.SqliteCronJobRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

/** 验证六类任务模型路由的校验、Profile 隔离、持久化和即时响应。 */
class DashboardProviderTaskModelRoutesTest {
    /** 命名 Profile 保存后必须立即返回本次值，并且不得改写根 Profile。 */
    @Test
    void shouldPersistAndReturnNamedProfileRoutesWithoutChangingRoot() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-task-routes-");
        Path wrappers = Files.createTempDirectory("solonclaw-task-route-wrappers-");
        try {
            writeRootConfig(root);
            ProfileManager manager = new ProfileManager(root, wrappers, "solonclaw");
            manager.createProfile(
                    "writer", new ProfileCreateOptions().setNoAlias(true).setClone(true));
            AppConfig rootConfig = loadConfig(root);
            DashboardProviderService service =
                    new DashboardProviderService(
                            rootConfig,
                            null,
                            new LlmProviderService(rootConfig),
                            null,
                            null,
                            new DashboardProfileContext(manager, rootConfig));

            Map<String, Object> request = routeRequest("fast", "flash-model");
            Map<String, Object> response = service.updateTaskModelRoutes(request, "writer");

            assertThat(routeValues(response))
                    .allSatisfy(
                            (category, route) -> {
                                assertThat(route).containsEntry("provider", "fast");
                                assertThat(route).containsEntry("model", "flash-model");
                            });
            assertThat(routeValues(service.listTaskModelRoutes("writer")))
                    .isEqualTo(routeValues(response));
            assertThat(Files.readString(root.resolve("profiles/writer/config.yml")))
                    .contains("modelProvider: fast")
                    .contains("summaryProvider: fast")
                    .contains("defaultProvider: fast")
                    .doesNotContain("providers:");
            assertThat(Files.readString(root.resolve("config.yml")))
                    .doesNotContain("modelProvider: fast")
                    .doesNotContain("summaryProvider: fast")
                    .doesNotContain("defaultProvider: fast");
        } finally {
            FileUtil.del(root.toFile());
            FileUtil.del(wrappers.toFile());
        }
    }

    /** 路由必须同时指定已登记的 Provider 和模型，空路由则表示继承主模型。 */
    @Test
    void shouldValidateProviderAndModelPairs() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-task-route-validation-");
        try {
            writeRootConfig(root);
            AppConfig config = loadConfig(root);
            DashboardProviderService service =
                    new DashboardProviderService(config, null, new LlmProviderService(config));
            Map<String, Object> request = routeRequest("", "");
            Map<String, Object> monitor = new LinkedHashMap<String, Object>();
            monitor.put("provider", "fast");
            monitor.put("model", "missing-model");
            routeValues(request).put("monitor", monitor);

            assertThatThrownBy(() -> service.updateTaskModelRoutes(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("模型未加入 provider fast");

            monitor.put("model", "");
            assertThatThrownBy(() -> service.updateTaskModelRoutes(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("必须同时指定 provider 和 model");
        } finally {
            FileUtil.del(root.toFile());
        }
    }

    /** 六类路由使用不同 Provider/模型时必须准确映射到各业务配置段，不能发生字段串用。 */
    @Test
    void shouldMapDistinctTaskRoutesToTheirRuntimeConfigs() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-distinct-task-routes-");
        try {
            writeDistinctRouteConfig(root);
            AppConfig config = loadConfig(root);
            DashboardProviderService service =
                    new DashboardProviderService(config, null, new LlmProviderService(config));

            service.updateTaskModelRoutes(distinctRouteRequest());

            AppConfig reloaded = loadConfig(root);
            assertThat(reloaded.getProactive().getModelProvider()).isEqualTo("monitor-provider");
            assertThat(reloaded.getProactive().getModel()).isEqualTo("monitor-model");
            assertThat(reloaded.getLearning().getModelProvider()).isEqualTo("background-provider");
            assertThat(reloaded.getLearning().getModel()).isEqualTo("background-model");
            assertThat(reloaded.getCurator().getAiProvider()).isEqualTo("curator-provider");
            assertThat(reloaded.getCurator().getAiModel()).isEqualTo("curator-model");
            assertThat(reloaded.getApprovals().getModelProvider()).isEqualTo("approval-provider");
            assertThat(reloaded.getApprovals().getModel()).isEqualTo("approval-model");
            assertThat(reloaded.getCompression().getSummaryProvider())
                    .isEqualTo("compression-provider");
            assertThat(reloaded.getCompression().getSummaryModel()).isEqualTo("compression-model");
            assertThat(reloaded.getScheduler().getDefaultProvider()).isEqualTo("cron-provider");
            assertThat(reloaded.getScheduler().getDefaultModel()).isEqualTo("cron-model");
        } finally {
            FileUtil.del(root.toFile());
        }
    }

    /** Provider 更新不得删除根配置中仍被后台任务引用的模型。 */
    @Test
    void shouldRejectRemovingModelReferencedByRootTaskRoute() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-provider-root-model-reference-");
        try {
            writeRootConfig(root);
            Files.writeString(
                    root.resolve("config.yml"),
                    Files.readString(root.resolve("config.yml"))
                            + "solonclaw:\n"
                            + "  compression:\n"
                            + "    summaryProvider: fast\n"
                            + "    summaryModel: cheap-model\n");
            AppConfig config = loadConfig(root);
            DashboardProviderService service =
                    new DashboardProviderService(config, null, new LlmProviderService(config));
            String before = Files.readString(root.resolve("config.yml"));

            assertThatThrownBy(
                            () -> service.updateProvider("fast", fastProviderWithoutCheapModel()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("compression")
                    .hasMessageContaining("cheap-model");
            assertThat(Files.readString(root.resolve("config.yml"))).isEqualTo(before);
        } finally {
            FileUtil.del(root.toFile());
        }
    }

    /** Provider 更新不得删除仅由当前运行 Props 路由引用、尚未出现在 YAML 原文中的模型。 */
    @Test
    void shouldRejectRemovingModelReferencedByRuntimeTaskRoute() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-provider-runtime-route-reference-");
        try {
            writeRootConfig(root);
            Props props = new Props();
            props.put("solonclaw.workspace", root.toAbsolutePath().toString());
            props.put("solonclaw.compression.summaryProvider", "fast");
            props.put("solonclaw.compression.summaryModel", "cheap-model");
            AppConfig config = AppConfig.loadDetached(props);
            DashboardProviderService service =
                    new DashboardProviderService(config, null, new LlmProviderService(config));
            String before = Files.readString(root.resolve("config.yml"));

            assertThatThrownBy(
                            () -> service.updateProvider("fast", fastProviderWithoutCheapModel()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("根运行配置 compression")
                    .hasMessageContaining("cheap-model");
            assertThat(Files.readString(root.resolve("config.yml"))).isEqualTo(before);
        } finally {
            FileUtil.del(root.toFile());
        }
    }

    /** Provider 更新不得删除命名 Profile 主模型仍在使用的模型。 */
    @Test
    void shouldRejectRemovingModelReferencedByNamedProfile() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-provider-profile-model-reference-");
        try {
            writeRootConfig(root);
            Path profileHome = root.resolve("profiles/writer");
            Files.createDirectories(profileHome);
            Files.writeString(
                    profileHome.resolve("config.yml"),
                    "model:\n  providerKey: fast\n  default: cheap-model\n");
            AppConfig config = loadConfig(root);
            DashboardProviderService service =
                    new DashboardProviderService(config, null, new LlmProviderService(config));
            String before = Files.readString(root.resolve("config.yml"));

            assertThatThrownBy(
                            () -> service.updateProvider("fast", fastProviderWithoutCheapModel()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Profile writer")
                    .hasMessageContaining("cheap-model");
            assertThat(Files.readString(root.resolve("config.yml"))).isEqualTo(before);
        } finally {
            FileUtil.del(root.toFile());
        }
    }

    /** Provider 更新不得删除现有 Cron 任务固定绑定的模型。 */
    @Test
    void shouldRejectRemovingModelReferencedByCronJob() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-provider-cron-model-reference-");
        SqliteDatabase database = null;
        try {
            writeRootConfig(root);
            AppConfig config = loadConfig(root);
            database = new SqliteDatabase(config);
            CronJobRecord job = new CronJobRecord();
            job.setJobId("cheap-cron");
            job.setName("cheap-cron");
            job.setCronExpr("0 * * * *");
            job.setPrompt("test");
            job.setSourceKey("MEMORY:test:user");
            job.setProvider("fast");
            job.setModel("cheap-model");
            job.setStatus("ACTIVE");
            job.setCreatedAt(1L);
            job.setUpdatedAt(1L);
            new SqliteCronJobRepository(database).save(job);
            database.shutdown();
            database = null;
            DashboardProviderService service =
                    new DashboardProviderService(config, null, new LlmProviderService(config));
            String before = Files.readString(root.resolve("config.yml"));

            assertThatThrownBy(
                            () -> service.updateProvider("fast", fastProviderWithoutCheapModel()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("定时任务 cheap-cron")
                    .hasMessageContaining("cheap-model");
            assertThat(Files.readString(root.resolve("config.yml"))).isEqualTo(before);
        } finally {
            if (database != null) {
                database.shutdown();
            }
            FileUtil.del(root.toFile());
        }
    }

    /** Cron 保存前必须重新读取其他进程已经更新的 Provider 注册表。 */
    @Test
    void shouldRejectCronModelRemovedByExternalProviderUpdate() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-cron-provider-reload-");
        SqliteDatabase database = null;
        try {
            writeRootConfig(root);
            AppConfig config = loadConfig(root);
            database = new SqliteDatabase(config);
            CronJobService service =
                    new CronJobService(config, new SqliteCronJobRepository(database));
            Files.writeString(
                    root.resolve("config.yml"),
                    Files.readString(root.resolve("config.yml"))
                            .replace(
                                    "models: [flash-model, cheap-model]", "models: [flash-model]"));

            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("name", "removed-model-cron");
            body.put("schedule", "30m");
            body.put("prompt", "不得保存外部配置已经移除的模型");
            body.put("provider", "fast");
            body.put("model", "cheap-model");

            assertThatThrownBy(() -> service.create("MEMORY:test:user", body))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("模型未加入 Provider fast")
                    .hasMessageContaining("cheap-model");
        } finally {
            if (database != null) {
                database.shutdown();
            }
            FileUtil.del(root.toFile());
        }
    }

    /** 删除 Provider 时必须阻止根 Profile 的 Cron 固定绑定变成悬空引用。 */
    @Test
    void shouldRejectDeletingProviderReferencedByRootCronJob() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-provider-root-cron-reference-");
        try {
            writeRootConfig(root);
            saveCronJob(root, "root-fast-cron", "fast", "cheap-model");
            AppConfig config = loadConfig(root);
            DashboardProviderService service =
                    new DashboardProviderService(config, null, new LlmProviderService(config));
            String before = Files.readString(root.resolve("config.yml"));

            assertThatThrownBy(() -> service.deleteProvider("fast"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("根 Profile 定时任务")
                    .hasMessageContaining("root-fast-cron");
            assertThat(Files.readString(root.resolve("config.yml"))).isEqualTo(before);
        } finally {
            FileUtil.del(root.toFile());
        }
    }

    /** 删除全局 Provider 时必须检查命名 Profile 的 Cron 固定绑定。 */
    @Test
    void shouldRejectDeletingProviderReferencedByNamedProfileCronJob() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-provider-profile-cron-reference-");
        try {
            writeRootConfig(root);
            Path profileHome = root.resolve("profiles/writer");
            Files.createDirectories(profileHome);
            Files.writeString(
                    profileHome.resolve("config.yml"),
                    "model:\n  providerKey: default\n  default: main-model\n");
            saveCronJob(profileHome, "writer-fast-cron", "fast", "cheap-model");
            AppConfig config = loadConfig(root);
            DashboardProviderService service =
                    new DashboardProviderService(config, null, new LlmProviderService(config));
            String before = Files.readString(root.resolve("config.yml"));

            assertThatThrownBy(() -> service.deleteProvider("fast"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Profile writer 定时任务")
                    .hasMessageContaining("writer-fast-cron");
            assertThat(Files.readString(root.resolve("config.yml"))).isEqualTo(before);
        } finally {
            FileUtil.del(root.toFile());
        }
    }

    /** 运行参数覆盖主模型时仍必须检查 YAML 原文，避免删除被掩盖的 Provider 引用。 */
    @Test
    void shouldRejectDeletingProviderReferencedByMaskedRootYaml() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-provider-masked-root-reference-");
        try {
            writeRootConfig(root);
            String raw = Files.readString(root.resolve("config.yml"));
            Files.writeString(
                    root.resolve("config.yml"),
                    raw.replace(
                            "providerKey: default\n  default: main-model",
                            "providerKey: fast\n  default: cheap-model"));
            Props props = new Props();
            props.put("solonclaw.workspace", root.toAbsolutePath().toString());
            props.put("model.providerKey", "default");
            props.put("model.default", "main-model");
            AppConfig config = AppConfig.loadDetached(props);
            DashboardProviderService service =
                    new DashboardProviderService(config, null, new LlmProviderService(config));
            String before = Files.readString(root.resolve("config.yml"));

            assertThatThrownBy(() -> service.deleteProvider("fast"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("根配置")
                    .hasMessageContaining("fast");
            assertThat(Files.readString(root.resolve("config.yml"))).isEqualTo(before);
        } finally {
            FileUtil.del(root.toFile());
        }
    }

    /** Provider 引用检查必须使用只读连接，不能借机初始化或迁移 Profile 数据库。 */
    @Test
    void shouldInspectCronReferencesWithoutMutatingStateDatabase() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-provider-readonly-cron-check-");
        try {
            writeRootConfig(root);
            Path stateDb = root.resolve("data/state.db");
            Files.createDirectories(stateDb.getParent());
            try (Connection connection =
                            DriverManager.getConnection("jdbc:sqlite:" + stateDb.toAbsolutePath());
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        "create table cron_jobs (job_id text primary key, provider text, model text)");
                statement.execute(
                        "insert into cron_jobs (job_id, provider, model) values ('other-cron', 'other', 'other-model')");
            }
            byte[] before = Files.readAllBytes(stateDb);
            AppConfig config = loadConfig(root);
            DashboardProviderService service =
                    new DashboardProviderService(config, null, new LlmProviderService(config));

            service.updateProvider("fast", fastProviderWithoutCheapModel());

            assertThat(Arrays.equals(Files.readAllBytes(stateDb), before)).isTrue();
        } finally {
            FileUtil.del(root.toFile());
        }
    }

    /** 写入包含两个模型的根 Provider 注册表。 */
    private void writeRootConfig(Path root) throws Exception {
        Files.writeString(
                root.resolve("config.yml"),
                "providers:\n"
                        + "  default:\n"
                        + "    baseUrl: https://default.example/v1\n"
                        + "    defaultModel: main-model\n"
                        + "    models: [main-model]\n"
                        + "    dialect: openai\n"
                        + "  fast:\n"
                        + "    baseUrl: https://fast.example/v1\n"
                        + "    defaultModel: flash-model\n"
                        + "    models: [flash-model, cheap-model]\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: default\n"
                        + "  default: main-model\n");
    }

    /** 创建删除 cheap-model 后的完整 Provider 更新请求。 */
    private Map<String, Object> fastProviderWithoutCheapModel() {
        Map<String, Object> provider = new LinkedHashMap<String, Object>();
        provider.put("name", "Fast Provider");
        provider.put("baseUrl", "https://8.8.8.8/v1");
        provider.put("defaultModel", "flash-model");
        provider.put("models", Collections.singletonList("flash-model"));
        provider.put("dialect", "openai");
        return provider;
    }

    /** 写入六组相互独立的 Provider/模型，确保测试能发现任务类别映射串位。 */
    private void writeDistinctRouteConfig(Path root) throws Exception {
        StringBuilder yaml = new StringBuilder("providers:\n");
        String[][] routes =
                new String[][] {
                    {"monitor", "monitor-provider", "monitor-model"},
                    {"background_review", "background-provider", "background-model"},
                    {"curator", "curator-provider", "curator-model"},
                    {"approval", "approval-provider", "approval-model"},
                    {"compression", "compression-provider", "compression-model"},
                    {"cron", "cron-provider", "cron-model"}
                };
        for (String[] route : routes) {
            yaml.append("  ").append(route[1]).append(":\n");
            yaml.append("    baseUrl: https://").append(route[1]).append(".example/v1\n");
            yaml.append("    defaultModel: ").append(route[2]).append("\n");
            yaml.append("    models: [").append(route[2]).append("]\n");
            yaml.append("    dialect: openai\n");
        }
        yaml.append("model:\n")
                .append("  providerKey: monitor-provider\n")
                .append("  default: monitor-model\n");
        Files.writeString(root.resolve("config.yml"), yaml.toString());
    }

    /** 从指定工作区加载不注册全局运行时解析器的配置快照。 */
    private AppConfig loadConfig(Path home) {
        Props props = new Props();
        props.put("solonclaw.workspace", home.toAbsolutePath().toString());
        return AppConfig.loadDetached(props);
    }

    /** 在指定 Profile 的 SQLite 数据库中保存一个固定模型 Cron 任务。 */
    private void saveCronJob(Path home, String jobId, String provider, String model)
            throws Exception {
        SqliteDatabase database = new SqliteDatabase(loadConfig(home));
        try {
            CronJobRecord job = new CronJobRecord();
            job.setJobId(jobId);
            job.setName(jobId);
            job.setCronExpr("0 * * * *");
            job.setPrompt("test");
            job.setSourceKey("MEMORY:test:user");
            job.setProvider(provider);
            job.setModel(model);
            job.setStatus("ACTIVE");
            job.setCreatedAt(1L);
            job.setUpdatedAt(1L);
            new SqliteCronJobRepository(database).save(job);
        } finally {
            database.shutdown();
        }
    }

    /** 创建六类路由使用同一 Provider/模型的请求体。 */
    private Map<String, Object> routeRequest(String provider, String model) {
        Map<String, Object> routes = new LinkedHashMap<String, Object>();
        for (String category :
                new String[] {
                    "monitor", "background_review", "curator", "approval", "compression", "cron"
                }) {
            Map<String, Object> route = new LinkedHashMap<String, Object>();
            route.put("provider", provider);
            route.put("model", model);
            routes.put(category, route);
        }
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("routes", routes);
        return request;
    }

    /** 创建六类任务分别指向不同 Provider 和模型的请求体。 */
    private Map<String, Object> distinctRouteRequest() {
        Map<String, Object> routes = new LinkedHashMap<String, Object>();
        String[][] values =
                new String[][] {
                    {"monitor", "monitor-provider", "monitor-model"},
                    {"background_review", "background-provider", "background-model"},
                    {"curator", "curator-provider", "curator-model"},
                    {"approval", "approval-provider", "approval-model"},
                    {"compression", "compression-provider", "compression-model"},
                    {"cron", "cron-provider", "cron-model"}
                };
        for (String[] value : values) {
            Map<String, Object> route = new LinkedHashMap<String, Object>();
            route.put("provider", value[1]);
            route.put("model", value[2]);
            routes.put(value[0], route);
        }
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("routes", routes);
        return request;
    }

    /** 从请求或响应中读取六类路由映射。 */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> routeValues(Map<String, Object> value) {
        return (Map<String, Map<String, Object>>) (Map<?, ?>) value.get("routes");
    }
}
