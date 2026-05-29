package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

public class RuntimePathGuardTest {
    @Test
    void shouldRedactRejectedMediaPathAndAllowedRoot() throws Exception {
        File runtimeHome = Files.createTempDirectory("runtime-path-guard-home").toFile();
        File outside = Files.createTempDirectory("runtime-path-token=ghp_pathguard12345").toFile();
        AppConfig config = loadConfig(runtimeHome);
        RuntimePathGuard guard = new RuntimePathGuard(config);
        File candidate = new File(outside, "credentials.json");

        assertThatThrownBy(() -> guard.requireUnderMedia(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path is outside allowed root")
                .hasMessageContaining("[REDACTED_PATH]")
                .hasMessageNotContaining(runtimeHome.getAbsolutePath())
                .hasMessageNotContaining(outside.getAbsolutePath())
                .hasMessageNotContaining("ghp_pathguard12345")
                .hasMessageNotContaining("credentials.json");
    }

    @Test
    void shouldRedactRejectedToolPathAndAllowedRoots() throws Exception {
        File runtimeHome = Files.createTempDirectory("runtime-path-guard-tool-home").toFile();
        File outside = Files.createTempDirectory("runtime-path-tool-token=ghp_toolpath12345").toFile();
        AppConfig config = loadConfig(runtimeHome);
        RuntimePathGuard guard = new RuntimePathGuard(config);
        File candidate = new File(outside, "private_key.pem");

        assertThatThrownBy(() -> guard.requireAllowedToolPath(candidate.getAbsolutePath()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path is outside allowed roots")
                .hasMessageContaining("private_key.pem")
                .hasMessageNotContaining(runtimeHome.getAbsolutePath())
                .hasMessageNotContaining(outside.getAbsolutePath())
                .hasMessageNotContaining("ghp_toolpath12345");
    }

    @Test
    void shouldRedactInvalidCanonicalPathMessage() throws Exception {
        File runtimeHome = Files.createTempDirectory("runtime-path-guard-invalid-home").toFile();
        RuntimePathGuard guard = new RuntimePathGuard(loadConfig(runtimeHome));
        File invalid =
                new File(
                        runtimeHome,
                        "media/token=ghp_pathguardinvalid12345/\u0000/secret.txt");

        assertThatThrownBy(() -> guard.requireUnderMedia(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid path")
                .hasMessageContaining("secret.txt")
                .hasMessageNotContaining(runtimeHome.getAbsolutePath())
                .hasMessageNotContaining("ghp_pathguardinvalid12345");
    }

    private static AppConfig loadConfig(File runtimeHome) {
        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());
        return AppConfig.load(props);
    }
}
