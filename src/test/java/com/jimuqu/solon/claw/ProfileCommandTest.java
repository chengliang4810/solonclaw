package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.profile.ProfileCreateOptions;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 验证 `/profile` 只展示当前 JVM 实际运行的非敏感 Profile 状态。 */
public class ProfileCommandTest {
    /** 当前运行 Profile 必须按 AppConfig 工作区解析，不能误用 sticky active Profile。 */
    @Test
    void shouldReportRuntimeProfileInsteadOfStickyActiveProfile() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path root = Files.createTempDirectory("profile-command-root");
        Path userHome = Files.createTempDirectory("profile-command-home");
        String previousRoot = System.getProperty("solonclaw.profile.root");
        String previousHome = System.getProperty("user.home");
        String previousProfile = System.getProperty("solonclaw.profile.name");
        try {
            System.setProperty("solonclaw.profile.root", root.toString());
            System.setProperty("user.home", userHome.toString());
            System.setProperty("solonclaw.profile.name", "default");
            ProfileManager manager = ProfileManager.current();
            manager.createProfile("work", new ProfileCreateOptions().setNoAlias(true));
            Path workHome = root.resolve("profiles/work");
            Files.write(
                    workHome.resolve("config.yml"),
                    "model:\n  providerKey: local\n  default: work-model\n"
                            .getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(workHome.resolve("skills/demo"));
            Files.write(
                    workHome.resolve("skills/demo/SKILL.md"),
                    "---\nname: demo\n---\n".getBytes(StandardCharsets.UTF_8));
            manager.createProfileAlias("work", "work-alias");
            env.appConfig.getRuntime().setHome(workHome.toString());

            GatewayReply reply =
                    env.commandService.handle(
                            env.message("profile-room", "profile-user", "/profile"), "/profile");

            assertThat(reply.isError()).isFalse();
            assertThat(reply.getContent())
                    .contains("name=work")
                    .contains("home=" + workHome)
                    .contains("model=local/work-model")
                    .contains("gateway=stopped")
                    .containsPattern("skills=\\d+")
                    .contains("alias=work-alias")
                    .doesNotContain("Credentials")
                    .doesNotContain("apiKey");
            assertThat(reply.getRuntimeMetadata())
                    .containsEntry("command_status", "handled")
                    .containsEntry("command", "profile")
                    .containsEntry("profile", "work");
        } finally {
            restoreProperty("solonclaw.profile.root", previousRoot);
            restoreProperty("user.home", previousHome);
            restoreProperty("solonclaw.profile.name", previousProfile);
        }
    }

    /** 恢复可能不存在的 JVM 属性，避免污染并行之外的后续测试。 */
    private void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }
}
