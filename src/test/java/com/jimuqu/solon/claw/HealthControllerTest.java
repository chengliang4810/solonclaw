package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.bootstrap.HealthController;
import java.util.List;
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

        assertThat(response)
                .containsEntry("ok", Boolean.TRUE)
                .containsEntry("status", "ok")
                .containsEntry("platform", "solon-claw")
                .containsKeys("gateway_state", "platforms", "active_agents", "pid", "updated_at", "gateway");
        assertThat(response.get("service")).isInstanceOf(Map.class);
        assertThat(response.get("runtime")).isInstanceOf(Map.class);
        assertThat(response.get("gateway_state")).isNull();
        assertThat(response.get("platforms")).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) response.get("platforms")).isEmpty();
        assertThat(response.get("active_agents")).isEqualTo(Integer.valueOf(0));
        assertThat(response.get("pid")).isInstanceOf(Integer.class);
        assertThat(response.get("updated_at")).isInstanceOf(String.class);
        assertThat(response.get("gateway")).isInstanceOf(Map.class);
        assertThat(response.get("runtime_capabilities")).isInstanceOf(Map.class);
        assertThat(response.get("runtime_status")).isInstanceOf(Map.class);

        Map<String, Object> service = (Map<String, Object>) response.get("service");
        Map<String, Object> runtime = (Map<String, Object>) response.get("runtime");
        Map<String, Object> gateway = (Map<String, Object>) response.get("gateway");
        Map<String, Object> capabilities =
                (Map<String, Object>) response.get("runtime_capabilities");
        Map<String, Object> runtimeStatus = (Map<String, Object>) response.get("runtime_status");

        assertThat(service)
                .containsEntry("name", "solon-claw")
                .containsEntry("status", "up");
        assertThat(runtime)
                .containsKeys("startedAtEpochMs", "currentTimeEpochMs", "uptimeMs", "uptimeSeconds")
                .containsEntry("pid", response.get("pid"))
                .containsEntry("updated_at", response.get("updated_at"));
        assertThat(runtime.get("gateway")).isEqualTo(gateway);
        assertThat(runtime.get("capabilities")).isEqualTo(capabilities);
        assertThat(runtime.get("status_snapshot")).isEqualTo(runtimeStatus);
        assertThat(gateway)
                .containsEntry("state", null)
                .containsEntry("running", Boolean.FALSE)
                .containsEntry("active_agents", Integer.valueOf(0));
        assertThat(capabilities)
                .containsEntry("schema_version", Integer.valueOf(1))
                .containsEntry("service", "solon-claw")
                .containsEntry("dashboard_first", Boolean.TRUE);
        assertThat((List<String>) capabilities.get("supported_model_protocols"))
                .containsExactly("openai", "openai-responses", "ollama", "gemini", "anthropic");
        assertThat((List<String>) capabilities.get("supported_channels"))
                .containsExactly("feishu", "dingtalk", "wecom", "weixin", "qqbot", "yuanbao");
        assertThat(runtimeStatus)
                .containsEntry("schema_version", Integer.valueOf(1))
                .containsEntry("service", "solon-claw")
                .containsEntry("status", "ok")
                .containsEntry("active_sessions", Integer.valueOf(0))
                .containsKeys("gateway", "diagnostics");

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
