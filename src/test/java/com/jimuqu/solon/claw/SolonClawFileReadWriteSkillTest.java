package com.jimuqu.solon.claw;

import static com.jimuqu.solon.claw.support.TestToolSupport.guardedFileSkill;
import static com.jimuqu.solon.claw.support.TestToolSupport.readUtf8;
import static com.jimuqu.solon.claw.support.TestToolSupport.tempDir;
import static com.jimuqu.solon.claw.support.TestToolSupport.writeUtf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SolonClawFileReadWriteSkill;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.noear.snack4.ONode;
import org.slf4j.LoggerFactory;

public class SolonClawFileReadWriteSkillTest {
    // shouldBlockRuntimeCredentialCacheRead 已删除：凭据文件读已放宽（对齐 外部对标仓库"读非安全边界"），
    // 读 bws_cache.json 现在放行，写仍阻断。

    /** 验证默认 workspace 工作区下的脚本写入不会落到嵌套 workspace 目录。 */
    @Test
    void shouldCollapseWorkspaceRootPrefixForScriptWrites() throws Exception {
        Path tempRoot = tempDir("file-workspace-prefix");
        Path dir = tempRoot.resolve("workspace");
        Files.createDirectories(dir);
        SolonClawFileReadWriteSkill skill = guardedFileSkill(dir);

        ONode result =
                ONode.ofJson(skill.write("workspace/scripts/loop-probe.py", "print('ok')\n"));
        Path expected = dir.resolve("scripts/loop-probe.py");

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("resolved_path").getString())
                .isEqualTo(expected.toRealPath().toString());
        assertThat(expected).exists();
        assertThat(dir.resolve("workspace/scripts/loop-probe.py")).doesNotExist();
    }

    /** 验证获批工作区外写入后，去重清理复用已校验路径且不会记录沙箱异常警告。 */
    @Test
    void shouldNotWarnWhenClearingReadDedupAfterApprovedOutsideWorkspaceWrite() throws Exception {
        Path workspace = tempDir("file-approved-outside-workspace");
        Path outsideFile = tempDir("file-approved-outside-target").resolve("approved.txt");
        SolonClawFileReadWriteSkill skill = guardedFileSkill(workspace);
        Logger logger = (Logger) LoggerFactory.getLogger(SolonClawFileReadWriteSkill.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        SecurityPolicyService.approveFilePolicyForCurrentThread(
                "workspace_outside_write", outsideFile.toString());

        ONode result;
        try {
            result = ONode.ofJson(skill.write(outsideFile.toString(), "approved\n"));
        } finally {
            SecurityPolicyService.clearCurrentThreadPolicyApprovals();
            logger.detachAppender(appender);
        }

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(readUtf8(outsideFile)).isEqualTo("approved\n");
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("清理文件读取去重状态失败"));
    }

    /** 验证工作区 URI 的审批 token 始终绑定工具收到的原始目标，而不是归一化后的磁盘路径。 */
    @Test
    void shouldWriteApprovedWorkspaceUriOutsideWorkspace() throws Exception {
        Path boundary = tempDir("file-approved-workspace-uri");
        Path workspace = boundary.resolve("workspace");
        Path outsideFile = boundary.resolve("approved.txt").toAbsolutePath();
        Files.createDirectories(workspace);
        String uri = "workspace://" + outsideFile;
        SolonClawFileReadWriteSkill skill = guardedFileSkill(workspace);
        SecurityPolicyService.approveFilePolicyForCurrentThread("workspace_outside_write", uri);

        ONode result;
        try {
            result = ONode.ofJson(skill.write(uri, "approved workspace uri\n"));
        } finally {
            SecurityPolicyService.clearCurrentThreadPolicyApprovals();
        }

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(readUtf8(outsideFile)).isEqualTo("approved workspace uri\n");
    }

    /** 验证两个文件写入入口均解析当前 workspace URI，并在审批前拒绝兄弟 Profile URI。 */
    @Test
    void shouldGuardWorkspaceUrisConsistentlyAcrossFileWriteTools() throws Exception {
        Path profileRoot = tempDir("file-workspace-uri-profile-root");
        Path activeHome = Files.createDirectories(profileRoot.resolve("profiles/active"));
        Path workspace = Files.createDirectories(activeHome.resolve("workspace"));
        Path siblingHome = Files.createDirectories(profileRoot.resolve("profiles/sibling"));
        Path siblingSkills = Files.createDirectories(siblingHome.resolve("skills"));
        String siblingUri = "workspace://" + siblingSkills.resolve("blocked.md").toAbsolutePath();
        String allowedUri = "workspace://" + siblingSkills.resolve("allowed.md").toAbsolutePath();
        String previous = System.getProperty("solonclaw.profile.root");
        System.setProperty("solonclaw.profile.root", profileRoot.toString());
        SolonClawFileReadWriteSkill skill = guardedFileSkill(workspace);
        SecurityPolicyService.approveFilePolicyForCurrentThread(
                "workspace_outside_write", siblingUri);
        try {
            ONode legacy = ONode.ofJson(skill.write("workspace://notes/legacy.txt", "legacy\n"));
            ONode current =
                    ONode.ofJson(
                            skill.writeFile("workspace://notes/current.txt", "current\n", false));
            ONode blockedLegacy = ONode.ofJson(skill.write(siblingUri, "blocked\n"));
            ONode blockedCurrent = ONode.ofJson(skill.writeFile(siblingUri, "blocked\n", false));

            assertThat(legacy.get("status").getString()).isEqualTo("success");
            assertThat(current.get("status").getString()).isEqualTo("success");
            assertThat(readUtf8(workspace.resolve("notes/legacy.txt"))).isEqualTo("legacy\n");
            assertThat(readUtf8(workspace.resolve("notes/current.txt"))).isEqualTo("current\n");
            assertThat(blockedLegacy.get("error").getString())
                    .contains("Profile 'sibling'")
                    .doesNotContain("APPROVAL_REQUIRED");
            assertThat(blockedCurrent.get("error").getString())
                    .contains("Profile 'sibling'")
                    .doesNotContain("APPROVAL_REQUIRED");
            assertThat(siblingSkills.resolve("blocked.md")).doesNotExist();
            SecurityPolicyService.approveFilePolicyForCurrentThread(
                    "workspace_outside_write", allowedUri);
            ONode allowed = ONode.ofJson(skill.writeFile(allowedUri, "allowed\n", true));
            assertThat(allowed.get("status").getString()).isEqualTo("success");
            assertThat(readUtf8(siblingSkills.resolve("allowed.md"))).isEqualTo("allowed\n");
        } finally {
            SecurityPolicyService.clearCurrentThreadPolicyApprovals();
            if (previous == null) {
                System.clearProperty("solonclaw.profile.root");
            } else {
                System.setProperty("solonclaw.profile.root", previous);
            }
        }
    }

    /** 验证 read_file 当前入口只使用 path 参数读取文件。 */
    @Test
    void shouldReadFileWithCurrentPathParameter() throws Exception {
        Path dir = tempDir("file-read-path");
        writeUtf8(dir.resolve("state.json"), "{\"ok\":true}\n");
        SolonClawFileReadWriteSkill skill = new SolonClawFileReadWriteSkill(dir.toString(), null);

        ONode result = ONode.ofJson(skill.readFile("state.json", 1, 5));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("path").getString()).isEqualTo("state.json");
        assertThat(result.get("content").getString()).contains("1|{\"ok\":true}");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void shouldWriteByAtomicFileSwap() throws Exception {
        Path dir = tempDir("file-write");
        Path file = dir.resolve("target.txt");
        writeUtf8(file, "before\n");
        Object beforeKey = Files.getAttribute(file, "unix:ino");

        SolonClawFileReadWriteSkill skill = new SolonClawFileReadWriteSkill(dir.toString(), null);
        String result = skill.write("target.txt", "after\n");

        assertThat(ONode.ofJson(result).get("status").getString()).isEqualTo("success");
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

    /** 验证对话式整文件写入不会把已存在真实密钥覆盖成读取结果里的脱敏占位符。 */
    @Test
    void shouldRejectPlaceholderSecretDowngradeWhenWritingConfigFile() throws Exception {
        Path dir = tempDir("file-write-config-secret");
        Path file = dir.resolve("config.yml");
        writeUtf8(
                file,
                "providers:\n"
                        + "  default:\n"
                        + "    apiKey: sk-original-config-secret-12345\n"
                        + "    defaultModel: gpt-5\n");
        SolonClawFileReadWriteSkill skill = new SolonClawFileReadWriteSkill(dir.toString(), null);

        ONode rejected =
                ONode.ofJson(
                        skill.write(
                                "config.yml",
                                "providers:\n"
                                        + "  default:\n"
                                        + "    apiKey: ***\n"
                                        + "    defaultModel: gpt-5.1\n"));

        assertThat(rejected.get("status").getString()).isEqualTo("error");
        assertThat(rejected.get("error").getString())
                .contains("配置密钥占位符")
                .contains("config_set_secret")
                .doesNotContain("sk-original-config-secret-12345");
        assertThat(readUtf8(file))
                .contains("apiKey: sk-original-config-secret-12345")
                .contains("defaultModel: gpt-5\n");
    }

    /** 验证用户提供真实新密钥时仍允许通过文件写入完成配置更新。 */
    @Test
    void shouldAllowRealSecretReplacementWhenWritingConfigFile() throws Exception {
        Path dir = tempDir("file-write-config-secret-real");
        Path file = dir.resolve("config.yml");
        writeUtf8(file, "providers:\n  default:\n    apiKey: sk-old-real-secret-12345\n");
        SolonClawFileReadWriteSkill skill = new SolonClawFileReadWriteSkill(dir.toString(), null);

        ONode result =
                ONode.ofJson(
                        skill.write(
                                "config.yml",
                                "providers:\n"
                                        + "  default:\n"
                                        + "    apiKey: sk-new-real-secret-67890\n"));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(readUtf8(file)).contains("apiKey: sk-new-real-secret-67890");
    }

    /** 验证模型文件写入不能绕过长期记忆审批，并覆盖路径规范化和符号链接。 */
    @Test
    void shouldBlockMemoryControlWritesAcrossPathForms() throws Exception {
        Path workspace = tempDir("file-memory-write-guard");
        Files.createDirectories(workspace.resolve("memory"));
        SolonClawFileReadWriteSkill skill =
                new SolonClawFileReadWriteSkill(workspace.toString(), null);

        assertMemoryWriteBlocked(skill, "MEMORY.md");
        assertMemoryWriteBlocked(skill, workspace.resolve("USER.md").toString());
        assertMemoryWriteBlocked(skill, "nested/../.solonclaw-memory-approvals.json");
        assertMemoryWriteBlocked(skill, ".solonclaw-memory-approvals.lock");
        assertMemoryWriteBlocked(skill, "memory/2026-07-13.md");

        Path link = workspace.resolve("memory-link");
        if (com.jimuqu.solon.claw.support.TestToolSupport.createDirectoryLink(
                link, workspace.resolve("memory"))) {
            assertMemoryWriteBlocked(skill, "memory-link/2026-07-13.md");
        }
    }

    /** 验证跨 Profile 路径即使获得一次性写入审批，也不能写入其他 Profile 的记忆控制文件。 */
    @Test
    void shouldBlockApprovedOutsideMemoryControlWrite() throws Exception {
        Path profileRoot = tempDir("file-memory-write-profile-root");
        Path workspace = Files.createDirectories(profileRoot.resolve("profiles/active"));
        Path target =
                Files.createDirectories(profileRoot.resolve("profiles/other")).resolve("MEMORY.md");
        SolonClawFileReadWriteSkill skill = guardedFileSkill(workspace);
        SecurityPolicyService.approveFilePolicyForCurrentThread(
                "workspace_outside_write", target.toString());

        try {
            assertMemoryWriteBlocked(skill, target.toString());
        } finally {
            SecurityPolicyService.clearCurrentThreadPolicyApprovals();
        }
    }

    /** 断言指定路径被统一的长期记忆写入边界拒绝。 */
    private void assertMemoryWriteBlocked(SolonClawFileReadWriteSkill skill, String path) {
        assertThatThrownBy(() -> skill.write(path, "blocked\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长期记忆");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void shouldPreserveExistingFilePermissions() throws Exception {
        Path dir = tempDir("file-write");
        Path file = dir.resolve("mode.txt");
        writeUtf8(file, "before\n");
        Files.setPosixFilePermissions(
                file,
                java.util.EnumSet.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_READ));

        SolonClawFileReadWriteSkill skill = new SolonClawFileReadWriteSkill(dir.toString(), null);
        String result = skill.write("mode.txt", "after\n");

        assertThat(ONode.ofJson(result).get("status").getString()).isEqualTo("success");
        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_READ);
    }
}
