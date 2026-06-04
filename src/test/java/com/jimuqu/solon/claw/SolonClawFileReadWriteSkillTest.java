package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SolonClawFileReadWriteSkill;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

public class SolonClawFileReadWriteSkillTest {
    @Test
    void shouldBlockRuntimeCredentialCacheRead() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-file-read-safety-test");
        Files.createDirectories(dir.resolve("cache"));
        Files.write(dir.resolve("cache/bws_cache.json"), "secret".getBytes(StandardCharsets.UTF_8));
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(dir.toString());
        SolonClawFileReadWriteSkill skill =
                new SolonClawFileReadWriteSkill(dir.toString(), new SecurityPolicyService(config));

        assertThatThrownBy(() -> skill.read("cache/bws_cache.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("读取敏感系统/凭据文件被阻断")
                .hasMessageNotContaining("secret");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void shouldWriteByAtomicFileSwap() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-file-write-test");
        Path file = dir.resolve("target.txt");
        Files.write(file, "before\n".getBytes(StandardCharsets.UTF_8));
        Object beforeKey = Files.getAttribute(file, "unix:ino");

        SolonClawFileReadWriteSkill skill = new SolonClawFileReadWriteSkill(dir.toString(), null);
        String result = skill.write("target.txt", "after\n");

        assertThat(result).contains("\"success\":true");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("after\n");
        assertThat(Files.getAttribute(file, "unix:ino")).isNotEqualTo(beforeKey);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(
                            files.filter(
                                            path ->
                                                    path.getFileName()
                                                            .toString()
                                                            .contains(".solonclaw-tmp"))
                                    .count())
                    .isZero();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void shouldPreserveExistingFilePermissions() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-file-write-test");
        Path file = dir.resolve("mode.txt");
        Files.write(file, "before\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(
                file,
                java.util.EnumSet.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_READ));

        SolonClawFileReadWriteSkill skill = new SolonClawFileReadWriteSkill(dir.toString(), null);
        String result = skill.write("mode.txt", "after\n");

        assertThat(result).contains("\"success\":true");
        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_READ);
    }
}
