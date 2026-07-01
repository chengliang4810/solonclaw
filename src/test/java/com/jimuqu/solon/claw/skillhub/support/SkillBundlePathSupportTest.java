package com.jimuqu.solon.claw.skillhub.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillBundlePathSupportTest {
    @Test
    void shouldRedactUnsafeBundlePathMessages() {
        String unsafe = "C:/Users/chengliang/.ssh/token=ghp_skillbundle12345/secret.txt";

        assertThatThrownBy(() -> SkillBundlePathSupport.normalizeBundlePath(unsafe))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe bundle path")
                .hasMessageContaining("secret.txt")
                .hasMessageNotContaining("C:/Users/chengliang")
                .hasMessageNotContaining(".ssh")
                .hasMessageNotContaining("ghp_skillbundle12345");
    }

    @Test
    void shouldRedactUnsafeSkillNameMessages() {
        String unsafe = "../private-token=ghp_skillname12345";

        assertThatThrownBy(() -> SkillBundlePathSupport.normalizeSkillName(unsafe))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe skill name")
                .hasMessageNotContaining("..")
                .hasMessageNotContaining("ghp_skillname12345");
    }

    @Test
    void shouldRejectUnsafeHubTargetPaths(@TempDir Path tempDir) {
        String[] unsafe =
                new String[] {
                    "",
                    " ",
                    ".",
                    "./x",
                    "../x",
                    "/tmp/x",
                    tempDir.resolve("outside").toAbsolutePath().toString()
                };

        for (String value : unsafe) {
            assertThatThrownBy(
                            () -> SkillBundlePathSupport.resolveUnderRoot(tempDir.toFile(), value))
                    .as("path=%s", value)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unsafe bundle path");
        }
    }

    @Test
    void shouldAcceptSafeRelativeSlugUnderRoot(@TempDir Path tempDir) throws Exception {
        File target =
                SkillBundlePathSupport.resolveUnderRoot(tempDir.toFile(), "category/safe-skill");

        assertThat(target.getCanonicalPath()).startsWith(tempDir.toFile().getCanonicalPath());
        assertThat(target.getPath().replace(File.separatorChar, '/'))
                .endsWith("category/safe-skill");
    }

    @Test
    void shouldRejectSymlinkEscapedHubTargets(@TempDir Path tempDir) throws Exception {
        Path outside = Files.createTempDirectory("skillhub-outside");
        Path link = tempDir.resolve("linked-outside");
        assumeTrue(createDirectoryLink(link, outside));
        Files.write(
                outside.resolve("payload.txt"),
                java.util.Arrays.asList("x"),
                StandardCharsets.UTF_8);

        assertThatThrownBy(
                        () ->
                                SkillBundlePathSupport.resolveUnderRoot(
                                        tempDir.toFile(), "linked-outside/payload.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe bundle path");
    }

    /** 创建目录链接，用于在支持链接能力的平台上验证 hub 目录逃逸防护。 */
    private boolean createDirectoryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception ignored) {
            if (!System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("win")) {
                return false;
            }
            try {
                Process process =
                        new ProcessBuilder(
                                        "cmd",
                                        "/c",
                                        "mklink",
                                        "/J",
                                        link.toString(),
                                        target.toString())
                                .redirectErrorStream(true)
                                .start();
                return process.waitFor() == 0 && Files.exists(link);
            } catch (Exception ignoredAgain) {
                return false;
            }
        }
    }
}
