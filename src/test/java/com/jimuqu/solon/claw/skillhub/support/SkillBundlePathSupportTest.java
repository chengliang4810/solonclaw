package com.jimuqu.solon.claw.skillhub.support;

import static com.jimuqu.solon.claw.support.TestToolSupport.createDirectoryLink;
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
}
