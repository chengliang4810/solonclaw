package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.SkillUsageTracker;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.SkillTools;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.Props;

public class SkillUsageTrackerTest {
    @Test
    void shouldTrackUnifiedLoadAndCallCounters() throws Exception {
        File tempDir = Files.createTempDirectory("skill-usage-test").toFile();
        AppConfig config = loadConfig(tempDir);
        SkillUsageTracker tracker = new SkillUsageTracker(config);

        tracker.bumpView("my-skill");
        tracker.bumpView("my-skill");
        tracker.bumpInvoke("my-skill");

        java.util.Map<String, Object> entry = tracker.getEntry("my-skill");
        assertThat(((Number) entry.get("loadCount")).intValue()).isEqualTo(2);
        assertThat(((Number) entry.get("callCount")).intValue()).isEqualTo(1);
        assertThat(((Number) entry.get("count")).intValue()).isEqualTo(3);
        assertThat(entry.get("lastActivityAt")).isNotNull();
    }

    /** 管理/编辑活动也必须进入对外统一的技能活动计数。 */
    @Test
    void shouldIncludeManageActivityInUnifiedCount() throws Exception {
        File tempDir = Files.createTempDirectory("skill-usage-manage").toFile();
        SkillUsageTracker tracker = new SkillUsageTracker(loadConfig(tempDir));

        tracker.bumpView("managed-skill");
        tracker.bumpManage("managed-skill", "patch");
        tracker.bumpManage("managed-skill", "archive");

        java.util.Map<String, Object> entry = tracker.getEntry("managed-skill");
        assertThat(((Number) entry.get("manageCount")).intValue()).isEqualTo(2);
        assertThat(((Number) entry.get("count")).intValue()).isEqualTo(3);
        assertThat(entry.get("lastActivityAt")).isEqualTo(entry.get("lastManagedAt"));
    }

    /** 真实技能工具成功后应各记录一次查看或管理活动。 */
    @Test
    void shouldTrackSuccessfulSkillToolActionsOnce() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SkillTools tools =
                new SkillTools(
                        env.localSkillService,
                        null,
                        env.sessionRepository,
                        "MEMORY:skill-usage:user");

        tools.skillManage(
                "create",
                "tracked-tool-skill",
                null,
                "---\nname: tracked-tool-skill\ndescription: test\n---\n\n# Test\n",
                null,
                null,
                null,
                null);
        tools.skillView("tracked-tool-skill", null);

        java.util.Map<String, Object> entry =
                new SkillUsageTracker(env.appConfig).getEntry("tracked-tool-skill");
        assertThat(((Number) entry.get("manageCount")).intValue()).isEqualTo(1);
        assertThat(((Number) entry.get("loadCount")).intValue()).isEqualTo(1);
        assertThat(((Number) entry.get("callCount")).intValue()).isEqualTo(1);
        assertThat(((Number) entry.get("count")).intValue()).isEqualTo(3);
    }

    /** skill_view 必须把真实调用会话锚定到工具执行前的消息边界。 */
    @Test
    void shouldAnchorSkillViewEvidenceToCurrentSessionMessages() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:skill-evidence:user";
        SessionRecord session = env.sessionRepository.bindNewSession(sourceKey);
        session.setNdjson(
                MessageSupport.toNdjson(
                        java.util.Collections.singletonList(ChatMessage.ofUser("请加载技能"))));
        env.sessionRepository.save(session);
        SkillTools tools =
                new SkillTools(env.localSkillService, null, env.sessionRepository, sourceKey);
        tools.skillManage(
                "create",
                "anchored-tool-skill",
                null,
                "---\nname: anchored-tool-skill\ndescription: test\n---\n\n# Test\n",
                null,
                null,
                null,
                null);

        tools.skillView("anchored-tool-skill", null);

        Map<String, Object> entry =
                new SkillUsageTracker(env.appConfig).getEntry("anchored-tool-skill");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence =
                (List<Map<String, Object>>) entry.get("recentSessionEvidence");
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).get("sessionId")).isEqualTo(session.getSessionId());
        assertThat(((Number) evidence.get(0).get("messageCount")).intValue()).isEqualTo(1);
    }

    @Test
    void shouldTrackStateTransitions() throws Exception {
        File tempDir = Files.createTempDirectory("skill-usage-state").toFile();
        AppConfig config = loadConfig(tempDir);
        SkillUsageTracker tracker = new SkillUsageTracker(config);

        assertThat(tracker.getState("new-skill")).isEqualTo("active");

        tracker.markState("new-skill", SkillUsageTracker.STATE_STALE);
        assertThat(tracker.getState("new-skill")).isEqualTo("stale");

        tracker.markState("new-skill", SkillUsageTracker.STATE_ARCHIVED);
        assertThat(tracker.getState("new-skill")).isEqualTo("archived");
    }

    @Test
    void shouldSupportPinAndUnpin() throws Exception {
        File tempDir = Files.createTempDirectory("skill-usage-pin").toFile();
        AppConfig config = loadConfig(tempDir);
        SkillUsageTracker tracker = new SkillUsageTracker(config);

        assertThat(tracker.isPinned("pinnable")).isFalse();

        tracker.pin("pinnable");
        assertThat(tracker.isPinned("pinnable")).isTrue();

        tracker.unpin("pinnable");
        assertThat(tracker.isPinned("pinnable")).isFalse();
    }

    @Test
    void shouldPersistAcrossInstances() throws Exception {
        File tempDir = Files.createTempDirectory("skill-usage-persist").toFile();
        AppConfig config = loadConfig(tempDir);

        SkillUsageTracker tracker1 = new SkillUsageTracker(config);
        tracker1.bumpInvoke("persistent-skill");
        tracker1.pin("persistent-skill");

        SkillUsageTracker tracker2 = new SkillUsageTracker(config);
        assertThat(tracker2.isPinned("persistent-skill")).isTrue();
        assertThat(((Number) tracker2.getEntry("persistent-skill").get("callCount")).intValue())
                .isEqualTo(1);
    }

    @Test
    void shouldReturnLastActivityTimestamp() throws Exception {
        File tempDir = Files.createTempDirectory("skill-usage-activity").toFile();
        AppConfig config = loadConfig(tempDir);
        SkillUsageTracker tracker = new SkillUsageTracker(config);

        long before = System.currentTimeMillis();
        tracker.bumpInvoke("active-skill");
        long after = System.currentTimeMillis();

        long lastActivity = tracker.getLastActivityAt("active-skill");
        assertThat(lastActivity).isBetween(before, after);
    }

    /** 多个 Tracker 实例必须通过共享状态锁保留全部并发计数。 */
    @Test
    void shouldKeepConcurrentUpdatesFromSeparateTrackers() throws Exception {
        File tempDir = Files.createTempDirectory("skill-usage-concurrent").toFile();
        AppConfig config = loadConfig(tempDir);
        SkillUsageTracker first = new SkillUsageTracker(config);
        SkillUsageTracker second = new SkillUsageTracker(config);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstTask =
                    executor.submit(
                            () -> {
                                await(start);
                                for (int i = 0; i < 100; i++) {
                                    first.bumpView("shared-skill");
                                }
                            });
            Future<?> secondTask =
                    executor.submit(
                            () -> {
                                await(start);
                                for (int i = 0; i < 100; i++) {
                                    second.bumpInvoke("shared-skill");
                                }
                            });
            start.countDown();
            firstTask.get(10, TimeUnit.SECONDS);
            secondTask.get(10, TimeUnit.SECONDS);
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        java.util.Map<String, Object> entry = first.getEntry("shared-skill");
        assertThat(((Number) entry.get("loadCount")).intValue()).isEqualTo(100);
        assertThat(((Number) entry.get("callCount")).intValue()).isEqualTo(100);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for shared state test", e);
        }
    }

    private static AppConfig loadConfig(File workspaceHome) {
        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        return AppConfig.load(props);
    }
}
