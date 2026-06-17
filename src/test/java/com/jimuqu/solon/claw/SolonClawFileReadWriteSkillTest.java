package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.jimuqu.solon.claw.support.TestToolSupport.guardedFileSkill;
import static com.jimuqu.solon.claw.support.TestToolSupport.readUtf8;
import static com.jimuqu.solon.claw.support.TestToolSupport.tempDir;
import static com.jimuqu.solon.claw.support.TestToolSupport.writeUtf8;

import com.jimuqu.solon.claw.tool.runtime.SolonClawFileReadWriteSkill;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.noear.snack4.ONode;

public class SolonClawFileReadWriteSkillTest {
    @Test
    void shouldBlockRuntimeCredentialCacheRead() throws Exception {
        Path dir = tempDir("file-read-safety");
        Files.createDirectories(dir.resolve("cache"));
        writeUtf8(dir.resolve("cache/bws_cache.json"), "secret");
        SolonClawFileReadWriteSkill skill = guardedFileSkill(dir);

        assertThatThrownBy(() -> skill.read("cache/bws_cache.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("读取敏感系统/凭据文件被阻断")
                .hasMessageNotContaining("secret");
    }

    /** 验证默认 runtime 工作区下的脚本写入不会落到嵌套 runtime 目录。 */
    @Test
    void shouldCollapseRuntimeRootPrefixForScriptWrites() throws Exception {
        Path tempRoot = tempDir("file-runtime-prefix");
        Path dir = tempRoot.resolve("runtime");
        Files.createDirectories(dir);
        SolonClawFileReadWriteSkill skill = guardedFileSkill(dir);

        ONode result = ONode.ofJson(skill.write("runtime/scripts/loop-probe.py", "print('ok')\n"));
        Path expected = dir.resolve("scripts/loop-probe.py");

        assertThat(result.get("success").getBoolean()).isTrue();
        assertThat(result.get("resolved_path").getString())
                .isEqualTo(expected.toRealPath().toString());
        assertThat(expected).exists();
        assertThat(dir.resolve("runtime/scripts/loop-probe.py")).doesNotExist();
    }

    /**
     * 验证对标行为读取工具能够兼容内置文件工具常用的fileName参数名，避免模型重试浪费工具调用次数。
     */
    @Test
    void shouldAcceptFileNameAliasForReadFileTool() throws Exception {
        Path dir = tempDir("file-read-alias");
        writeUtf8(dir.resolve("state.json"), "{\"ok\":true}\n");
        SolonClawFileReadWriteSkill skill = new SolonClawFileReadWriteSkill(dir.toString(), null);

        ONode result = ONode.ofJson(skill.readFile(null, "state.json", 1, 5));

        assertThat(result.get("success").getBoolean()).isTrue();
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

        assertThat(result).contains("\"success\":true");
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

        assertThat(result).contains("\"success\":true");
        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_READ);
    }
}
