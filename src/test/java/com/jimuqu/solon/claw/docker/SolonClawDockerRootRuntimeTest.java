package com.jimuqu.solon.claw.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * 校验官方 Docker 镜像保持非 root 运行（固定 UID 的 solonclaw 用户）与基础远程连接能力。
 *
 * <p>历史上镜像曾以 root 运行，后改为非 root 用户以避免 Agent 工具获得容器 root 权限；本测试锁定该安全策略，防止被误改回 root 或引入 gosu / 动态 UID 等降权方案。
 */
public class SolonClawDockerRootRuntimeTest {
    @Test
    void shouldRunOfficialDockerImageAsNonRoot() throws Exception {
        String dockerfile = readFile("Dockerfile");
        String entrypoint = readFile("docker/entrypoint.sh");
        String compose = readFile("docker-compose.yml");

        // 官方镜像使用固定 UID 的非 root 用户 solonclaw 运行，不依赖 gosu 降权。
        assertThat(dockerfile)
                .contains("openssh-client")
                .contains("useradd")
                .contains("USER solonclaw")
                .doesNotContain("gosu");
        assertThat(entrypoint)
                .contains("mkdir -p \"$WORKSPACE_HOME\"")
                .contains("exec java -jar /app/solonclaw.jar \"$@\"")
                .doesNotContain("gosu")
                .doesNotContain("SOLONCLAW_UID")
                .doesNotContain("SOLONCLAW_GID");
        assertThat(compose)
                .contains("SOLONCLAW_OFFICIAL_DOCKER_IMAGE")
                .doesNotContain("SOLONCLAW_UID")
                .doesNotContain("SOLONCLAW_GID");
    }

    /**
     * 读取仓库文件内容，用于锁定 Docker 非 root 运行策略不被后续改动误恢复为 root。
     *
     * @param path 仓库相对路径。
     * @return 文件文本内容。
     */
    private static String readFile(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
