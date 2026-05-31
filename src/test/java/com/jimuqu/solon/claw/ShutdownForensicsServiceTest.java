package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.ShutdownForensicsService;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

public class ShutdownForensicsServiceTest {
    @Test
    void shouldCaptureShutdownContext() throws Exception {
        File tempDir = Files.createTempDirectory("forensics-test").toFile();
        AppConfig config = loadConfig(tempDir);
        ShutdownForensicsService service = new ShutdownForensicsService(config);

        Map<String, Object> snapshot = service.snapshotShutdownContext("SIGTERM");

        assertThat(snapshot).containsKey("timestamp");
        assertThat(snapshot).containsKey("uptimeMs");
        assertThat(snapshot).containsKey("pid");
        assertThat(snapshot).containsKey("memory");
        assertThat(snapshot).containsKey("threads");
        assertThat(snapshot.get("reason")).isEqualTo("SIGTERM");
        assertThat(((Number) snapshot.get("uptimeMs")).longValue()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldPersistAndReadShutdownRecord() throws Exception {
        File tempDir = Files.createTempDirectory("forensics-persist").toFile();
        AppConfig config = loadConfig(tempDir);
        ShutdownForensicsService service = new ShutdownForensicsService(config);

        service.persistShutdownRecord("test-shutdown");

        Map<String, Object> last = service.lastShutdownRecord();
        assertThat(last).isNotNull();
        assertThat(last.get("reason")).isEqualTo("test-shutdown");
        assertThat(last).containsKey("pid");
    }

    @Test
    void shouldPersistLifecycleShutdownRecord() throws Exception {
        File tempDir = Files.createTempDirectory("forensics-lifecycle").toFile();
        AppConfig config = loadConfig(tempDir);
        ShutdownForensicsService service = new ShutdownForensicsService(config);

        service.persistLifecycleShutdownRecord();

        Map<String, Object> last = service.lastShutdownRecord();
        assertThat(last).isNotNull();
        assertThat(last.get("reason")).isEqualTo("lifecycle_shutdown");
        assertThat(service.lastShutdownRecordFile()).isNotNull();
    }

    @Test
    void shouldReturnNullWhenNoRecordExists() throws Exception {
        File tempDir = Files.createTempDirectory("forensics-empty").toFile();
        AppConfig config = loadConfig(tempDir);
        ShutdownForensicsService service = new ShutdownForensicsService(config);

        assertThat(service.lastShutdownRecord()).isNull();
        assertThat(service.lastShutdownRecordFile()).isNull();
    }

    private static AppConfig loadConfig(File runtimeHome) {
        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());
        return AppConfig.load(props);
    }
}
