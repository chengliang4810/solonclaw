package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeStatusService;
import com.jimuqu.solon.claw.support.RuntimeProcessSupport;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;

public class GatewayRuntimeStatusServiceTest {
    @Test
    void shouldWritePidFileAsGatewayRuntimeMetadata(@TempDir Path workspaceHome) {
        GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(config(workspaceHome));

        service.writePidFile();

        Map<String, Object> payload = readMap(workspaceHome.resolve("gateway.pid").toFile());
        assertThat(payload).containsEntry("kind", "solonclaw-gateway");
        assertThat(asLong(payload.get("pid"))).isEqualTo(currentPid());
        assertThat(asLong(payload.get("startTime"))).isEqualTo(currentJvmStartTime());
        assertThat(payload.get("startInstant")).isInstanceOf(String.class);
        assertThat((String) payload.get("startInstant")).isNotBlank();
        assertThat(payload.get("command")).isInstanceOf(String.class);
        assertThat((String) payload.get("command")).isNotBlank();
    }

    @Test
    void shouldTreatMatchingJsonPidMetadataAsRunning(@TempDir Path workspaceHome) {
        GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(config(workspaceHome));
        service.writePidFile();

        assertThat(service.isRunning()).isTrue();
    }

    @Test
    void shouldRejectNumericPidFileWithoutGatewayMetadata(@TempDir Path workspaceHome) {
        File pidFile = workspaceHome.resolve("gateway.pid").toFile();
        FileUtil.writeString(String.valueOf(currentPid()), pidFile, StandardCharsets.UTF_8);
        GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(config(workspaceHome));

        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void shouldRejectJsonPidMetadataWhenCurrentJvmStartTimeDoesNotMatch(@TempDir Path workspaceHome) {
        File pidFile = workspaceHome.resolve("gateway.pid").toFile();
        FileUtil.writeString(
                "{"
                        + "\"kind\":\"solonclaw-gateway\","
                        + "\"pid\":"
                        + currentPid()
                        + ","
                        + "\"startTime\":"
                        + (currentJvmStartTime() - 1000L)
                        + ","
                        + "\"startInstant\":\"1970-01-01T00:00:00Z\","
                        + "\"command\":\""
                        + System.getProperty("sun.java.command", "java")
                        + "\""
                        + "}",
                pidFile,
                StandardCharsets.UTF_8);
        GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(config(workspaceHome));

        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void shouldNotRemovePidFileOwnedByDifferentGatewayInstance(@TempDir Path workspaceHome) {
        File pidFile = workspaceHome.resolve("gateway.pid").toFile();
        FileUtil.writeString(
                "{"
                        + "\"kind\":\"solonclaw-gateway\","
                        + "\"pid\":"
                        + currentPid()
                        + ","
                        + "\"startTime\":"
                        + (currentJvmStartTime() + 1000L)
                        + ","
                        + "\"command\":\"solonclaw\""
                        + "}",
                pidFile,
                StandardCharsets.UTF_8);
        GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(config(workspaceHome));

        service.removePidFile();

        assertThat(pidFile).exists();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(File file) {
        Object parsed =
                ONode.deserialize(FileUtil.readString(file, StandardCharsets.UTF_8), Object.class);
        assertThat(parsed).isInstanceOf(Map.class);
        return (Map<String, Object>) parsed;
    }

    private AppConfig config(Path workspaceHome) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.toFile().getAbsolutePath());
        return config;
    }

    private long currentPid() {
        return RuntimeProcessSupport.currentPidOrUnknown();
    }

    private long currentJvmStartTime() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

}
