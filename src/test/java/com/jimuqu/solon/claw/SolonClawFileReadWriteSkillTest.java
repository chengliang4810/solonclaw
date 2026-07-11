package com.jimuqu.solon.claw;

import static com.jimuqu.solon.claw.support.TestToolSupport.guardedFileSkill;
import static com.jimuqu.solon.claw.support.TestToolSupport.readUtf8;
import static com.jimuqu.solon.claw.support.TestToolSupport.tempDir;
import static com.jimuqu.solon.claw.support.TestToolSupport.writeUtf8;
import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.tool.runtime.SolonClawFileReadWriteSkill;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.noear.snack4.ONode;

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
