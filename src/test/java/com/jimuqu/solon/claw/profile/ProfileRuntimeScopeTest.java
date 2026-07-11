package com.jimuqu.solon.claw.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SubprocessEnvironmentSanitizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Props;

/** 验证命名 Profile 在异步线程、Bean 容器和子进程环境中的作用域隔离。 */
public class ProfileRuntimeScopeTest {
    @Test
    void shouldCaptureAndRestoreProfilesOnPreheatedExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThat(executor.submit(ProfileRuntimeScope::current).get()).isNull();

            Path root = Files.createTempDirectory("profile-runtime-scope");
            AppContext contextA = contextWith(new Marker("bean-a"));
            AppContext contextB = contextWith(new Marker("bean-b"));

            Future<Observation> resultA;
            ProfileRuntimeScope.Context capturedA;
            try (ProfileRuntimeScope.Scope ignored =
                    ProfileRuntimeScope.open(
                            "a",
                            root.resolve("profiles/a"),
                            Collections.singletonMap("PROFILE_VALUE", "env-a"),
                            contextA)) {
                capturedA = ProfileRuntimeScope.current();
            }
            resultA = executor.submit(ProfileRuntimeScope.capture(capturedA, observation()));

            Future<Observation> resultB;
            try (ProfileRuntimeScope.Scope ignored =
                    ProfileRuntimeScope.open(
                            "b",
                            root.resolve("profiles/b"),
                            Collections.singletonMap("PROFILE_VALUE", "env-b"),
                            contextB)) {
                resultB = executor.submit(ProfileRuntimeScope.capture(observation()));
            }

            assertObservation(resultA.get(), "a", "env-a", "bean-a", root.resolve("profiles/a"));
            assertObservation(resultB.get(), "b", "env-b", "bean-b", root.resolve("profiles/b"));
            assertThat(executor.submit(ProfileRuntimeScope::current).get()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldNotImplicitlyInheritScopeIntoRawThread() throws Exception {
        AtomicReference<ProfileRuntimeScope.Context> observed =
                new AtomicReference<ProfileRuntimeScope.Context>();
        Thread thread;
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "a",
                        Files.createTempDirectory("profile-raw-thread"),
                        Collections.singletonMap("PROFILE_VALUE", "env-a"),
                        null)) {
            thread = new Thread(() -> observed.set(ProfileRuntimeScope.current()));
            thread.start();
            thread.join(5000L);
        }

        assertThat(thread.isAlive()).isFalse();
        assertThat(observed.get()).isNull();
    }

    @Test
    void shouldNotFallbackToDefaultBeanContextWhileScopedContextIsUnavailable() throws Exception {
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "loading",
                        Files.createTempDirectory("profile-loading-context"),
                        Collections.<String, String>emptyMap(),
                        null)) {
            assertThat(ProfileBeanResolver.currentContext()).isNull();
            assertThat(ProfileBeanResolver.getBean(Marker.class)).isNull();
        }
    }

    @Test
    void shouldReplaceProcessEnvironmentBeforeConfiguredPassthrough() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("ignored");
        builder.environment().clear();
        builder.environment().put("DEFAULT_PROFILE_PASSWORD", "default-secret");
        AppConfig config = new AppConfig();
        config.getTerminal()
                .setEnvPassthrough(
                        Arrays.asList("DEFAULT_PROFILE_PASSWORD", "PROFILE_VISIBLE_VALUE"));

        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "a",
                        Files.createTempDirectory("profile-process-env").resolve("profiles/a"),
                        Collections.singletonMap("PROFILE_VISIBLE_VALUE", "profile-a"),
                        null)) {
            ProfileRuntimeScope.replaceProcessEnvironment(builder.environment());
            SubprocessEnvironmentSanitizer.sanitize(builder.environment(), config);
        }

        assertThat(builder.environment())
                .containsEntry("PROFILE_VISIBLE_VALUE", "profile-a")
                .containsEntry("SOLONCLAW_PROFILE", "a")
                .doesNotContainKey("DEFAULT_PROFILE_PASSWORD");
        assertThat(builder.environment().get("SOLONCLAW_HOME"))
                .endsWith("profiles" + java.io.File.separator + "a");
    }

    @Test
    void shouldPassOnlyExactRuntimeEnvironmentNamesToChildProcess() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("ignored");
        AppConfig config = new AppConfig();
        config.getTerminal()
                .setEnvPassthrough(
                        Arrays.asList(
                                "OPENAI_API_KEY",
                                "SOLONCLAW_PROVIDER_PRIMARY_API_KEY",
                                "SOLONCLAW_CHANNEL_FEISHU_APP_ID",
                                "SOLONCLAW_CHANNEL_FEISHU_APP_SECRET"));
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("JAVA_HOME", "/profile/jdk");
        environment.put("MAVEN_HOME", "/profile/maven");
        environment.put("SSL_CERT_FILE", "/profile/cert.pem");
        environment.put("HTTP_PROXY", "http://127.0.0.1:7890");
        environment.put("JAVA_HOME_SECRET", "blocked");
        environment.put("HTTP_PROXY_TOKEN", "blocked");
        environment.put("OPENAI_API_KEY", "blocked-provider");
        environment.put("SOLONCLAW_PROVIDER_PRIMARY_API_KEY", "blocked-profile-provider");
        environment.put("SOLONCLAW_CHANNEL_FEISHU_APP_ID", "blocked-channel-id");
        environment.put("SOLONCLAW_CHANNEL_FEISHU_APP_SECRET", "blocked-channel");

        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "a", Files.createTempDirectory("profile-runtime-env"), environment, null)) {
            ProfileRuntimeScope.replaceProcessEnvironment(builder.environment());
            SubprocessEnvironmentSanitizer.sanitize(builder.environment(), config);
        }

        assertThat(builder.environment())
                .containsEntry("JAVA_HOME", "/profile/jdk")
                .containsEntry("MAVEN_HOME", "/profile/maven")
                .containsEntry("SSL_CERT_FILE", "/profile/cert.pem")
                .containsEntry("HTTP_PROXY", "http://127.0.0.1:7890")
                .doesNotContainKeys(
                        "JAVA_HOME_SECRET",
                        "HTTP_PROXY_TOKEN",
                        "OPENAI_API_KEY",
                        "SOLONCLAW_PROVIDER_PRIMARY_API_KEY",
                        "SOLONCLAW_CHANNEL_FEISHU_APP_ID",
                        "SOLONCLAW_CHANNEL_FEISHU_APP_SECRET");
    }

    @Test
    void shouldKeepUnscopedEnvironmentBehaviorAndLimitScopedFallbacks() throws Exception {
        assertThat(ProfileRuntimeScope.environmentSnapshot()).isEqualTo(System.getenv());
        assertThat(ProfileRuntimeScope.environmentValue("PATH")).isEqualTo(System.getenv("PATH"));

        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("PATH", "/profile/bin");
        environment.put("PROFILE_SECRET", "profile-secret");
        environment.put("SOLONCLAW_HOME", "/spoofed/default");
        environment.put("SOLONCLAW_PROFILE", "spoofed");
        Path profileHome = Files.createTempDirectory("profile-environment");
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open("a", profileHome, environment, null)) {
            assertThat(ProfileRuntimeScope.environmentValue("PATH")).isEqualTo("/profile/bin");
            assertThat(ProfileRuntimeScope.environmentValue("PROFILE_SECRET"))
                    .isEqualTo("profile-secret");
            assertThat(ProfileRuntimeScope.environmentValue("OTHER_PROFILE_PASSWORD")).isNull();
            assertThat(ProfileRuntimeScope.environmentValue("SOLONCLAW_PROFILE")).isEqualTo("a");
            assertThat(ProfileRuntimeScope.environmentValue("SOLONCLAW_HOME"))
                    .isEqualTo(profileHome.toAbsolutePath().normalize().toString());
        }

        assertThat(Arrays.asList("XDG_CONFIG_HOME", "VIRTUAL_ENV", "PYTHONPATH", "SSL_CERT_FILE"))
                .allMatch(ProfileRuntimeScope::isProcessFallbackAllowed);
        assertThat(ProfileRuntimeScope.isProcessFallbackAllowed("OPENAI_API_KEY")).isFalse();
        assertThat(ProfileRuntimeScope.isProcessFallbackAllowed("PWD")).isFalse();
        assertThat(ProfileRuntimeScope.isProcessFallbackAllowed("SOLONCLAW_CRON_TIMEOUT"))
                .isFalse();
        assertThat(ProfileRuntimeScope.isProcessFallbackAllowed("SOLONCLAW_PROFILE")).isFalse();
        assertThat(ProfileRuntimeScope.isProcessFallbackAllowed("TERMINAL_SCRIPT")).isFalse();
        assertThat(ProfileRuntimeScope.isProcessFallbackAllowed("TERMINAL_SSH_KEY")).isFalse();
    }

    /** 创建读取当前 Profile、环境、工作区和 Bean 的延迟任务。 */
    private Callable<Observation> observation() {
        return new Callable<Observation>() {
            /** 返回当前工作线程实际看到的 Profile 运行信息。 */
            @Override
            public Observation call() {
                ProfileRuntimeScope.Context current = ProfileRuntimeScope.current();
                Marker marker = ProfileBeanResolver.getBean(Marker.class);
                return new Observation(
                        current == null ? null : current.getProfile(),
                        ProfileRuntimeScope.environmentValue("PROFILE_VALUE"),
                        marker == null ? null : marker.value,
                        current == null ? null : current.getHome(),
                        ProfileBeanResolver.currentContext());
            }
        };
    }

    /** 创建只包含一个标记 Bean 的轻量 Profile 容器。 */
    private AppContext contextWith(Marker marker) {
        AppContext context = new AppContext(null, getClass().getClassLoader(), new Props());
        context.wrapAndPut(Marker.class, marker);
        return context;
    }

    /** 校验捕获任务没有回退到其他 Profile 的环境、Bean 或工作区。 */
    private void assertObservation(
            Observation observed, String profile, String environment, String bean, Path home) {
        assertThat(observed.profile).isEqualTo(profile);
        assertThat(observed.environment).isEqualTo(environment);
        assertThat(observed.bean).isEqualTo(bean);
        assertThat(observed.home).isEqualTo(home.toAbsolutePath().normalize());
        assertThat(observed.context).isNotNull();
    }

    /** 区分两个 Profile 子容器的测试 Bean。 */
    private static final class Marker {
        /** 标记值。 */
        private final String value;

        /** 创建标记 Bean。 */
        private Marker(String value) {
            this.value = value;
        }
    }

    /** 捕获一次异步任务观测到的完整 Profile 运行信息。 */
    private static final class Observation {
        /** Profile 名。 */
        private final String profile;

        /** Profile 环境值。 */
        private final String environment;

        /** Profile Bean 标记。 */
        private final String bean;

        /** Profile 工作区。 */
        private final Path home;

        /** Profile Bean 容器。 */
        private final AppContext context;

        /** 创建不可变观测结果。 */
        private Observation(
                String profile, String environment, String bean, Path home, AppContext context) {
            this.profile = profile;
            this.environment = environment;
            this.bean = bean;
            this.home = home;
            this.context = context;
        }
    }
}
