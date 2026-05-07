package com.jimuqu.solon.claw.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Files;
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
}
