package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.bootstrap.HealthController;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class HealthControllerTest {
    @Test
    void shouldKeepLegacyHealthPayloadCompatible() {
        HealthController controller = new HealthController();

        Map<String, Object> response = controller.health();

        assertThat(response)
                .containsEntry("ok", Boolean.TRUE)
                .containsEntry("service", "solon-claw")
                .hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeDetailedRuntimeSummary() {
        HealthController controller = new HealthController();

        Map<String, Object> response = controller.detailedHealth();

        assertThat(response).containsEntry("ok", Boolean.TRUE);
        assertThat(response.get("service")).isInstanceOf(Map.class);
        assertThat(response.get("runtime")).isInstanceOf(Map.class);

        Map<String, Object> service = (Map<String, Object>) response.get("service");
        Map<String, Object> runtime = (Map<String, Object>) response.get("runtime");

        assertThat(service)
                .containsEntry("name", "solon-claw")
                .containsEntry("status", "up");
        assertThat(runtime).containsKeys("startedAtEpochMs", "currentTimeEpochMs", "uptimeMs", "uptimeSeconds");

        long startedAt = ((Number) runtime.get("startedAtEpochMs")).longValue();
        long currentTime = ((Number) runtime.get("currentTimeEpochMs")).longValue();
        long uptimeMs = ((Number) runtime.get("uptimeMs")).longValue();
        long uptimeSeconds = ((Number) runtime.get("uptimeSeconds")).longValue();

        assertThat(startedAt).isPositive();
        assertThat(currentTime).isGreaterThanOrEqualTo(startedAt);
        assertThat(uptimeMs).isGreaterThanOrEqualTo(0L);
        assertThat(uptimeSeconds).isBetween(0L, uptimeMs / 1000L);
    }
}
