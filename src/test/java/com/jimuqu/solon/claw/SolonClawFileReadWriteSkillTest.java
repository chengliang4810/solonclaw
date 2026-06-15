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
import org.noear.snack4.ONode;

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

    /** 验证默认 runtime 工作区下的脚本写入不会落到嵌套 runtime 目录。 */
    @Test
    void shouldCollapseRuntimeRootPrefixForScriptWrites() throws Exception {
        Path tempRoot = Files.createTempDirectory("jimuqu-file-runtime-prefix-test");
        Path dir = tempRoot.resolve("runtime");
        Files.createDirectories(dir);
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(dir.toString());
        SolonClawFileReadWriteSkill skill =
                new SolonClawFileReadWriteSkill(dir.toString(), new SecurityPolicyService(config));

        ONode result = ONode.ofJson(skill.write("runtime/scripts/loop-probe.py", "print('ok')\n"));
        Path expected = dir.resolve("scripts/loop-probe.py");

        assertThat(result.get("success").getBoolean()).isTrue();
        assertThat(result.get("resolved_path").getString())
                .isEqualTo(expected.toRealPath().toString());
        assertThat(Files.exists(expected)).isTrue();
        assertThat(Files.exists(dir.resolve("runtime/scripts/loop-probe.py"))).isFalse();
    }

    /**
     * 验证参考风格读取工具能够兼容内置文件工具常用的fileName参数名，避免模型重试浪费工具调用次数。
     */
    @Test
    void shouldAcceptFileNameAliasForReadFileTool() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-file-read-alias-test");
        Files.write(dir.resolve("state.json"), "{\"ok\":true}\n".getBytes(StandardCharsets.UTF_8));
        SolonClawFileReadWriteSkill skill = new SolonClawFileReadWriteSkill(dir.toString(), null);

        ONode result = ONode.ofJson(skill.readFile(null, "state.json", 1, 5));

        assertThat(result.get("success").getBoolean()).isTrue();
        assertThat(result.get("path").getString()).isEqualTo("state.json");
        assertThat(result.get("content").getString()).contains("1|{\"ok\":true}");
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
