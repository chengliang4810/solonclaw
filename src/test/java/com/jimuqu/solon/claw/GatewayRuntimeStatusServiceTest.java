package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeStatusService;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;

public class GatewayRuntimeStatusServiceTest {
    @Test
    void shouldWritePidFileAsGatewayRuntimeMetadata(@TempDir Path runtimeHome) {
        GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(config(runtimeHome));

        service.writePidFile();

        Map<String, Object> payload = readMap(runtimeHome.resolve("gateway.pid").toFile());
        assertThat(payload).containsEntry("kind", "solon-claw-gateway");
        assertThat(asLong(payload.get("pid"))).isEqualTo(currentPid());
        assertThat(asLong(payload.get("startTime"))).isEqualTo(currentJvmStartTime());
        assertThat(payload.get("startInstant")).isInstanceOf(String.class);
        assertThat((String) payload.get("startInstant")).isNotBlank();
        assertThat(payload.get("command")).isInstanceOf(String.class);
        assertThat((String) payload.get("command")).isNotBlank();
    }

    @Test
    void shouldTreatMatchingJsonPidMetadataAsRunning(@TempDir Path runtimeHome) {
        GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(config(runtimeHome));
        service.writePidFile();

        assertThat(service.isRunning()).isTrue();
    }

    @Test
    void shouldRejectNumericPidFileWithoutGatewayMetadata(@TempDir Path runtimeHome) {
        File pidFile = runtimeHome.resolve("gateway.pid").toFile();
        FileUtil.writeString(String.valueOf(currentPid()), pidFile, StandardCharsets.UTF_8);
        GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(config(runtimeHome));

        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void shouldRejectJsonPidMetadataWhenCurrentJvmStartTimeDoesNotMatch(@TempDir Path runtimeHome) {
        File pidFile = runtimeHome.resolve("gateway.pid").toFile();
        FileUtil.writeString(
                "{"
                        + "\"kind\":\"solon-claw-gateway\","
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
        GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(config(runtimeHome));

        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void shouldNotRemovePidFileOwnedByDifferentGatewayInstance(@TempDir Path runtimeHome) {
        File pidFile = runtimeHome.resolve("gateway.pid").toFile();
        FileUtil.writeString(
                "{"
                        + "\"kind\":\"solon-claw-gateway\","
                        + "\"pid\":"
                        + currentPid()
                        + ","
                        + "\"startTime\":"
                        + (currentJvmStartTime() + 1000L)
                        + ","
                        + "\"command\":\"solon-claw\""
                        + "}",
                pidFile,
                StandardCharsets.UTF_8);
        GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(config(runtimeHome));

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

    private AppConfig config(Path runtimeHome) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.toFile().getAbsolutePath());
        return config;
    }

    private long currentPid() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int separator = runtimeName.indexOf('@');
        String pid = separator > 0 ? runtimeName.substring(0, separator) : runtimeName;
        return Long.parseLong(pid);
    }

    private long currentJvmStartTime() {
        return ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

}
