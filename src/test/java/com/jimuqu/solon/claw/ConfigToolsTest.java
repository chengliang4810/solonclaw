package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.support.TestEnvironment;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class ConfigToolsTest {
    @Test
    void shouldExposeConfigSetToolAndUpdateRuntimeConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object configSetTool = null;
        for (Object tool : env.toolRegistry.resolveEnabledTools("MEMORY:chat-1:user-1")) {
            for (Method method : tool.getClass().getMethods()) {
                if ("configSet".equals(method.getName())) {
                    configSetTool = tool;
                    break;
                }
            }
            if (configSetTool != null) {
                break;
            }
        }

        assertThat(configSetTool).isNotNull();
        Method method = configSetTool.getClass().getMethod("configSet", String.class, String.class);
        String response = (String) method.invoke(configSetTool, "channels.weixin.enabled", "true");
        assertThat(ONode.ofJson(response).get("success").getBoolean()).isTrue();
        assertThat(env.appConfig.getChannels().getWeixin().isEnabled()).isTrue();

        String reactResponse =
                (String) method.invoke(configSetTool, "react.delegateMaxSteps", "24");
        assertThat(ONode.ofJson(reactResponse).get("success").getBoolean()).isTrue();
        assertThat(env.appConfig.getReact().getDelegateMaxSteps()).isEqualTo(24);

        String summaryResponse =
                (String) method.invoke(configSetTool, "react.summarizationMaxTokens", "48000");
        assertThat(ONode.ofJson(summaryResponse).get("success").getBoolean()).isTrue();
        assertThat(env.appConfig.getReact().getSummarizationMaxTokens()).isEqualTo(48000);
    }

    @Test
    void shouldRejectPlaceholderSecretsFromConfigTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object configSetSecretTool = null;
        for (Object tool : env.toolRegistry.resolveEnabledTools("MEMORY:chat-1:user-1")) {
            for (Method method : tool.getClass().getMethods()) {
                if ("configSetSecret".equals(method.getName())) {
                    configSetSecretTool = tool;
                    break;
                }
            }
            if (configSetSecretTool != null) {
                break;
            }
        }

        assertThat(configSetSecretTool).isNotNull();
        Method method =
                configSetSecretTool
                        .getClass()
                        .getMethod("configSetSecret", String.class, String.class);
        String response =
                (String)
                        method.invoke(
                                configSetSecretTool,
                                "providers.default.apiKey",
                                "your-api-key");

        assertThat(ONode.ofJson(response).get("success").getBoolean()).isFalse();
        assertThat(ONode.ofJson(response).get("error").getString()).contains("占位符密钥");

        String channelSecretResponse =
                (String)
                        method.invoke(
                                configSetSecretTool,
                                "solonclaw.channels.weixin.token",
                                "dummy");
        assertThat(ONode.ofJson(channelSecretResponse).get("success").getBoolean()).isFalse();
        assertThat(ONode.ofJson(channelSecretResponse).get("error").getString())
                .contains("占位符密钥");
    }

    @Test
    void shouldRejectPlaceholderSecretsFromConfigSetTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object configSetTool = null;
        for (Object tool : env.toolRegistry.resolveEnabledTools("MEMORY:chat-1:user-1")) {
            for (Method method : tool.getClass().getMethods()) {
                if ("configSet".equals(method.getName())) {
                    configSetTool = tool;
                    break;
                }
            }
            if (configSetTool != null) {
                break;
            }
        }

        assertThat(configSetTool).isNotNull();
        Method method = configSetTool.getClass().getMethod("configSet", String.class, String.class);
        String providerResponse =
                (String) method.invoke(configSetTool, "providers.default.apiKey", "example");
        String channelResponse =
                (String) method.invoke(configSetTool, "channels.dingtalk.clientSecret", "none");

        assertThat(ONode.ofJson(providerResponse).get("success").getBoolean()).isFalse();
        assertThat(ONode.ofJson(providerResponse).get("error").getString()).contains("占位符密钥");
        assertThat(ONode.ofJson(channelResponse).get("success").getBoolean()).isFalse();
        assertThat(ONode.ofJson(channelResponse).get("error").getString()).contains("占位符密钥");
    }
}
