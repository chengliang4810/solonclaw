package com.jimuqu.solon.claw.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** 校验官方 Docker 镜像保持 root 运行与基础远程连接能力。 */
public class SolonClawDockerRootRuntimeTest {
    @Test
    void shouldRunOfficialDockerImageAsRoot() throws Exception {
        String dockerfile = readFile("Dockerfile");
        String entrypoint = readFile("docker/entrypoint.sh");
        String compose = readFile("docker-compose.yml");

        assertThat(dockerfile)
                .contains("openssh-client")
                .doesNotContain("gosu")
                .doesNotContain("useradd")
                .doesNotContain("groupadd");
        assertThat(entrypoint)
                .contains("mkdir -p \"$RUNTIME_HOME\"")
                .contains("exec java -jar /app/solon-claw.jar \"$@\"")
                .doesNotContain("gosu")
                .doesNotContain("SOLONCLAW_UID")
                .doesNotContain("SOLONCLAW_GID");
        assertThat(compose)
                .contains("SOLONCLAW_OFFICIAL_DOCKER_IMAGE")
                .doesNotContain("SOLONCLAW_UID")
                .doesNotContain("SOLONCLAW_GID");
    }

    /**
     * 读取仓库文件内容，用于避免 Docker 默认用户策略在后续改动中被误恢复。
     *
     * @param path 仓库相对路径。
     * @return 文件文本内容。
     */
    private static String readFile(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
