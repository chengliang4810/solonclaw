package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.support.RuntimeMemoryMonitorService;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class RuntimeMemoryMonitorServiceTest {
    @Test
    void shouldRecordBaselineAndLatestSnapshotsAcrossLifecycle() {
        RuntimeMemoryMonitorService service = new RuntimeMemoryMonitorService();

        assertThat(service.status().get("enabled")).isEqualTo(Boolean.FALSE);
        assertThat(service.status().get("baseline")).isNull();
        assertThat(service.status().get("latest")).isNull();

        service.start();
        service.captureSnapshot("periodic");
        service.shutdown();

        Map<String, Object> status = service.status();
        assertThat(status.get("enabled")).isEqualTo(Boolean.TRUE);
        assertThat(status.get("running")).isEqualTo(Boolean.FALSE);
        assertThat(status.get("baseline")).isInstanceOf(Map.class);
        assertThat(status.get("latest")).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> baseline = (Map<String, Object>) status.get("baseline");
        @SuppressWarnings("unchecked")
        Map<String, Object> latest = (Map<String, Object>) status.get("latest");

        assertSnapshotFields(baseline, "baseline");
        assertSnapshotFields(latest, "shutdown");
        assertThat(((Number) latest.get("timestamp")).longValue())
                .isGreaterThanOrEqualTo(((Number) baseline.get("timestamp")).longValue());
    }

    private void assertSnapshotFields(Map<String, Object> snapshot, String tag) {
        assertThat(snapshot.get("tag")).isEqualTo(tag);
        assertThat(snapshot.get("used_mb")).isInstanceOf(Number.class);
        assertThat(snapshot.get("max_mb")).isInstanceOf(Number.class);
        assertThat(snapshot.get("free_mb")).isInstanceOf(Number.class);
        assertThat(snapshot.get("thread_count")).isInstanceOf(Number.class);
        assertThat(snapshot.get("uptime_ms")).isInstanceOf(Number.class);
        assertThat(snapshot.get("timestamp")).isInstanceOf(Number.class);
        assertThat(snapshot.get("timestamp_iso")).isInstanceOf(String.class);
    }
}
