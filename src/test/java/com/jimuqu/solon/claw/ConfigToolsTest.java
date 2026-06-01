package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
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
    void shouldRejectHighRiskEnvPassthroughFromConfigSetTool() throws Exception {
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
        String allowedResponse =
                (String) method.invoke(configSetTool, "terminal.env_passthrough", "TENOR_API_KEY");
        assertThat(ONode.ofJson(allowedResponse).get("success").getBoolean()).isTrue();
        assertThat(env.appConfig.getTerminal().getEnvPassthrough())
                .containsExactly("TENOR_API_KEY");

        String rejectedResponse =
                (String) method.invoke(configSetTool, "terminal.env_passthrough", "OPENAI_API_KEY");
        assertThat(ONode.ofJson(rejectedResponse).get("success").getBoolean()).isFalse();
        assertThat(ONode.ofJson(rejectedResponse).get("error").getString())
                .contains("terminal.envPassthrough")
                .contains("OPENAI_API_KEY")
                .contains("high-risk");
        String pathResponse =
                (String) method.invoke(configSetTool, "terminal.envPassthrough", "PATH");
        assertThat(ONode.ofJson(pathResponse).get("success").getBoolean()).isFalse();
        assertThat(ONode.ofJson(pathResponse).get("error").getString())
                .contains("terminal.envPassthrough")
                .contains("PATH")
                .contains("high-risk");
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("TENOR_API_KEY")
                .doesNotContain("OPENAI_API_KEY");
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
        assertThat(ONode.ofJson(providerResponse).get("error").getString())
                .contains("config_set_secret");
        assertThat(ONode.ofJson(channelResponse).get("success").getBoolean()).isFalse();
        assertThat(ONode.ofJson(channelResponse).get("error").getString())
                .contains("config_set_secret");
    }

    @Test
    void shouldRedactSecretsFromConfigToolErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object configGetTool = null;
        for (Object tool : env.toolRegistry.resolveEnabledTools("MEMORY:chat-1:user-1")) {
            for (Method method : tool.getClass().getMethods()) {
                if ("configGet".equals(method.getName())) {
                    configGetTool = tool;
                    break;
                }
            }
            if (configGetTool != null) {
                break;
            }
        }

        assertThat(configGetTool).isNotNull();
        Method method = configGetTool.getClass().getMethod("configGet", String.class);
        String response =
                (String) method.invoke(configGetTool, "providers.ghp_1234567890abcdef.apiKey");

        assertThat(ONode.ofJson(response).get("success").getBoolean()).isFalse();
        assertThat(ONode.ofJson(response).get("error").getString())
                .contains("providers.***.apiKey")
                .doesNotContain("ghp_1234567890abcdef");
    }

    @Test
    void shouldRedactSecretsFromConfigToolSuccessKeys() throws Exception {
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
                                "ghp_configvaluesecret12345");

        assertThat(response)
                .contains("providers.default.apiKey")
                .doesNotContain("ghp_configvaluesecret12345");
    }

    @Test
    void shouldExposeConfigAliasesAndRedactSecretReads() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object configGetTool = null;
        Object configSetTool = null;
        Object configSetSecretTool = null;
        for (Object tool : env.toolRegistry.resolveEnabledTools("MEMORY:chat-1:user-1")) {
            for (Method method : tool.getClass().getMethods()) {
                if ("configRead".equals(method.getName())) {
                    configGetTool = tool;
                } else if ("configWrite".equals(method.getName())) {
                    configSetTool = tool;
                } else if ("configUpdateSecret".equals(method.getName())) {
                    configSetSecretTool = tool;
                }
            }
        }

        assertThat(configGetTool).isNotNull();
        assertThat(configSetTool).isNotNull();
        assertThat(configSetSecretTool).isNotNull();

        Method write =
                configSetTool.getClass().getMethod("configWrite", String.class, String.class);
        ONode writeResponse =
                ONode.ofJson(
                        (String) write.invoke(configSetTool, "channels.weixin.enabled", "true"));
        assertThat(writeResponse.get("success").getBoolean()).isTrue();
        assertThat(env.appConfig.getChannels().getWeixin().isEnabled()).isTrue();

        Method secret =
                configSetSecretTool
                        .getClass()
                        .getMethod("configUpdateSecret", String.class, String.class);
        ONode secretResponse =
                ONode.ofJson(
                        (String)
                                secret.invoke(
                                        configSetSecretTool,
                                        "providers.default.apiKey",
                                        "sk-test-real-secret-12345"));
        assertThat(secretResponse.get("success").getBoolean()).isTrue();
        assertThat(secretResponse.toString()).doesNotContain("sk-test-real-secret-12345");

        Method read = configGetTool.getClass().getMethod("configRead", String.class);
        ONode providerSecretRead =
                ONode.ofJson((String) read.invoke(configGetTool, "providers.default.apiKey"));
        assertThat(providerSecretRead.get("success").getBoolean()).isTrue();
        assertThat(providerSecretRead.get("value").getString()).isEqualTo("***");
        assertThat(providerSecretRead.get("redacted").getBoolean()).isTrue();
        assertThat(providerSecretRead.get("preview").getString())
                .isEqualTo("providers.default.apiKey=***");
        assertThat(providerSecretRead.toString()).doesNotContain("sk-test-real-secret-12345");

        ONode providerModelRead =
                ONode.ofJson((String) read.invoke(configGetTool, "providers.default.defaultModel"));
        assertThat(providerModelRead.get("success").getBoolean()).isTrue();
        assertThat(providerModelRead.get("value").getString()).isNotBlank();

        Method writeSecret =
                configSetSecretTool
                        .getClass()
                        .getMethod("configUpdateSecret", String.class, String.class);
        ONode writeSecretResponse =
                ONode.ofJson(
                        (String)
                                writeSecret.invoke(
                                        configSetSecretTool,
                                        "solonclaw.gateway.injectionSecret",
                                        "gateway-secret-12345"));
        assertThat(writeSecretResponse.get("success").getBoolean()).isTrue();
        assertThat(writeSecretResponse.toString()).doesNotContain("gateway-secret-12345");

        ONode readResponse =
                ONode.ofJson((String) read.invoke(configGetTool, "gateway.injectionSecret"));
        assertThat(readResponse.get("success").getBoolean()).isTrue();
        assertThat(readResponse.get("value").getString()).isEqualTo("***");
        assertThat(readResponse.get("redacted").getBoolean()).isTrue();
        assertThat(readResponse.get("preview").getString()).isEqualTo("gateway.injectionSecret=***");
        assertThat(readResponse.toString()).doesNotContain("sk-test-real-secret-12345");
    }
}
