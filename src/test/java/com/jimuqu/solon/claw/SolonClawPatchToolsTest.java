package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SolonClawPatchTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class SolonClawPatchToolsTest {
    @Test
    void shouldExposePatchParserPolicySummary() {
        Map<String, Object> summary = SolonClawPatchTools.patchParserPolicySummary();

        assertThat(summary.get("enabled")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("toolName")).isEqualTo("patch");
        assertThat(summary.get("atomicValidationBeforeWrite")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("noPartialWritesOnValidationFailure")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("replaceRequiresUniqueMatchByDefault")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("pathTraversalBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("credentialPolicyPrechecked")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(summary))
                .contains("replace")
                .contains("V4A")
                .contains("update")
                .contains("moveTo")
                .contains("symlinkEscapeBlocked")
                .contains("staleFileWarnings");
    }

    @Test
    void shouldReplaceUniqueString() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("src/app.txt");
        Files.createDirectories(file.getParent());
        Files.write(file, "hello\nworld\n".getBytes(StandardCharsets.UTF_8));

        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String json = tools.patch("replace", "src/app.txt", "hello", "hi", false, null);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(result.get("files_modified"))).contains("src/app.txt");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("hi\nworld\n");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void shouldReplaceByAtomicFileSwap() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("target.txt");
        Files.write(file, "before\n".getBytes(StandardCharsets.UTF_8));
        Object beforeKey = Files.getAttribute(file, "unix:ino");

        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String json = tools.patch("replace", "target.txt", "before", "after", false, null);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
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
    void shouldReportResolvedAbsolutePathForReplaceMode() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("target.txt");
        Files.write(file, "before\n".getBytes(StandardCharsets.UTF_8));

        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String json = tools.patch("replace", "target.txt", "before", "after", false, null);

        Map<?, ?> result = parse(json);
        String expected = file.toRealPath().toString();
        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("resolved_path")).isEqualTo(expected);
        assertThat(String.valueOf(result.get("files_modified"))).isEqualTo("[" + expected + "]");
        assertThat(String.valueOf(result.get("diff")))
                .contains("--- a/target.txt")
                .contains("+++ b/target.txt");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("after\n");
    }

    @Test
    void shouldReportResolvedAbsolutePathForSingleFileV4aUpdate() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("app.txt");
        Files.write(file, "alpha\nbeta\n".getBytes(StandardCharsets.UTF_8));
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: app.txt\n"
                        + "@@ beta @@\n"
                        + " alpha\n"
                        + "-beta\n"
                        + "+gamma\n"
                        + "*** End Patch";

        Map<?, ?> result = parse(tools.patch("patch", null, null, null, null, patch));
        String expected = file.toRealPath().toString();

        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("resolved_path")).isEqualTo(expected);
        assertThat(String.valueOf(result.get("files_modified"))).isEqualTo("[" + expected + "]");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("alpha\ngamma\n");
    }

    @Test
    void shouldRejectAmbiguousReplaceUnlessReplaceAll() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("dup.txt");
        Files.write(file, "x\nx\n".getBytes(StandardCharsets.UTF_8));

        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String json = tools.patch("replace", "dup.txt", "x", "y", false, null);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error"))).contains("Found 2 matches");
    }

    @Test
    void shouldApplyV4aPatchAtomicallyAfterValidation() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("app.txt");
        Files.write(file, "alpha\nbeta\n".getBytes(StandardCharsets.UTF_8));

        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: app.txt\n"
                        + "@@ beta @@\n"
                        + " alpha\n"
                        + "-beta\n"
                        + "+gamma\n"
                        + "*** Add File: new.txt\n"
                        + "+created\n"
                        + "*** End Patch";
        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(result.get("diff")))
                .contains("--- /dev/null")
                .contains("+++ b/new.txt")
                .contains("+created");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("alpha\ngamma\n");
        assertThat(new String(Files.readAllBytes(dir.resolve("new.txt")), StandardCharsets.UTF_8))
                .isEqualTo("created");
    }

    @Test
    void shouldUseDevNullDiffForDeletedFilesLikeJimuqu() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("old.txt");
        Files.write(file, "obsolete\n".getBytes(StandardCharsets.UTF_8));
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String patch =
                "*** Begin Patch\n"
                        + "*** Delete File: old.txt\n"
                        + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(result.get("diff")))
                .contains("--- a/old.txt")
                .contains("+++ /dev/null")
                .contains("-obsolete");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void shouldRejectAdditionOnlyHunkWhenContextHintIsMissingWithoutWriting() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("app.txt");
        Files.write(file, "def main():\n    pass\n".getBytes(StandardCharsets.UTF_8));
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: app.txt\n"
                        + "@@ def missing @@\n"
                        + "+def helper():\n"
                        + "+    return 42\n"
                        + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error")))
                .contains("Patch validation failed")
                .contains("context hint 'def missing' not found");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("def main():\n    pass\n");
    }

    @Test
    void shouldRejectAdditionOnlyHunkWhenContextHintIsAmbiguousWithoutWriting() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("app.txt");
        Files.write(file, "marker\none\nmarker\ntwo\n".getBytes(StandardCharsets.UTF_8));
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: app.txt\n"
                        + "@@ marker @@\n"
                        + "+inserted\n"
                        + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error")))
                .contains("Patch validation failed")
                .contains("context hint 'marker' is ambiguous");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("marker\none\nmarker\ntwo\n");
    }

    @Test
    void shouldRejectTraversalBeforeWriting() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());

        String json = tools.patch("replace", "../outside.txt", "a", "b", false, null);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error"))).contains("越权");
    }

    @Test
    void shouldRejectSymlinkEscapeBeforePatching() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path outside = Files.createTempDirectory("jimuqu-patch-outside");
        Path outsideFile = outside.resolve("secret.txt");
        Files.write(outsideFile, "TOKEN=old\n".getBytes(StandardCharsets.UTF_8));
        Path link = dir.resolve("linked");
        assumeTrue(createDirectoryLink(link, outside));
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());

        String json = tools.patch("replace", "linked/secret.txt", "old", "new", false, null);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error"))).contains("符号链接").contains("沙箱外部");
        assertThat(new String(Files.readAllBytes(outsideFile), StandardCharsets.UTF_8))
                .isEqualTo("TOKEN=old\n");
    }

    @Test
    void shouldBlockCredentialFilesInsidePatchToolItself() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve(".env.production");
        Files.write(file, "TOKEN=old\n".getBytes(StandardCharsets.UTF_8));
        SolonClawPatchTools tools =
                new SolonClawPatchTools(dir.toString(), new SecurityPolicyService(new AppConfig()));

        String json = tools.patch("replace", ".env.production", "old", "new", false, null);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error"))).contains("BLOCKED").contains("敏感");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("TOKEN=old\n");
    }

    @Test
    void shouldBlockCredentialFilesInV4aPatchBeforeWriting() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        SolonClawPatchTools tools =
                new SolonClawPatchTools(dir.toString(), new SecurityPolicyService(new AppConfig()));
        String patch =
                "*** Begin Patch\n"
                        + "*** Add File: .env.local\n"
                        + "+TOKEN=new\n"
                        + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error")))
                .contains("BLOCKED")
                .contains("[REDACTED_PATH]")
                .doesNotContain(".env.local");
        assertThat(Files.exists(dir.resolve(".env.local"))).isFalse();
    }

    @Test
    void shouldBlockCredentialMoveTargetsBeforeWriting() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path source = dir.resolve("source.txt");
        Path template = dir.resolve("template.txt");
        Files.write(source, "alpha\n".getBytes(StandardCharsets.UTF_8));
        Files.write(template, "token\n".getBytes(StandardCharsets.UTF_8));
        SolonClawPatchTools tools =
                new SolonClawPatchTools(dir.toString(), new SecurityPolicyService(new AppConfig()));

        String moveFilePatch =
                "*** Begin Patch\n"
                        + "*** Move File: template.txt -> .env.local\n"
                        + "*** End Patch";
        String updateMovePatch =
                "*** Begin Patch\n"
                        + "*** Update File: source.txt\n"
                        + "*** Move to: .env.production\n"
                        + "@@ alpha @@\n"
                        + "-alpha\n"
                        + "+beta\n"
                        + "*** End Patch";

        Map<?, ?> moveFileResult = parse(tools.patch("patch", null, null, null, null, moveFilePatch));
        Map<?, ?> updateMoveResult =
                parse(tools.patch("patch", null, null, null, null, updateMovePatch));

        assertThat(moveFileResult.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(moveFileResult.get("error")))
                .contains("BLOCKED")
                .contains("[REDACTED_PATH]")
                .doesNotContain(".env.local");
        assertThat(updateMoveResult.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(updateMoveResult.get("error")))
                .contains("BLOCKED")
                .contains("[REDACTED_PATH]")
                .doesNotContain(".env.production");
        assertThat(new String(Files.readAllBytes(source), StandardCharsets.UTF_8))
                .isEqualTo("alpha\n");
        assertThat(Files.exists(dir.resolve("template.txt"))).isTrue();
        assertThat(Files.exists(dir.resolve(".env.local"))).isFalse();
        assertThat(Files.exists(dir.resolve(".env.production"))).isFalse();
    }

    @Test
    void shouldRedactSecretsFromPatchErrors() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());

        String json =
                tools.patch(
                        "replace",
                        "missing-ghp_1234567890abcdef.txt",
                        "old",
                        "new",
                        false,
                        null);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error")))
                .contains("missing-ghp_***")
                .doesNotContain("ghp_1234567890abcdef");
    }

    @Test
    void shouldRedactSensitivePatchErrorPaths() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());

        String replaceJson =
                tools.patch(
                        "replace",
                        "private/token-ghp_patchmissing12345/missing.txt",
                        "old",
                        "new",
                        false,
                        null);
        Map<?, ?> replaceResult = parse(replaceJson);
        assertThat(replaceResult.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(replaceResult.get("error")))
                .contains("Cannot read file")
                .contains("missing.txt")
                .doesNotContain("private/")
                .doesNotContain("ghp_patchmissing12345");

        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: private/token-ghp_patchvalidation12345/missing.txt\n"
                        + "@@ old @@\n"
                        + "-old\n"
                        + "+new\n"
                        + "*** End Patch";
        Map<?, ?> patchResult = parse(tools.patch("patch", null, null, null, null, patch));
        assertThat(patchResult.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(patchResult.get("error")))
                .contains("Patch validation failed")
                .contains("missing.txt")
                .doesNotContain("private/")
                .doesNotContain("ghp_patchvalidation12345");
    }

    @Test
    void shouldRedactSecretsFromPatchSuccessResultsOnly() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("src/secret-ghp_patchpath12345.txt");
        Files.createDirectories(file.getParent());
        Files.write(file, "old\n".getBytes(StandardCharsets.UTF_8));
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());

        String json =
                tools.patch(
                        "replace",
                        "src/secret-ghp_patchpath12345.txt",
                        "old",
                        "Authorization: Bearer ghp_patchdiff12345",
                        false,
                        null);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(result))
                .contains("secret-ghp_***")
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_patchpath12345")
                .doesNotContain("ghp_patchdiff12345");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .contains("ghp_patchdiff12345");
    }

    @Test
    void shouldRejectAddFileWhenTargetExistsWithoutOverwriting() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("existing.txt");
        Files.write(file, "original\n".getBytes(StandardCharsets.UTF_8));
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String patch =
                "*** Begin Patch\n"
                        + "*** Add File: existing.txt\n"
                        + "+replacement\n"
                        + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error")))
                .contains("Patch validation failed")
                .contains("existing.txt")
                .contains("already exists")
                .contains("add would overwrite");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("original\n");
    }

    @Test
    void shouldRejectPatchWithoutEnvelopeBeforeWriting() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("loose.txt");
        Files.write(file, "alpha\n".getBytes(StandardCharsets.UTF_8));
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String patch =
                "*** Update File: loose.txt\n"
                        + "@@ alpha @@\n"
                        + "-alpha\n"
                        + "+beta\n";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error")))
                .contains("patch rejected")
                .contains("Begin Patch")
                .contains("End Patch");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("alpha\n");
    }

    @Test
    void shouldRejectUpdateMoveToExistingTargetWithoutPartialWrites() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path source = dir.resolve("source.txt");
        Path destination = dir.resolve("destination.txt");
        Files.write(source, "alpha\n".getBytes(StandardCharsets.UTF_8));
        Files.write(destination, "destination\n".getBytes(StandardCharsets.UTF_8));
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: source.txt\n"
                        + "*** Move to: destination.txt\n"
                        + "@@ alpha @@\n"
                        + "-alpha\n"
                        + "+updated\n"
                        + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error")))
                .contains("Patch validation failed")
                .contains("destination.txt")
                .contains("destination already exists")
                .contains("move would overwrite");
        assertThat(new String(Files.readAllBytes(source), StandardCharsets.UTF_8))
                .isEqualTo("alpha\n");
        assertThat(new String(Files.readAllBytes(destination), StandardCharsets.UTF_8))
                .isEqualTo("destination\n");
    }

    @Test
    void shouldRejectMoveFileWithoutDestinationWithoutPartialWrites() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path source = dir.resolve("source.txt");
        Files.write(source, "alpha\n".getBytes(StandardCharsets.UTF_8));
        SolonClawPatchTools tools = new SolonClawPatchTools(dir.toString());
        String patch =
                "*** Begin Patch\n"
                        + "*** Move File: source.txt\n"
                        + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error")))
                .contains("Patch validation failed")
                .contains("missing destination path");
        assertThat(new String(Files.readAllBytes(source), StandardCharsets.UTF_8))
                .isEqualTo("alpha\n");
    }

    private Map<?, ?> parse(String json) {
        return ONode.deserialize(json, java.util.LinkedHashMap.class);
    }

    private boolean createDirectoryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception ignored) {
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
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
