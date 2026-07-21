package com.jimuqu.solon.claw.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 Profile 根目录变更锁的路径和串行化行为。 */
class ProfileMutationLockTest {
    /** 每个测试独占的临时目录。 */
    @TempDir Path tempDir;

    /**
     * 锁文件应落在机器级 root 的 profiles 目录下。
     *
     * @throws Exception 读取路径或构造配置失败时抛出异常。
     */
    @Test
    void shouldResolveLockUnderProfileRoot() throws Exception {
        Path root = tempDir.resolve("workspace");
        Path home = root.resolve("profiles/writer");
        Files.createDirectories(home);
        AppConfig config = loadConfig(home);

        assertThat(ProfileMutationLock.lockPath(config))
                .isEqualTo(
                        root.resolve("profiles/.provider-cron.lock").toAbsolutePath().normalize());
    }

    /**
     * 同一 JVM 内应先串行化锁对象，再进入跨进程文件锁。
     *
     * @throws Exception 执行锁动作失败时抛出异常。
     */
    @Test
    void shouldSerializeConcurrentMutationsInJvm() throws Exception {
        Path root = tempDir.resolve("workspace");
        Path home = root.resolve("profiles/writer");
        Files.createDirectories(home);
        AppConfig config = loadConfig(home);
        ProfileMutationLock lock = new ProfileMutationLock(config);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> first =
                    executor.submit(
                            () ->
                                    lock.withLock(
                                            () -> {
                                                entered.countDown();
                                                release.await(5, TimeUnit.SECONDS);
                                                return "first";
                                            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<String> second = executor.submit(() -> lock.withLock(() -> "second"));

            TimeUnit.MILLISECONDS.sleep(200);
            assertThat(second.isDone()).isFalse();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
            assertThat(ProfileMutationLock.lockPath(config)).exists();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** Dashboard 通用配置写入必须等待 Provider、Profile 与 Cron 共用的根目录锁。 */
    @Test
    void shouldSerializeDashboardConfigWritesWithRootMutations() throws Exception {
        Path root = tempDir.resolve("dashboard-workspace");
        Files.createDirectories(root);
        AppConfig config = loadConfig(root);
        DashboardConfigService service = new DashboardConfigService(config, null);
        DashboardRuntimeConfigService runtimeService =
                new DashboardRuntimeConfigService(
                        config, new GatewayRuntimeRefreshService(config, null));
        ProfileMutationLock lock = new ProfileMutationLock(config);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);

        try {
            Future<Void> first =
                    executor.submit(
                            () ->
                                    lock.withLock(
                                            () -> {
                                                entered.countDown();
                                                release.await(5, TimeUnit.SECONDS);
                                                return null;
                                            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Map<String, Object>> second =
                    executor.submit(
                            () ->
                                    service.savePartialFlat(
                                            Collections.<String, Object>singletonMap(
                                                    "llm.temperature", Double.valueOf(0.2d)),
                                            false));
            Future<Map<String, Object>> third =
                    executor.submit(
                            () -> runtimeService.set("solonclaw.react.maxSteps", "12", false));

            TimeUnit.MILLISECONDS.sleep(200);
            assertThat(second.isDone()).isFalse();
            assertThat(third.isDone()).isFalse();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS)).containsEntry("ok", Boolean.TRUE);
            assertThat(third.get(5, TimeUnit.SECONDS)).containsEntry("ok", Boolean.TRUE);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** Profile 生命周期写入必须等待 Provider 与 Cron 共用的根目录锁。 */
    @Test
    void shouldSerializeProfileLifecycleWithRootMutations() throws Exception {
        Path root = tempDir.resolve("profile-workspace");
        Files.createDirectories(root);
        AppConfig config = loadConfig(root);
        ProfileMutationLock lock = new ProfileMutationLock(config);
        ProfileManager manager =
                new ProfileManager(root, tempDir.resolve("profile-bin"), "solonclaw");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Void> first =
                    executor.submit(
                            () ->
                                    lock.withLock(
                                            () -> {
                                                entered.countDown();
                                                release.await(5, TimeUnit.SECONDS);
                                                return null;
                                            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Path> second =
                    executor.submit(
                            () ->
                                    manager.createProfile(
                                            "worker", new ProfileCreateOptions().setNoAlias(true)));

            TimeUnit.MILLISECONDS.sleep(200);
            assertThat(second.isDone()).isFalse();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS)).isDirectory();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** Profile 活动项、说明和别名写入口也必须等待统一根目录锁。 */
    @Test
    void shouldSerializeProfileMetadataWritesWithRootMutations() throws Exception {
        Path root = tempDir.resolve("profile-metadata-workspace");
        Files.createDirectories(root);
        AppConfig config = loadConfig(root);
        ProfileMutationLock lock = new ProfileMutationLock(config);
        ProfileManager manager =
                new ProfileManager(root, tempDir.resolve("profile-metadata-bin"), "solonclaw");
        manager.createProfile("worker", new ProfileCreateOptions().setNoAlias(true));

        assertWaitsForRootLock(
                lock,
                () -> {
                    manager.useProfile("worker");
                    return null;
                });
        assertWaitsForRootLock(lock, () -> manager.setDescription("worker", "构建任务"));
        assertWaitsForRootLock(lock, () -> manager.createProfileAlias("worker", "worker-alias"));
        assertWaitsForRootLock(lock, () -> manager.removeProfileAlias("worker", "worker-alias"));
    }

    /** CLI 活动项、人工说明和别名入口不得绕过统一根目录锁。 */
    @Test
    void shouldSerializeCliMetadataWritesWithRootMutations() throws Exception {
        Path root = tempDir.resolve("profile-cli-workspace");
        Path wrappers = tempDir.resolve("profile-cli-bin");
        Files.createDirectories(root);
        AppConfig config = loadConfig(root);
        ProfileMutationLock lock = new ProfileMutationLock(config);
        ProfileManager manager = new ProfileManager(root, wrappers, "solonclaw");
        manager.createProfile("worker", new ProfileCreateOptions().setNoAlias(true));

        assertWaitsForRootLock(lock, () -> executeProfileCommand(manager, "use", "worker"));
        assertWaitsForRootLock(
                lock, () -> executeProfileCommand(manager, "describe", "worker", "--text", "构建任务"));
        assertWaitsForRootLock(
                lock,
                () -> executeProfileCommand(manager, "alias", "worker", "--name", "cli-worker"));

        assertThat(manager.activeProfile()).isEqualTo("worker");
        assertThat(manager.profileView("worker").getDescription()).isEqualTo("构建任务");
        assertThat(wrappers.resolve("cli-worker")).exists();
    }

    /** 自动说明只在模型返回后短暂持锁写入元数据，并等待并发根目录变更结束。 */
    @Test
    void shouldSerializeGeneratedDescriptionWriteWithRootMutations() throws Exception {
        Path root = tempDir.resolve("profile-auto-description-workspace");
        Files.createDirectories(root);
        AppConfig config = loadConfig(root);
        ProfileMutationLock lock = new ProfileMutationLock(config);
        ProfileManager manager =
                new ProfileManager(
                        root,
                        tempDir.resolve("profile-auto-description-bin"),
                        "solonclaw",
                        new ProfileDescriptionService(
                                (profileConfig, systemPrompt, userPrompt) ->
                                        "{\"description\":\"自动构建任务\"}"));
        manager.createProfile("worker", new ProfileCreateOptions().setNoAlias(true));

        assertWaitsForRootLock(lock, () -> manager.describeProfile("worker", true));

        assertThat(manager.profileView("worker").getDescription()).isEqualTo("自动构建任务");
    }

    /**
     * 断言指定 Profile 写动作在根锁释放前不会完成。
     *
     * @param lock 统一根目录锁。
     * @param action 待验证写动作。
     * @param <T> 写动作返回类型。
     * @throws Exception 加锁或写动作失败时抛出异常。
     */
    private <T> void assertWaitsForRootLock(ProfileMutationLock lock, Callable<T> action)
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> holder =
                    executor.submit(
                            () ->
                                    lock.withLock(
                                            () -> {
                                                entered.countDown();
                                                release.await(5, TimeUnit.SECONDS);
                                                return null;
                                            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<T> mutation = executor.submit(action);
            TimeUnit.MILLISECONDS.sleep(100);
            assertThat(mutation.isDone()).isFalse();
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            mutation.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** 执行 Profile CLI 子命令并返回退出码。 */
    private int executeProfileCommand(ProfileManager manager, String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode =
                manager.execute(
                        Arrays.asList(args),
                        "default",
                        new ByteArrayInputStream(new byte[0]),
                        new PrintStream(stdout, true, StandardCharsets.UTF_8.name()),
                        new PrintStream(stderr, true, StandardCharsets.UTF_8.name()));
        assertThat(new String(stderr.toByteArray(), StandardCharsets.UTF_8)).isEmpty();
        assertThat(exitCode).isZero();
        return exitCode;
    }

    /**
     * 构造一个最小配置快照。
     *
     * @param home Profile 工作区。
     * @return AppConfig 结果。
     */
    private AppConfig loadConfig(Path home) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getWorkspace().setDir(home.toString());
        config.getRuntime().setConfigFile(home.resolve("config.yml").toString());
        config.getRuntime().setStateDb(home.resolve("data/state.db").toString());
        return config;
    }
}
