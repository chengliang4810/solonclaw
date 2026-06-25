package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.SkillUsageTracker;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

public class SkillUsageTrackerTest {
    @Test
    void shouldTrackViewAndInvokeCounters() throws Exception {
        File tempDir = Files.createTempDirectory("skill-usage-test").toFile();
        AppConfig config = loadConfig(tempDir);
        SkillUsageTracker tracker = new SkillUsageTracker(config);

        tracker.bumpView("my-skill");
        tracker.bumpView("my-skill");
        tracker.bumpInvoke("my-skill");

        java.util.Map<String, Object> entry = tracker.getEntry("my-skill");
        assertThat(((Number) entry.get("viewCount")).intValue()).isEqualTo(2);
        assertThat(((Number) entry.get("invokeCount")).intValue()).isEqualTo(1);
        assertThat(entry.get("lastViewedAt")).isNotNull();
        assertThat(entry.get("lastInvokedAt")).isNotNull();
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
        assertThat(((Number) tracker2.getEntry("persistent-skill").get("invokeCount")).intValue())
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

    private static AppConfig loadConfig(File workspaceHome) {
        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        return AppConfig.load(props);
    }
}
