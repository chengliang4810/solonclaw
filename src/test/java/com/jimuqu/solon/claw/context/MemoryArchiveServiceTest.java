package com.jimuqu.solon.claw.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.MemorySearchResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.core.service.MemoryService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.message.AssistantMessage;

/** 旧每日记忆不可变归档、摘要、审批和恢复测试。 */
class MemoryArchiveServiceTest {
    /** 固定测试日期。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-17T08:00:00Z"), ZoneId.of("Asia/Shanghai"));

    /** 临时工作区。 */
    @TempDir Path tempDir;

    /** 验证旧文件原始字节保真、数据摘要包含真实条目且可通过统一索引读取。 */
    @Test
    void shouldArchiveExactBytesBuildFallbackSummaryAndRemainSearchable() throws Exception {
        AppConfig config = config(false);
        FileMemoryService memory = new FileMemoryService(config);
        Path source = tempDir.resolve("memory/2026-05-01.md");
        byte[] original =
                ("# 2026-05-01\r\n\r\n- 项目约定：archive-search-marker 使用蓝绿发布\r\n")
                        .getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(source.getParent());
        Files.write(source, original);
        MemoryArchiveService service = service(config, memory, new NoopGateway());

        MemoryArchiveState state = service.runOnce();
        Path archive = onlyArchive();
        Path summary = summaryPath(archive);

        assertThat(state.getLastOutcome()).isEqualTo(MemoryArchiveState.OUTCOME_SUCCESS);
        assertThat(state.getArchivedCount()).isEqualTo(1);
        assertThat(state.getSummarizedByFallbackCount()).isEqualTo(1);
        assertThat(Files.exists(source)).isFalse();
        assertThat(Files.readAllBytes(archive)).isEqualTo(original);
        assertThat(read(summary))
                .contains("数据回退")
                .contains("archive-search-marker")
                .contains("不可变原文");

        Files.createDirectories(tempDir.resolve("data"));
        List<MemorySearchResult> matches = memory.search("archive-search-marker", 10);
        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).getPath()).startsWith("memory/archive/2026/05/");
        assertThat(memory.get(matches.get(0).getPath())).contains("archive-search-marker");

        PersonaWorkspaceService workspace = new PersonaWorkspaceService(config);
        assertThat(workspace.listDiaryRelativePaths())
                .contains(
                        "memory/archive/2026/05/" + archive.getFileName(),
                        "memory/archive/2026/05/" + summary.getFileName());
        service.shutdown();
    }

    /** 验证模型只看到脱敏真实正文，稳定候选走审批且摘要丢失重试不会重复排队。 */
    @Test
    void shouldRedactModelInputAndDeduplicatePendingCandidateOnRetry() throws Exception {
        AppConfig config = config(true);
        FileMemoryService memory = new FileMemoryService(config);
        memory.setApprovalEnabled(true);
        Path source = tempDir.resolve("memory/2026-05-02.md");
        Files.createDirectories(source.getParent());
        Files.write(
                source,
                ("# 2026-05-02\n- 用户要求先测试，token=sk-test-archive1234567890\n")
                        .getBytes(StandardCharsets.UTF_8));
        RecordingGateway gateway =
                new RecordingGateway(
                        "{\"summary\":\"用户反复要求先完成验证再提交。\","
                                + "\"stable_memory_candidates\":[\"长期偏好：提交前先运行相关测试。\"]}");
        MemoryArchiveService service = service(config, memory, gateway);

        service.runOnce();
        Path archive = onlyArchive();
        Path summary = summaryPath(archive);
        assertThat(memory.listPendingApprovals()).hasSize(1);
        assertThat(gateway.lastUserMessage)
                .contains("用户要求先测试")
                .contains("token=***")
                .doesNotContain("sk-test-archive1234567890");
        assertThat(read(summary)).contains("归纳方式：AI").contains("长期偏好：提交前先运行相关测试");

        Files.write(summary, "corrupt-summary".getBytes(StandardCharsets.UTF_8));
        service.runOnce();

        assertThat(memory.listPendingApprovals()).hasSize(1);
        assertThat(gateway.calls).hasValue(2);

        assertThat(memory.approve("all")).contains("已批准并应用 1 条");
        Files.write(summary, "corrupt-after-approval".getBytes(StandardCharsets.UTF_8));
        service.runOnce();

        assertThat(memory.read("memory")).contains("长期偏好：提交前先运行相关测试。");
        assertThat(memory.listPendingApprovals()).isEmpty();
        assertThat(gateway.calls).hasValue(3);
        service.shutdown();
    }

    /** 验证模型畸形输出自动回退，且原文中的 fence 不会让回退摘要失败。 */
    @Test
    void shouldFallbackWhenModelOutputIsInvalid() throws Exception {
        AppConfig config = config(true);
        FileMemoryService memory = new FileMemoryService(config);
        Path source = tempDir.resolve("memory/2026-05-03.md");
        Files.createDirectories(source.getParent());
        Files.write(
                source,
                "# 2026-05-03\n- ```不可信块```\n- 实际完成数据库核验\n".getBytes(StandardCharsets.UTF_8));
        MemoryArchiveService service = service(config, memory, new RecordingGateway("not-json"));

        MemoryArchiveState state = service.runOnce();

        assertThat(state.getLastOutcome()).isEqualTo(MemoryArchiveState.OUTCOME_SUCCESS);
        assertThat(state.getSummarizedByFallbackCount()).isEqualTo(1);
        assertThat(read(summaryPath(onlyArchive())))
                .contains("数据回退")
                .contains("实际完成数据库核验")
                .doesNotContain("```");
        service.shutdown();
    }

    /** 验证摘要、候选的非字符串节点及额外包装文本都不能冒充严格 JSON 模型结果。 */
    @Test
    void shouldFallbackWhenModelJsonContainsNonStringNodesOrWrapperText() throws Exception {
        assertInvalidModelOutputFallsBack(
                "numeric-summary", "{\"summary\":123,\"stable_memory_candidates\":[]}");
        assertInvalidModelOutputFallsBack(
                "object-candidate",
                "{\"summary\":\"有效摘要\",\"stable_memory_candidates\":[{\"text\":\"长期偏好：先测试\"}]}");
        assertInvalidModelOutputFallsBack(
                "wrapped-json", "模型结果：{\"summary\":\"有效摘要\",\"stable_memory_candidates\":[]}");
    }

    /** 验证保留期边界、当天、未来文件、专题文件和符号链接均不会被错误归档。 */
    @Test
    void shouldSelectOnlyStrictOldDailyFiles() throws Exception {
        AppConfig config = config(false);
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);
        Files.write(memoryDir.resolve("2026-06-16.md"), "old".getBytes(StandardCharsets.UTF_8));
        Files.write(
                memoryDir.resolve("2026-06-17.md"), "boundary".getBytes(StandardCharsets.UTF_8));
        Files.write(memoryDir.resolve("2026-07-17.md"), "today".getBytes(StandardCharsets.UTF_8));
        Files.write(memoryDir.resolve("2026-07-18.md"), "future".getBytes(StandardCharsets.UTF_8));
        Files.write(memoryDir.resolve("topic.md"), "topic".getBytes(StandardCharsets.UTF_8));
        try {
            Files.createSymbolicLink(
                    memoryDir.resolve("2026-05-01.md"), memoryDir.resolve("topic.md"));
        } catch (UnsupportedOperationException e) {
            // 不支持符号链接的文件系统仍覆盖其余候选边界。
        }
        MemoryArchiveService service =
                service(config, new FileMemoryService(config), new NoopGateway());

        service.runOnce();

        assertThat(Files.exists(memoryDir.resolve("2026-06-16.md"))).isFalse();
        assertThat(Files.readAllBytes(memoryDir.resolve("2026-06-17.md")))
                .isEqualTo("boundary".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.exists(memoryDir.resolve("2026-07-17.md"))).isTrue();
        assertThat(Files.exists(memoryDir.resolve("2026-07-18.md"))).isTrue();
        assertThat(Files.exists(memoryDir.resolve("topic.md"))).isTrue();
        service.shutdown();
    }

    /** 验证恢复保留归档原文，同内容幂等而不同内容 fail-closed。 */
    @Test
    void shouldRestoreWithoutRemovingArchiveAndRejectConflictingActiveFile() throws Exception {
        AppConfig config = config(false);
        Path source = tempDir.resolve("memory/2026-05-04.md");
        byte[] original = "# 2026-05-04\n- recover-marker\n".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(source.getParent());
        Files.write(source, original);
        MemoryArchiveService service =
                service(config, new FileMemoryService(config), new NoopGateway());
        service.runOnce();
        Path archive = onlyArchive();
        String relative =
                tempDir.relativize(archive).toString().replace(java.io.File.separatorChar, '/');

        assertThat(service.restore(relative)).contains("已恢复");
        assertThat(Files.readAllBytes(source)).isEqualTo(original);
        assertThat(Files.readAllBytes(archive)).isEqualTo(original);
        assertThat(service.restore(relative)).contains("无需恢复");

        Files.write(source, "conflict".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.restore(relative))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝覆盖");
        assertThat(Files.readAllBytes(archive)).isEqualTo(original);
        service.shutdown();
    }

    /** 验证恢复只写入取得记忆锁后重新读取并校验的归档字节，拒绝锁等待期间的文件替换。 */
    @Test
    void shouldRejectArchiveReplacementWhileRestoreWaitsForMemoryLock() throws Exception {
        AppConfig config = config(false);
        Path source = tempDir.resolve("memory/2026-05-07.md");
        Files.createDirectories(source.getParent());
        Files.write(source, "# 2026-05-07\n- original\n".getBytes(StandardCharsets.UTF_8));
        MemoryArchiveService service =
                service(config, new FileMemoryService(config), new NoopGateway());
        service.runOnce();
        Path archive = onlyArchive();
        String relative =
                tempDir.relativize(archive).toString().replace(java.io.File.separatorChar, '/');
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> restoreThread = new AtomicReference<Thread>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Future<String>> future = new AtomicReference<Future<String>>();
        try {
            new MemoryFileLock(config)
                    .withLock(
                            () -> {
                                future.set(
                                        executor.submit(
                                                () -> {
                                                    restoreThread.set(Thread.currentThread());
                                                    started.countDown();
                                                    return service.restore(relative);
                                                }));
                                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                                assertThat(waitUntilBlocked(restoreThread.get())).isTrue();
                                Files.write(
                                        archive,
                                        "# 2026-05-07\n- replaced\n"
                                                .getBytes(StandardCharsets.UTF_8));
                                return null;
                            });

            assertThatThrownBy(() -> future.get().get(2, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("归档原文哈希校验失败。");
            assertThat(Files.exists(source)).isFalse();
        } finally {
            executor.shutdownNow();
            service.shutdown();
        }
    }

    /** 验证 archive、年份和月份任一祖先目录为符号链接时均拒绝向外部写入。 */
    @Test
    void shouldRejectSymlinkAtEveryArchiveDirectoryLevel() throws Exception {
        assertArchiveSymlinkRejected("archive");
        assertArchiveSymlinkRejected("year");
        assertArchiveSymlinkRejected("month");
    }

    /** 验证辅助模型阻塞期间不持有记忆 mutation 锁，普通今日记忆写入仍可完成。 */
    @Test
    void shouldNotBlockNormalMemoryWritesWhileModelIsRunning() throws Exception {
        AppConfig config = config(true);
        FileMemoryService memory = new FileMemoryService(config);
        Path source = tempDir.resolve("memory/2026-05-05.md");
        Files.createDirectories(source.getParent());
        Files.write(source, "# 2026-05-05\n- durable-entry\n".getBytes(StandardCharsets.UTF_8));
        BlockingGateway gateway = new BlockingGateway();
        MemoryArchiveService service = service(config, memory, gateway);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MemoryArchiveState> archiveFuture = executor.submit(service::runOnce);
            assertThat(gateway.entered.await(2, TimeUnit.SECONDS)).isTrue();

            Future<String> writeFuture =
                    executor.submit(() -> memory.add("today", "归档模型运行期间的普通写入"));
            assertThat(writeFuture.get(1, TimeUnit.SECONDS)).contains("已写入");

            gateway.release.countDown();
            assertThat(archiveFuture.get(2, TimeUnit.SECONDS).getLastOutcome())
                    .isEqualTo(MemoryArchiveState.OUTCOME_SUCCESS);
        } finally {
            gateway.release.countDown();
            executor.shutdownNow();
            service.shutdown();
        }
    }

    /** 创建当前测试工作区配置。 */
    private AppConfig config(boolean aiEnabled) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(tempDir.toString());
        config.getRuntime().setStateDb(tempDir.resolve("data/state.db").toString());
        config.getWorkspace().setDir(tempDir.toString());
        config.getMemory().getArchive().setEnabled(true);
        config.getMemory().getArchive().setRetentionDays(30);
        config.getMemory().getArchive().setMaxFilesPerRun(7);
        config.getMemory().getArchive().setAiSummaryEnabled(aiEnabled);
        config.getMemory().getArchive().setAuxiliaryTimeoutSeconds(2);
        return config;
    }

    /** 在指定归档目录层级放置越界符号链接，并断言原文件与外部目录均保持不变。 */
    private void assertArchiveSymlinkRejected(String level) throws Exception {
        Path caseRoot = tempDir.resolve("symlink-" + level);
        AppConfig config = config(false);
        config.getRuntime().setHome(caseRoot.toString());
        config.getRuntime().setStateDb(caseRoot.resolve("data/state.db").toString());
        config.getWorkspace().setDir(caseRoot.toString());
        Path memory = caseRoot.resolve("memory");
        Path source = memory.resolve("2026-05-06.md");
        Path external = tempDir.resolve("outside-" + level);
        Files.createDirectories(memory);
        Files.createDirectories(external);
        Files.write(source, ("source-" + level).getBytes(StandardCharsets.UTF_8));
        if ("archive".equals(level)) {
            Files.createSymbolicLink(memory.resolve("archive"), external);
        } else if ("year".equals(level)) {
            Files.createDirectories(memory.resolve("archive"));
            Files.createSymbolicLink(memory.resolve("archive/2026"), external);
        } else {
            Files.createDirectories(memory.resolve("archive/2026"));
            Files.createSymbolicLink(memory.resolve("archive/2026/05"), external);
        }
        MemoryArchiveService service =
                service(config, new FileMemoryService(config), new NoopGateway());
        try {
            MemoryArchiveState state = service.runOnce();
            assertThat(state.getLastOutcome()).isEqualTo(MemoryArchiveState.OUTCOME_FAILED);
            assertThat(state.getFailedCount()).isPositive();
            assertThat(Files.readAllBytes(source))
                    .isEqualTo(("source-" + level).getBytes(StandardCharsets.UTF_8));
            try (java.util.stream.Stream<Path> files = Files.list(external)) {
                assertThat(files.collect(Collectors.toList())).isEmpty();
            }
        } finally {
            service.shutdown();
        }
    }

    /** 在独立工作区运行一个畸形模型输出用例，并断言真实条目触发数据回退。 */
    private void assertInvalidModelOutputFallsBack(String caseName, String response)
            throws Exception {
        Path caseRoot = tempDir.resolve(caseName);
        AppConfig config = config(true);
        config.getRuntime().setHome(caseRoot.toString());
        config.getRuntime().setStateDb(caseRoot.resolve("data/state.db").toString());
        config.getWorkspace().setDir(caseRoot.toString());
        Path source = caseRoot.resolve("memory/2026-05-08.md");
        Files.createDirectories(source.getParent());
        Files.write(source, "# 2026-05-08\n- strict-json-entry\n".getBytes(StandardCharsets.UTF_8));
        MemoryArchiveService service =
                service(config, new FileMemoryService(config), new RecordingGateway(response));
        try {
            MemoryArchiveState state = service.runOnce();
            assertThat(state.getSummarizedByFallbackCount()).isEqualTo(1);
            Path archive;
            try (java.util.stream.Stream<Path> files =
                    Files.walk(caseRoot.resolve("memory/archive"))) {
                archive =
                        files.filter(Files::isRegularFile)
                                .filter(
                                        path ->
                                                path.getFileName()
                                                        .toString()
                                                        .matches(".*--[0-9a-f]{12}\\.md"))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("缺少归档原文"));
            }
            assertThat(read(summaryPath(archive)))
                    .contains("数据回退", "strict-json-entry")
                    .doesNotContain("归纳方式：AI");
        } finally {
            service.shutdown();
        }
    }

    /** 等待恢复线程进入 JVM 记忆锁阻塞态。 */
    private boolean waitUntilBlocked(Thread thread) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (thread != null && thread.getState() == Thread.State.BLOCKED) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    /** 创建使用固定时钟和内存状态仓储的归档服务。 */
    private MemoryArchiveService service(
            AppConfig config, MemoryService memoryService, LlmGateway gateway) {
        return new MemoryArchiveService(
                config, memoryService, gateway, new MemorySettings(), FIXED_CLOCK);
    }

    /** 返回当前测试生成的唯一不可变归档原文。 */
    private Path onlyArchive() throws Exception {
        try (java.util.stream.Stream<Path> files = Files.walk(tempDir.resolve("memory/archive"))) {
            List<Path> archives =
                    files.filter(Files::isRegularFile)
                            .filter(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                                                    .matches(".*--[0-9a-f]{12}\\.md"))
                            .collect(Collectors.toList());
            assertThat(archives).hasSize(1);
            return archives.get(0);
        }
    }

    /** 返回原文旁的派生摘要路径。 */
    private Path summaryPath(Path archive) {
        return archive.resolveSibling(archive.getFileName().toString() + ".summary.md");
    }

    /** 以 UTF-8 读取测试文件。 */
    private String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 固定模型输出并记录真实调用输入。 */
    private static final class RecordingGateway implements LlmGateway {
        /** 固定模型回复。 */
        private final String response;

        /** 模型调用次数。 */
        private final AtomicInteger calls = new AtomicInteger();

        /** 最近一次用户提示。 */
        private String lastUserMessage;

        /** 创建固定模型网关。 */
        private RecordingGateway(String response) {
            this.response = response;
        }

        /** 返回固定辅助模型结果。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            calls.incrementAndGet();
            lastUserMessage = userMessage;
            LlmResult result = new LlmResult();
            result.setAssistantMessage(new AssistantMessage(response));
            return result;
        }

        /** 测试不支持恢复调用。 */
        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new UnsupportedOperationException();
        }
    }

    /** 不允许意外模型调用的网关。 */
    private static final class NoopGateway implements LlmGateway {
        /** 拒绝聊天调用。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            throw new AssertionError("数据回退模式不应调用模型");
        }

        /** 拒绝恢复调用。 */
        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new AssertionError("记忆归档不应恢复模型会话");
        }
    }

    /** 阻塞模型调用直到测试允许继续，用于验证锁持有范围。 */
    private static final class BlockingGateway implements LlmGateway {
        /** 模型已进入调用的信号。 */
        private final CountDownLatch entered = new CountDownLatch(1);

        /** 允许模型返回的信号。 */
        private final CountDownLatch release = new CountDownLatch(1);

        /** 等待测试放行后返回有效空候选摘要。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            LlmResult result = new LlmResult();
            result.setAssistantMessage(
                    new AssistantMessage(
                            "{\"summary\":\"真实条目用于验证锁范围。\",\"stable_memory_candidates\":[]}"));
            return result;
        }

        /** 测试不支持恢复调用。 */
        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new UnsupportedOperationException();
        }
    }

    /** 内存全局设置仓储。 */
    private static final class MemorySettings implements GlobalSettingRepository {
        /** 设置值。 */
        private final Map<String, String> values = new LinkedHashMap<String, String>();

        /** 读取设置。 */
        @Override
        public String get(String key) {
            return values.get(key);
        }

        /** 保存设置。 */
        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }

        /** 删除设置。 */
        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}
