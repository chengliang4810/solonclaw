package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.gateway.context.GatewayContextIsolation;
import com.jimuqu.solon.claw.gateway.policy.PlatformToolsetPolicy;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

public class GatewayContextIsolationAndToolsetPolicyTest {

    // ---- GatewayContextIsolation 测试 ----

    @Test
    void isolationKeyShouldMatchSourceKey() {
        GatewayMessage msg =
                new GatewayMessage(PlatformType.FEISHU, "chat-001", "user-abc", "hello");
        assertThat(GatewayContextIsolation.isolationKey(msg)).isEqualTo(msg.sourceKey());
    }

    @Test
    void isolationKeyShouldHandleNullMessage() {
        assertThat(GatewayContextIsolation.isolationKey(null)).isNotNull();
    }

    @Test
    void sameContextShouldReturnTrueForSamePlatformChatUser() {
        GatewayMessage a = new GatewayMessage(PlatformType.FEISHU, "chat-001", "user-abc", "msg1");
        GatewayMessage b = new GatewayMessage(PlatformType.FEISHU, "chat-001", "user-abc", "msg2");
        assertThat(GatewayContextIsolation.sameContext(a, b)).isTrue();
    }

    @Test
    void sameContextShouldReturnFalseForDifferentPlatform() {
        GatewayMessage a = new GatewayMessage(PlatformType.FEISHU, "chat-001", "user-abc", "msg1");
        GatewayMessage b =
                new GatewayMessage(PlatformType.DINGTALK, "chat-001", "user-abc", "msg2");
        assertThat(GatewayContextIsolation.sameContext(a, b)).isFalse();
    }

    @Test
    void sameContextShouldReturnFalseForDifferentChat() {
        GatewayMessage a = new GatewayMessage(PlatformType.FEISHU, "chat-001", "user-abc", "msg1");
        GatewayMessage b = new GatewayMessage(PlatformType.FEISHU, "chat-002", "user-abc", "msg2");
        assertThat(GatewayContextIsolation.sameContext(a, b)).isFalse();
    }

    @Test
    void sameContextShouldReturnFalseForDifferentUser() {
        GatewayMessage a = new GatewayMessage(PlatformType.FEISHU, "chat-001", "user-abc", "msg1");
        GatewayMessage b = new GatewayMessage(PlatformType.FEISHU, "chat-001", "user-xyz", "msg2");
        assertThat(GatewayContextIsolation.sameContext(a, b)).isFalse();
    }

    // ---- PlatformToolsetPolicy 测试 ----

    @Test
    void shouldReturnGlobalToolsetsWhenNoPlatformConfig() {
        AppConfig.GatewayConfig gatewayConfig = new AppConfig.GatewayConfig();
        List<String> global = Arrays.asList("web", "terminal", "file");
        List<String> result =
                PlatformToolsetPolicy.resolveToolsets(PlatformType.FEISHU, global, gatewayConfig);
        assertThat(result).containsExactlyInAnyOrder("web", "terminal", "file");
    }

    @Test
    void shouldReturnEnabledToolsetsWhenConfigured() {
        AppConfig.GatewayConfig gatewayConfig = new AppConfig.GatewayConfig();
        AppConfig.PlatformConfig feishuConfig = new AppConfig.PlatformConfig();
        feishuConfig.setEnabledToolsets(Arrays.asList("web", "file"));
        gatewayConfig.getPlatforms().put("FEISHU", feishuConfig);

        List<String> global = Arrays.asList("web", "terminal", "file");
        List<String> result =
                PlatformToolsetPolicy.resolveToolsets(PlatformType.FEISHU, global, gatewayConfig);
        assertThat(result).containsExactlyInAnyOrder("web", "file");
        assertThat(result).doesNotContain("terminal");
    }

    @Test
    void shouldApplyDisabledToolsetsOnTopOfGlobal() {
        AppConfig.GatewayConfig gatewayConfig = new AppConfig.GatewayConfig();
        AppConfig.PlatformConfig feishuConfig = new AppConfig.PlatformConfig();
        feishuConfig.setDisabledToolsets(Arrays.asList("terminal"));
        gatewayConfig.getPlatforms().put("FEISHU", feishuConfig);

        List<String> global = Arrays.asList("web", "terminal", "file");
        List<String> result =
                PlatformToolsetPolicy.resolveToolsets(PlatformType.FEISHU, global, gatewayConfig);
        assertThat(result).containsExactlyInAnyOrder("web", "file");
        assertThat(result).doesNotContain("terminal");
    }

    @Test
    void disabledToolsetsShouldOverrideEnabledToolsets() {
        AppConfig.GatewayConfig gatewayConfig = new AppConfig.GatewayConfig();
        AppConfig.PlatformConfig config = new AppConfig.PlatformConfig();
        config.setEnabledToolsets(Arrays.asList("web", "terminal", "file"));
        config.setDisabledToolsets(Arrays.asList("terminal"));
        gatewayConfig.getPlatforms().put("DINGTALK", config);

        List<String> result =
                PlatformToolsetPolicy.resolveToolsets(
                        PlatformType.DINGTALK, Collections.<String>emptyList(), gatewayConfig);
        assertThat(result).containsExactlyInAnyOrder("web", "file");
        assertThat(result).doesNotContain("terminal");
    }

    @Test
    void differentPlatformsShouldHaveIndependentToolsets() {
        AppConfig.GatewayConfig gatewayConfig = new AppConfig.GatewayConfig();

        AppConfig.PlatformConfig feishuConfig = new AppConfig.PlatformConfig();
        feishuConfig.setEnabledToolsets(Arrays.asList("web", "file"));
        gatewayConfig.getPlatforms().put("FEISHU", feishuConfig);

        AppConfig.PlatformConfig dingtalkConfig = new AppConfig.PlatformConfig();
        dingtalkConfig.setEnabledToolsets(Arrays.asList("web", "terminal", "file"));
        gatewayConfig.getPlatforms().put("DINGTALK", dingtalkConfig);

        List<String> global = Arrays.asList("web", "terminal", "file");

        List<String> feishuResult =
                PlatformToolsetPolicy.resolveToolsets(PlatformType.FEISHU, global, gatewayConfig);
        List<String> dingtalkResult =
                PlatformToolsetPolicy.resolveToolsets(PlatformType.DINGTALK, global, gatewayConfig);

        assertThat(feishuResult).containsExactlyInAnyOrder("web", "file");
        assertThat(feishuResult).doesNotContain("terminal");
        assertThat(dingtalkResult).containsExactlyInAnyOrder("web", "terminal", "file");
    }

    @Test
    void isApprovalRequiredShouldReturnFalseByDefault() {
        AppConfig.GatewayConfig gatewayConfig = new AppConfig.GatewayConfig();
        assertThat(PlatformToolsetPolicy.isApprovalRequired(PlatformType.FEISHU, gatewayConfig))
                .isFalse();
    }

    @Test
    void isApprovalRequiredShouldReturnTrueWhenConfigured() {
        AppConfig.GatewayConfig gatewayConfig = new AppConfig.GatewayConfig();
        AppConfig.PlatformConfig config = new AppConfig.PlatformConfig();
        config.setApprovalRequired(true);
        gatewayConfig.getPlatforms().put("FEISHU", config);

        assertThat(PlatformToolsetPolicy.isApprovalRequired(PlatformType.FEISHU, gatewayConfig))
                .isTrue();
        assertThat(PlatformToolsetPolicy.isApprovalRequired(PlatformType.DINGTALK, gatewayConfig))
                .isFalse();
    }

    // ---- AppConfig 配置加载测试 ----

    @Test
    void shouldLoadPlatformToolsetConfigFromYaml() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-platform-toolset").toFile();
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
                new File(runtimeHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());
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
        File runtimeHome = Files.createTempDirectory("solon-claw-platform-empty").toFile();
        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        assertThat(config.getGateway().getPlatforms()).isEmpty();
    }
}
