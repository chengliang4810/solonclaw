package com.jimuqu.solon.claw;

import static com.jimuqu.solon.claw.support.TestToolSupport.createDirectoryLink;
import static com.jimuqu.solon.claw.support.TestToolSupport.guardedPatchTools;
import static com.jimuqu.solon.claw.support.TestToolSupport.parseJsonMap;
import static com.jimuqu.solon.claw.support.TestToolSupport.patchTools;
import static com.jimuqu.solon.claw.support.TestToolSupport.readUtf8;
import static com.jimuqu.solon.claw.support.TestToolSupport.tempDir;
import static com.jimuqu.solon.claw.support.TestToolSupport.writeUtf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jimuqu.solon.claw.tool.runtime.SolonClawPatchTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

public class SolonClawPatchToolsTest {
    /** 验证工作区 URI、同路径重复操作和多目标补丁共用一次真实工具调用审批作用域。 */
    @Test
    void shouldApplyApprovedWorkspaceUriPatchOnceAcrossRepeatedAndMultipleTargets()
            throws Exception {
        Path boundary = tempDir("patch-workspace-uri-approved");
        Path workspace = boundary.resolve("workspace");
        Path first = boundary.resolve("first.txt").toAbsolutePath();
        Path second = boundary.resolve("second.txt").toAbsolutePath();
        Files.createDirectories(workspace);
        writeUtf8(first, "one=old\ntwo=old\n");
        writeUtf8(second, "three=old\n");
        String firstUri = "workspace://" + first;
        String secondUri = "workspace://" + second;
        SecurityPolicyService.approveFilePolicyForCurrentThread(
                "workspace_outside_write", firstUri);
        SecurityPolicyService.approveFilePolicyForCurrentThread(
                "workspace_outside_write", secondUri);
        SolonClawPatchTools tools = guardedPatchTools(workspace);
        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: "
                        + firstUri
                        + "\n@@ one=old @@\n-one=old\n+one=new\n"
                        + "*** Update File: "
                        + firstUri
                        + "\n@@ two=old @@\n-two=old\n+two=new\n"
                        + "*** Update File: "
                        + secondUri
                        + "\n@@ three=old @@\n-three=old\n+three=new\n"
                        + "*** End Patch";

        Map<?, ?> result =
                parseJsonMap(tools.patch("patch", null, null, null, null, patch));

        assertThat(result.get("status")).isEqualTo("success");
        assertThat(readUtf8(first)).isEqualTo("one=new\ntwo=new\n");
        assertThat(readUtf8(second)).isEqualTo("three=new\n");
        Map<?, ?> reused =
                parseJsonMap(
                        tools.patch("replace", firstUri, "one=new", "one=again", false, null));
        assertThat(reused.get("status")).isEqualTo("error");
        assertThat(String.valueOf(reused.get("error"))).contains("APPROVAL_REQUIRED");
        assertThat(readUtf8(first)).isEqualTo("one=new\ntwo=new\n");
    }

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
        Path dir = tempDir("patch");
        Path file = dir.resolve("src/app.txt");
        Files.createDirectories(file.getParent());
        writeUtf8(file, "hello\nworld\n");

        SolonClawPatchTools tools = patchTools(dir);
        String json = tools.patch("replace", "src/app.txt", "hello", "hi", false, null);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("success");
        assertThat(String.valueOf(result.get("files_modified")))
                .contains(file.toRealPath().toString());
        assertThat(readUtf8(file)).isEqualTo("hi\nworld\n");
    }

    /** 验证默认 workspace 工具根下的 patch 路径语义与 read_file/write_file 保持一致。 */
    @Test
    void shouldCollapseWorkspaceRootPrefixForReplaceMode() throws Exception {
        Path tempRoot = tempDir("patch-workspace-prefix");
        Path dir = tempRoot.resolve("workspace");
        Path file = dir.resolve("logs/patch-target.txt");
        Files.createDirectories(file.getParent());
        writeUtf8(file, "line_1\nline_2=old\nline_3\n");

        SolonClawPatchTools tools = patchTools(dir);
        String json =
                tools.patch(
                        "replace",
                        "workspace/logs/patch-target.txt",
                        "line_2=old",
                        "line_2=new",
                        false,
                        null);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("success");
        assertThat(result.get("resolved_path")).isEqualTo(file.toRealPath().toString());
        assertThat(readUtf8(file)).isEqualTo("line_1\nline_2=new\nline_3\n");
        assertThat(dir.resolve("workspace/logs/patch-target.txt")).doesNotExist();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void shouldReplaceByAtomicFileSwap() throws Exception {
        Path dir = tempDir("patch");
        Path file = dir.resolve("target.txt");
        writeUtf8(file, "before\n");
        Object beforeKey = Files.getAttribute(file, "unix:ino");

        SolonClawPatchTools tools = patchTools(dir);
        String json = tools.patch("replace", "target.txt", "before", "after", false, null);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("success");
        assertThat(readUtf8(file)).isEqualTo("after\n");
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

    /** 验证 patch 工具不会把读取结果中的脱敏密钥占位符写回配置文件。 */
    @Test
    void shouldRejectPlaceholderSecretDowngradeWhenPatchingConfigFile() throws Exception {
        Path dir = tempDir("patch-config-secret");
        Path file = dir.resolve("config.yml");
        writeUtf8(
                file,
                "providers:\n"
                        + "  default:\n"
                        + "    apiKey: sk-original-patch-secret-12345\n"
                        + "    defaultModel: gpt-5\n");
        SolonClawPatchTools tools = patchTools(dir);

        Map<?, ?> result =
                parseJsonMap(
                        tools.patch(
                                "replace",
                                "config.yml",
                                "    apiKey: sk-original-patch-secret-12345\n"
                                        + "    defaultModel: gpt-5",
                                "    apiKey: ***\n" + "    defaultModel: gpt-5.1",
                                false,
                                null));

        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error")))
                .contains("配置密钥占位符")
                .contains("config_set_secret")
                .doesNotContain("sk-original-patch-secret-12345");
        assertThat(readUtf8(file))
                .contains("apiKey: sk-original-patch-secret-12345")
                .contains("defaultModel: gpt-5\n");
    }

    /** 验证 V4A 多文件补丁在预验证阶段发现配置密钥占位符，不会先写其它文件。 */
    @Test
    void shouldRejectPlaceholderSecretDowngradeDuringPatchValidationWithoutPartialWrites()
            throws Exception {
        Path dir = tempDir("patch-config-secret-atomic");
        Path first = dir.resolve("notes.txt");
        Path config = dir.resolve("config.yml");
        writeUtf8(first, "before\n");
        writeUtf8(config, "providers:\n  default:\n    apiKey: sk-original-v4a-secret-12345\n");
        SolonClawPatchTools tools = patchTools(dir);
        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: notes.txt\n"
                        + "@@ before @@\n"
                        + "-before\n"
                        + "+after\n"
                        + "*** Update File: config.yml\n"
                        + "@@ apiKey @@\n"
                        + "-    apiKey: sk-original-v4a-secret-12345\n"
                        + "+    apiKey: configured\n"
                        + "*** End Patch";

        Map<?, ?> result = parseJsonMap(tools.patch("patch", null, null, null, null, patch));

        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error")))
                .contains("Patch validation failed")
                .contains("配置密钥占位符")
                .doesNotContain("sk-original-v4a-secret-12345");
        assertThat(readUtf8(first)).isEqualTo("before\n");
        assertThat(readUtf8(config)).contains("apiKey: sk-original-v4a-secret-12345");
    }

    /** 验证 patch 工具仍允许把旧真实密钥替换为用户提供的真实新密钥。 */
    @Test
    void shouldAllowRealSecretReplacementWhenPatchingConfigFile() throws Exception {
        Path dir = tempDir("patch-config-secret-real");
        Path file = dir.resolve("config.yml");
        writeUtf8(file, "providers:\n  default:\n    apiKey: sk-old-patch-secret-12345\n");
        SolonClawPatchTools tools = patchTools(dir);

        Map<?, ?> result =
                parseJsonMap(
                        tools.patch(
                                "replace",
                                "config.yml",
                                "apiKey: sk-old-patch-secret-12345",
                                "apiKey: sk-new-patch-secret-67890",
                                false,
                                null));

        assertThat(result.get("status")).isEqualTo("success");
        assertThat(readUtf8(file)).contains("apiKey: sk-new-patch-secret-67890");
    }

    @Test
    void shouldReportResolvedAbsolutePathForReplaceMode() throws Exception {
        Path dir = tempDir("patch");
        Path file = dir.resolve("target.txt");
        writeUtf8(file, "before\n");

        SolonClawPatchTools tools = patchTools(dir);
        String json = tools.patch("replace", "target.txt", "before", "after", false, null);

        Map<?, ?> result = parseJsonMap(json);
        String expected = file.toRealPath().toString();
        assertThat(result.get("status")).isEqualTo("success");
        assertThat(result.get("resolved_path")).isEqualTo(expected);
        assertThat(String.valueOf(result.get("files_modified"))).isEqualTo("[" + expected + "]");
        assertThat(String.valueOf(result.get("diff")))
                .contains("--- a/target.txt")
                .contains("+++ b/target.txt");
        assertThat(readUtf8(file)).isEqualTo("after\n");
    }

    @Test
    void shouldReportResolvedAbsolutePathForSingleFileV4aUpdate() throws Exception {
        Path dir = tempDir("patch");
        Path file = dir.resolve("app.txt");
        writeUtf8(file, "alpha\nbeta\n");
        SolonClawPatchTools tools = patchTools(dir);
        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: app.txt\n"
                        + "@@ beta @@\n"
                        + " alpha\n"
                        + "-beta\n"
                        + "+gamma\n"
                        + "*** End Patch";

        Map<?, ?> result = parseJsonMap(tools.patch("patch", null, null, null, null, patch));
        String expected = file.toRealPath().toString();

        assertThat(result.get("status")).isEqualTo("success");
        assertThat(result.get("resolved_path")).isEqualTo(expected);
        assertThat(String.valueOf(result.get("files_modified"))).isEqualTo("[" + expected + "]");
        assertThat(readUtf8(file)).isEqualTo("alpha\ngamma\n");
    }

    @Test
    void shouldRejectAmbiguousReplaceUnlessReplaceAll() throws Exception {
        Path dir = tempDir("patch");
        Path file = dir.resolve("dup.txt");
        writeUtf8(file, "x\nx\n");

        SolonClawPatchTools tools = patchTools(dir);
        String json = tools.patch("replace", "dup.txt", "x", "y", false, null);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error"))).contains("Found 2 matches");
    }

    @Test
    void shouldApplyV4aPatchAtomicallyAfterValidation() throws Exception {
        Path dir = tempDir("patch");
        Path file = dir.resolve("app.txt");
        writeUtf8(file, "alpha\nbeta\n");

        SolonClawPatchTools tools = patchTools(dir);
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

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("success");
        assertThat(String.valueOf(result.get("diff")))
                .contains("--- /dev/null")
                .contains("+++ b/new.txt")
                .contains("+created");
        assertThat(readUtf8(file)).isEqualTo("alpha\ngamma\n");
        assertThat(readUtf8(dir.resolve("new.txt"))).isEqualTo("created");
    }

    @Test
    void shouldUseDevNullDiffForDeletedFiles() throws Exception {
        Path dir = tempDir("patch");
        Path file = dir.resolve("old.txt");
        writeUtf8(file, "obsolete\n");
        SolonClawPatchTools tools = patchTools(dir);
        String patch = "*** Begin Patch\n" + "*** Delete File: old.txt\n" + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("success");
        assertThat(String.valueOf(result.get("diff")))
                .contains("--- a/old.txt")
                .contains("+++ /dev/null")
                .contains("-obsolete");
        assertThat(file).doesNotExist();
    }

    @Test
    void shouldRejectAdditionOnlyHunkWhenContextHintIsMissingWithoutWriting() throws Exception {
        Path dir = tempDir("patch");
        Path file = dir.resolve("app.txt");
        writeUtf8(file, "def main():\n    pass\n");
        SolonClawPatchTools tools = patchTools(dir);
        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: app.txt\n"
                        + "@@ def missing @@\n"
                        + "+def helper():\n"
                        + "+    return 42\n"
                        + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error")))
                .contains("Patch validation failed")
                .contains("context hint 'def missing' not found");
        assertThat(readUtf8(file)).isEqualTo("def main():\n    pass\n");
    }

    @Test
    void shouldRejectAdditionOnlyHunkWhenContextHintIsAmbiguousWithoutWriting() throws Exception {
        Path dir = tempDir("patch");
        Path file = dir.resolve("app.txt");
        writeUtf8(file, "marker\none\nmarker\ntwo\n");
        SolonClawPatchTools tools = patchTools(dir);
        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: app.txt\n"
                        + "@@ marker @@\n"
                        + "+inserted\n"
                        + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error")))
                .contains("Patch validation failed")
                .contains("context hint 'marker' is ambiguous");
        assertThat(readUtf8(file)).isEqualTo("marker\none\nmarker\ntwo\n");
    }

    @Test
    void shouldRejectTraversalBeforeWriting() throws Exception {
        Path dir = tempDir("patch");
        SolonClawPatchTools tools = patchTools(dir);

        String json = tools.patch("replace", "../outside.txt", "a", "b", false, null);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error")))
                .contains("APPROVAL_REQUIRED")
                .contains("../outside.txt");
    }

    /** 验证 workspace URI 中的父级逃逸与门禁使用相同语义并保持硬阻断。 */
    @Test
    void shouldRejectWorkspaceUriTraversalBeforeWriting() throws Exception {
        Path boundary = tempDir("patch-workspace-uri-traversal");
        Path workspace = boundary.resolve("workspace");
        Files.createDirectories(workspace);
        SolonClawPatchTools tools = guardedPatchTools(workspace);

        Map<?, ?> result =
                parseJsonMap(
                        tools.patch(
                                "replace",
                                "workspace://../outside.txt",
                                "before",
                                "after",
                                false,
                                null));

        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error")))
                .contains("BLOCKED")
                .contains("路径遍历");
        assertThat(boundary.resolve("outside.txt")).doesNotExist();
    }

    @Test
    void shouldRejectSymlinkEscapeBeforePatching() throws Exception {
        Path dir = tempDir("patch");
        Path outside = tempDir("patch-outside");
        Path outsideFile = outside.resolve("secret.txt");
        writeUtf8(outsideFile, "TOKEN=old\n");
        Path link = dir.resolve("linked");
        assumeTrue(createDirectoryLink(link, outside));
        SolonClawPatchTools tools = patchTools(dir);

        String json = tools.patch("replace", "linked/secret.txt", "old", "new", false, null);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error")))
                .contains("APPROVAL_REQUIRED")
                .contains("linked/secret.txt");
        assertThat(readUtf8(outsideFile)).isEqualTo("TOKEN=old\n");
    }

    void shouldRedactSecretsFromPatchErrors() throws Exception {
        Path dir = tempDir("patch");
        SolonClawPatchTools tools = patchTools(dir);

        String json =
                tools.patch(
                        "replace", "missing-ghp_1234567890abcdef.txt", "old", "new", false, null);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error")))
                .contains("missing-ghp_***")
                .doesNotContain("ghp_1234567890abcdef");
    }

    @Test
    void shouldRedactSensitivePatchErrorPaths() throws Exception {
        Path dir = tempDir("patch");
        SolonClawPatchTools tools = patchTools(dir);

        String replaceJson =
                tools.patch(
                        "replace",
                        "private/token-ghp_patchmissing12345/missing.txt",
                        "old",
                        "new",
                        false,
                        null);
        Map<?, ?> replaceResult = parseJsonMap(replaceJson);
        assertThat(replaceResult.get("status")).isEqualTo("error");
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
        Map<?, ?> patchResult = parseJsonMap(tools.patch("patch", null, null, null, null, patch));
        assertThat(patchResult.get("status")).isEqualTo("error");
        assertThat(String.valueOf(patchResult.get("error")))
                .contains("Patch validation failed")
                .contains("missing.txt")
                .doesNotContain("private/")
                .doesNotContain("ghp_patchvalidation12345");
    }

    @Test
    void shouldRedactSecretsFromPatchSuccessResultsOnly() throws Exception {
        Path dir = tempDir("patch");
        Path file = dir.resolve("src/secret-ghp_patchpath12345.txt");
        Files.createDirectories(file.getParent());
        writeUtf8(file, "old\n");
        SolonClawPatchTools tools = patchTools(dir);

        String json =
                tools.patch(
                        "replace",
                        "src/secret-ghp_patchpath12345.txt",
                        "old",
                        "Authorization: Bearer ghp_patchdiff12345",
                        false,
                        null);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("success");
        assertThat(String.valueOf(result))
                .contains("secret-ghp_***")
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_patchpath12345")
                .doesNotContain("ghp_patchdiff12345");
        assertThat(readUtf8(file)).contains("ghp_patchdiff12345");
    }

    @Test
    void shouldRejectAddFileWhenTargetExistsWithoutOverwriting() throws Exception {
        Path dir = tempDir("patch");
        Path file = dir.resolve("existing.txt");
        writeUtf8(file, "original\n");
        SolonClawPatchTools tools = patchTools(dir);
        String patch =
                "*** Begin Patch\n"
                        + "*** Add File: existing.txt\n"
                        + "+replacement\n"
                        + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error")))
                .contains("Patch validation failed")
                .contains("existing.txt")
                .contains("already exists")
                .contains("add would overwrite");
        assertThat(readUtf8(file)).isEqualTo("original\n");
    }

    @Test
    void shouldRejectPatchWithoutEnvelopeBeforeWriting() throws Exception {
        Path dir = tempDir("patch");
        Path file = dir.resolve("loose.txt");
        writeUtf8(file, "alpha\n");
        SolonClawPatchTools tools = patchTools(dir);
        String patch = "*** Update File: loose.txt\n" + "@@ alpha @@\n" + "-alpha\n" + "+beta\n";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error")))
                .contains("patch rejected")
                .contains("Begin Patch")
                .contains("End Patch");
        assertThat(readUtf8(file)).isEqualTo("alpha\n");
    }

    @Test
    void shouldRejectUpdateMoveToExistingTargetWithoutPartialWrites() throws Exception {
        Path dir = tempDir("patch");
        Path source = dir.resolve("source.txt");
        Path destination = dir.resolve("destination.txt");
        writeUtf8(source, "alpha\n");
        writeUtf8(destination, "destination\n");
        SolonClawPatchTools tools = patchTools(dir);
        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: source.txt\n"
                        + "*** Move to: destination.txt\n"
                        + "@@ alpha @@\n"
                        + "-alpha\n"
                        + "+updated\n"
                        + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error")))
                .contains("Patch validation failed")
                .contains("destination.txt")
                .contains("destination already exists")
                .contains("move would overwrite");
        assertThat(readUtf8(source)).isEqualTo("alpha\n");
        assertThat(readUtf8(destination)).isEqualTo("destination\n");
    }

    @Test
    void shouldRejectMoveFileWithoutDestinationWithoutPartialWrites() throws Exception {
        Path dir = tempDir("patch");
        Path source = dir.resolve("source.txt");
        writeUtf8(source, "alpha\n");
        SolonClawPatchTools tools = patchTools(dir);
        String patch = "*** Begin Patch\n" + "*** Move File: source.txt\n" + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parseJsonMap(json);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("error")))
                .contains("Patch validation failed")
                .contains("missing destination path");
        assertThat(readUtf8(source)).isEqualTo("alpha\n");
    }
}
