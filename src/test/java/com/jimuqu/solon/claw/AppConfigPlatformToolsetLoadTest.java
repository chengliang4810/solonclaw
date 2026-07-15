package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

/** AppConfig 从 yaml 加载 platform 工具集配置的单元测试。 */
public class AppConfigPlatformToolsetLoadTest {

    // ---- AppConfig 配置加载测试 ----

    @Test
    void shouldLoadPlatformToolsetConfigFromYaml() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-platform-toolset").toFile();
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  gateway:\n"
                        + "    platforms:\n"
                        + "      FEISHU:\n"
                        + "        enabledToolsets:\n"
                        + "          - web\n"
                        + "          - file\n"
                        + "      DINGTALK:\n"
                        + "        enabledToolsets:\n"
                        + "          - web\n"
                        + "          - terminal\n"
                        + "          - file\n"
                        + "        approvalRequired: true\n",
                new File(workspaceHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        AppConfig.GatewayConfig gateway = config.getGateway();
        assertThat(gateway.getPlatforms()).containsKeys("FEISHU", "DINGTALK");

        AppConfig.PlatformConfig feishu = gateway.getPlatforms().get("FEISHU");
        assertThat(feishu.getEnabledToolsets()).containsExactly("web", "file");
        assertThat(feishu.isApprovalRequired()).isFalse();

        AppConfig.PlatformConfig dingtalk = gateway.getPlatforms().get("DINGTALK");
        assertThat(dingtalk.getEnabledToolsets()).containsExactly("web", "terminal", "file");
        assertThat(dingtalk.isApprovalRequired()).isTrue();
    }

    @Test
    void shouldReturnEmptyPlatformsWhenNotConfigured() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-platform-empty").toFile();
        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        assertThat(config.getGateway().getPlatforms()).isEmpty();
        assertThat(config.getGateway().isMultiplexProfiles()).isTrue();
    }

    /** 复用网关开关从当前项目的嵌套配置读取，并支持常见布尔文本。 */
    @Test
    void shouldLoadMultiplexProfilesFromNestedGatewayConfig() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-profile-multiplex").toFile();
        FileUtil.writeUtf8String(
                "solonclaw:\n  gateway:\n    multiplexProfiles: yes\n",
                new File(workspaceHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        assertThat(AppConfig.load(props).getGateway().isMultiplexProfiles()).isTrue();
    }
}
