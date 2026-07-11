package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.profile.ProfileCreateOptions;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.SkillBrowseResult;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.support.SkillHubStateStore;
import com.jimuqu.solon.claw.web.DashboardProfileScope;
import com.jimuqu.solon.claw.web.DashboardSkillsController;
import com.jimuqu.solon.claw.web.DashboardSkillsService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.Props;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.handle.MethodType;

/** 验证 Profile Builder 使用的 Skills Hub 搜索参数与响应结构。 */
class DashboardSkillsHubSearchTest {
    /** 空查询不得访问网络，并返回稳定的四字段空响应。 */
    @Test
    void shouldSkipHubForBlankQuery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DashboardSkillsService service = service(calls, null, null, null);

        assertThat(service.searchHub("  ", null, 20))
                .containsEntry("results", Collections.emptyList())
                .containsEntry("source_counts", Collections.emptyMap())
                .containsEntry("timed_out", Collections.emptyList())
                .containsEntry("installed", Collections.emptyMap());
        assertThat(calls.get()).isZero();
    }

    /** 搜索会规范参数、按 identifier 信任级别去重，并返回来源与安装状态。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldNormalizeAndMapHubSearch() throws Exception {
        SkillBrowseResult browse = new SkillBrowseResult();
        browse.setItems(
                Arrays.asList(
                        meta("demo-old", "source-a", "source/demo", "community"),
                        meta("demo", "source-b", "source/demo", "trusted"),
                        meta("other", "source-a", "source/other", "builtin")));
        browse.setTimedOutSources(Collections.singletonList("github"));
        HubInstallRecord installed = new HubInstallRecord();
        installed.setIdentifier("source/demo");
        installed.setName("demo");
        installed.setTrustLevel("trusted");
        installed.setScanVerdict("allowed");
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<Object>> arguments = new AtomicReference<List<Object>>();
        DashboardSkillsService service =
                service(calls, arguments, browse, Collections.singletonList(installed));

        Map<String, Object> response = service.searchHub("  demo  ", "", 100);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(arguments.get()).containsExactly("demo", "all", Integer.valueOf(50));
        assertThat((List<Map<String, Object>>) response.get("results"))
                .extracting("name")
                .containsExactly("demo", "other");
        assertThat((Map<String, Integer>) response.get("source_counts"))
                .containsEntry("source-a", Integer.valueOf(2))
                .containsEntry("source-b", Integer.valueOf(1));
        assertThat((List<String>) response.get("timed_out")).containsExactly("github");
        assertThat((Map<String, Object>) response.get("installed")).containsKey("source/demo");
    }

    /** Controller 路由、默认 limit 和错误解析保持稳定。 */
    @Test
    void shouldExposeHubSearchRouteAndLimitContract() throws Exception {
        Method route = DashboardSkillsController.class.getMethod("searchHub", Context.class);
        Mapping mapping = route.getAnnotation(Mapping.class);
        assertThat(mapping.value()).isEqualTo("/api/skills/hub/search");
        assertThat(mapping.method()).containsExactly(MethodType.GET);

        DashboardSkillsController controller =
                new DashboardSkillsController(service(new AtomicInteger(), null, null, null));
        Method parser =
                DashboardSkillsController.class.getDeclaredMethod("parseLimit", String.class);
        parser.setAccessible(true);
        assertThat(parser.invoke(controller, new Object[] {null})).isEqualTo(Integer.valueOf(20));
        assertThat(parser.invoke(controller, "50")).isEqualTo(Integer.valueOf(50));
        assertThatThrownBy(() -> parser.invoke(controller, "invalid"))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    /** 非当前 Profile 必须使用自己的 Hub 状态，未知 Profile 必须映射为 HTTP 404。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldScopeHubSearchToTargetProfileAndReturnNotFound() throws Exception {
        Path root = Files.createTempDirectory("solonclaw-hub-profile-scope-");
        Path wrappers = Files.createTempDirectory("solonclaw-hub-profile-scope-wrappers-");
        try {
            ProfileManager manager = new ProfileManager(root, wrappers, "solonclaw");
            manager.createProfile("target", new ProfileCreateOptions().setNoAlias(true));
            AppConfig currentConfig = profileConfig(root);
            DashboardProfileScope scope =
                    new DashboardProfileScope(manager, "default", currentConfig);
            HubInstallRecord currentRecord = installed("current-skill", "source/current");
            DashboardSkillsService service =
                    service(
                            new AtomicInteger(),
                            null,
                            new SkillBrowseResult(),
                            Collections.singletonList(currentRecord),
                            scope);

            Path targetSkills = root.resolve("profiles/target/skills");
            SkillHubStateStore targetState = new SkillHubStateStore(targetSkills.toFile());
            targetState.recordInstall(installed("target-skill", "source/target"));

            Map<String, Object> current = service.searchHub("probe", "missing-source", 20, null);
            Map<String, Object> target = service.searchHub("probe", "missing-source", 20, "target");
            assertThat((Map<String, Object>) current.get("installed"))
                    .containsKey("source/current")
                    .doesNotContainKey("source/target");
            assertThat((Map<String, Object>) target.get("installed"))
                    .containsKey("source/target")
                    .doesNotContainKey("source/current");
            assertThatThrownBy(
                            () ->
                                    service.searchHub(
                                            "probe", "missing-source", 20, "missing-profile"))
                    .isInstanceOf(DashboardProfileScope.ProfileNotFoundException.class);

            Context context = ContextEmpty.create();
            context.paramMap().put("q", "probe");
            context.paramMap().put("source", "missing-source");
            context.paramMap().put("profile", "missing-profile");
            Map<String, Object> response =
                    new DashboardSkillsController(service).searchHub(context);
            assertThat(context.status()).isEqualTo(404);
            assertThat(response)
                    .containsEntry("success", Boolean.FALSE)
                    .containsEntry("code", "SKILLS_PROFILE_NOT_FOUND");
        } finally {
            deleteTree(root);
            deleteTree(wrappers);
        }
    }

    /** 创建只实现搜索和安装状态读取的 Skills Hub 测试替身。 */
    private DashboardSkillsService service(
            AtomicInteger calls,
            AtomicReference<List<Object>> arguments,
            SkillBrowseResult browse,
            List<HubInstallRecord> installed) {
        return service(calls, arguments, browse, installed, null);
    }

    /** 创建可选 Profile 解析器的 Skills Hub 测试替身。 */
    private DashboardSkillsService service(
            AtomicInteger calls,
            AtomicReference<List<Object>> arguments,
            SkillBrowseResult browse,
            List<HubInstallRecord> installed,
            DashboardProfileScope profileScope) {
        SkillHubService hub =
                (SkillHubService)
                        Proxy.newProxyInstance(
                                SkillHubService.class.getClassLoader(),
                                new Class<?>[] {SkillHubService.class},
                                (proxy, method, args) -> {
                                    if ("search".equals(method.getName())) {
                                        calls.incrementAndGet();
                                        if (arguments != null) {
                                            arguments.set(
                                                    new ArrayList<Object>(Arrays.asList(args)));
                                        }
                                        return browse == null ? new SkillBrowseResult() : browse;
                                    }
                                    if ("listInstalled".equals(method.getName())) {
                                        return installed == null
                                                ? Collections.emptyList()
                                                : installed;
                                    }
                                    if ("toString".equals(method.getName())) {
                                        return "skill-hub-test";
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
        return new DashboardSkillsService(null, null, profileScope, hub);
    }

    /** 构造能写入 Hub 状态文件的已安装记录。 */
    private HubInstallRecord installed(String name, String identifier) {
        HubInstallRecord record = new HubInstallRecord();
        record.setName(name);
        record.setSource("test");
        record.setIdentifier(identifier);
        record.setTrustLevel("trusted");
        record.setScanVerdict("allowed");
        record.setInstallPath(name);
        return record;
    }

    /** 为指定临时工作区加载独立配置。 */
    private AppConfig profileConfig(Path home) {
        Props props = new Props();
        props.put("solonclaw.workspace", home.toString());
        return AppConfig.loadDetached(props);
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

    /** 构造一条 Hub 搜索元数据。 */
    private SkillMeta meta(String name, String source, String identifier, String trust) {
        SkillMeta meta = new SkillMeta();
        meta.setName(name);
        meta.setDescription(name + " description");
        meta.setSource(source);
        meta.setIdentifier(identifier);
        meta.setTrustLevel(trust);
        meta.setRepo("repo/example");
        meta.setTags(Collections.singletonList("tag"));
        return meta;
    }
}
