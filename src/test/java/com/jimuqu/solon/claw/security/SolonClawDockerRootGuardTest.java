package com.jimuqu.solon.claw.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class SolonClawDockerRootGuardTest {
    @Test
    void shouldRefuseOfficialDockerRootServerStart() throws Exception {
        File entrypoint = Files.createTempFile("solonclaw-entrypoint", ".sh").toFile();

        assertThatThrownBy(
                        () ->
                                SolonClawDockerRootGuard.requireServerMayStart(
                                        "1", "", Integer.valueOf(0), "solonclaw", entrypoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to run the SolonClaw gateway as root")
                .hasMessageContaining("/app/docker-entrypoint.sh")
                .hasMessageContaining("SOLONCLAW_ALLOW_ROOT_GATEWAY=1");
    }

    @Test
    void shouldAllowOfficialDockerRootWhenEscapeHatchIsExplicit() throws Exception {
        File entrypoint = Files.createTempFile("solonclaw-entrypoint", ".sh").toFile();

        assertThatCode(
                        () ->
                                SolonClawDockerRootGuard.requireServerMayStart(
                                        "true", "1", Integer.valueOf(0), "root", entrypoint))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowOfficialDockerNonRootServerStart() throws Exception {
        File entrypoint = Files.createTempFile("solonclaw-entrypoint", ".sh").toFile();

        assertThatCode(
                        () ->
                                SolonClawDockerRootGuard.requireServerMayStart(
                                        "yes", "", Integer.valueOf(10000), "root", entrypoint))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldIgnoreNonOfficialRuntimeEvenWhenCurrentUserIsRoot() throws Exception {
        File entrypoint = Files.createTempFile("solonclaw-entrypoint", ".sh").toFile();

        assertThatCode(
                        () ->
                                SolonClawDockerRootGuard.requireServerMayStart(
                                        "", "", Integer.valueOf(0), "root", entrypoint))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFallbackToUserNameWhenUidIsUnavailable() throws Exception {
        File entrypoint = Files.createTempFile("solonclaw-entrypoint", ".sh").toFile();

        assertThatThrownBy(
                        () ->
                                SolonClawDockerRootGuard.requireServerMayStart(
                                        "1", "", null, "root", entrypoint))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(
                        () ->
                                SolonClawDockerRootGuard.requireServerMayStart(
                                        "1", "", null, "solonclaw", entrypoint))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldResolveSolonClawUidGidInDockerEntrypoint() throws Exception {
        Path entrypoint = new File("docker/entrypoint.sh").toPath();
        List<String> lines = Files.readAllLines(entrypoint, StandardCharsets.UTF_8);

        assertThat(resolveUidGid(lines, env("SOLONCLAW_UID", "2000", "SOLONCLAW_GID", "2001")))
                .isEqualTo("2000:2001");
        assertThat(resolveUidGid(lines, env("PUID", "1000", "PGID", "10")))
                .isEqualTo("10000:10000");
        assertThat(resolveUidGid(lines, env())).isEqualTo("10000:10000");
    }

    @Test
    void shouldExposeOnlySolonClawUidGidInDockerCompose() throws Exception {
        String compose =
                new String(
                        Files.readAllBytes(new File("docker-compose.yml").toPath()),
                        StandardCharsets.UTF_8);

        assertThat(compose).contains("SOLONCLAW_UID:").contains("${SOLONCLAW_UID:-10000}");
        assertThat(compose).contains("SOLONCLAW_GID:").contains("${SOLONCLAW_GID:-10000}");
        assertThat(compose).doesNotContain("PUID:").doesNotContain("PGID:");
    }

    private static String resolveUidGid(List<String> entrypointLines, Map<String, String> env)
            throws Exception {
        StringBuilder script = new StringBuilder();
        for (String line : entrypointLines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("APP_UID=") || trimmed.startsWith("APP_GID=")) {
                script.append(trimmed).append('\n');
            }
        }
        script.append("printf '%s:%s' \"$APP_UID\" \"$APP_GID\"\n");
        ProcessBuilder builder = new ProcessBuilder("bash", "-ec", script.toString());
        Map<String, String> processEnv = builder.environment();
        processEnv.remove("SOLONCLAW_UID");
        processEnv.remove("SOLONCLAW_GID");
        processEnv.remove("PUID");
        processEnv.remove("PGID");
        processEnv.putAll(env);
        Process process = builder.start();
        byte[] stdout = readAll(process.getInputStream());
        byte[] stderr = readAll(process.getErrorStream());
        int exit = process.waitFor();
        assertThat(exit).as(new String(stderr, StandardCharsets.UTF_8)).isEqualTo(0);
        return new String(stdout, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(java.io.InputStream inputStream) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = inputStream.read(buffer)) >= 0) {
            output.write(buffer, 0, len);
        }
        return output.toByteArray();
    }

    private static Map<String, String> env(String... values) {
        Map<String, String> env = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            env.put(values[i], values[i + 1]);
        }
        return env;
    }
}
