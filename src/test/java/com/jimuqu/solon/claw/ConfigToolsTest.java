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
        assertThat(ONode.ofJson(response).get("status").getString()).isEqualTo("success");
        assertThat(env.appConfig.getChannels().getWeixin().isEnabled()).isTrue();

        String reactResponse =
                (String) method.invoke(configSetTool, "react.delegateMaxSteps", "24");
        assertThat(ONode.ofJson(reactResponse).get("status").getString()).isEqualTo("success");
        assertThat(env.appConfig.getReact().getDelegateMaxSteps()).isEqualTo(24);

        String summaryResponse =
                (String) method.invoke(configSetTool, "react.summarizationMaxTokens", "48000");
        assertThat(ONode.ofJson(summaryResponse).get("status").getString()).isEqualTo("success");
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
                (String)
                        method.invoke(
                                configSetTool,
                                "solonclaw.terminal.envPassthrough",
                                "TENOR_API_KEY");
        assertThat(ONode.ofJson(allowedResponse).get("status").getString()).isEqualTo("success");
        assertThat(env.appConfig.getTerminal().getEnvPassthrough())
                .containsExactly("TENOR_API_KEY");

        String rejectedResponse =
                (String)
                        method.invoke(
                                configSetTool,
                                "solonclaw.terminal.envPassthrough",
                                "OPENAI_API_KEY");
        assertThat(ONode.ofJson(rejectedResponse).get("status").getString()).isEqualTo("error");
        assertThat(ONode.ofJson(rejectedResponse).get("error").getString())
                .contains("solonclaw.terminal.envPassthrough")
                .contains("OPENAI_API_KEY")
                .contains("high-risk");
        String pathResponse =
                (String) method.invoke(configSetTool, "solonclaw.terminal.envPassthrough", "PATH");
        assertThat(ONode.ofJson(pathResponse).get("status").getString()).isEqualTo("error");
        assertThat(ONode.ofJson(pathResponse).get("error").getString())
                .contains("solonclaw.terminal.envPassthrough")
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
                                configSetSecretTool, "providers.default.apiKey", "your-api-key");

        assertThat(ONode.ofJson(response).get("status").getString()).isEqualTo("error");
        assertThat(ONode.ofJson(response).get("error").getString()).contains("占位符密钥");

        String channelSecretResponse =
                (String)
                        method.invoke(
                                configSetSecretTool, "solonclaw.channels.weixin.token", "dummy");
        assertThat(ONode.ofJson(channelSecretResponse).get("status").getString())
                .isEqualTo("error");
        assertThat(ONode.ofJson(channelSecretResponse).get("error").getString()).contains("占位符密钥");
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

        assertThat(ONode.ofJson(providerResponse).get("status").getString()).isEqualTo("error");
        assertThat(ONode.ofJson(providerResponse).get("error").getString())
                .contains("config_set_secret");
        assertThat(ONode.ofJson(channelResponse).get("status").getString()).isEqualTo("error");
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

        assertThat(ONode.ofJson(response).get("status").getString()).isEqualTo("error");
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

    /** 验证配置工具只暴露当前入口，并保持密钥读写结果脱敏。 */
    @Test
    void shouldExposeCurrentConfigToolsAndRedactSecretReads() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object configGetTool = null;
        Object configSetTool = null;
        Object configSetSecretTool = null;
        for (Object tool : env.toolRegistry.resolveEnabledTools("MEMORY:chat-1:user-1")) {
            for (Method method : tool.getClass().getMethods()) {
                assertThat(method.getName())
                        .isNotIn("configRead", "configWrite", "configUpdateSecret");
                if ("configGet".equals(method.getName())) {
                    configGetTool = tool;
                } else if ("configSet".equals(method.getName())) {
                    configSetTool = tool;
                } else if ("configSetSecret".equals(method.getName())) {
                    configSetSecretTool = tool;
                }
            }
        }

        assertThat(configGetTool).isNotNull();
        assertThat(configSetTool).isNotNull();
        assertThat(configSetSecretTool).isNotNull();

        Method write = configSetTool.getClass().getMethod("configSet", String.class, String.class);
        ONode writeResponse =
                ONode.ofJson(
                        (String) write.invoke(configSetTool, "channels.weixin.enabled", "true"));
        assertThat(writeResponse.get("status").getString()).isEqualTo("success");
        assertThat(env.appConfig.getChannels().getWeixin().isEnabled()).isTrue();

        Method secret =
                configSetSecretTool
                        .getClass()
                        .getMethod("configSetSecret", String.class, String.class);
        ONode secretResponse =
                ONode.ofJson(
                        (String)
                                secret.invoke(
                                        configSetSecretTool,
                                        "providers.default.apiKey",
                                        "sk-test-real-secret-12345"));
        assertThat(secretResponse.get("status").getString()).isEqualTo("success");
        assertThat(secretResponse.toString()).doesNotContain("sk-test-real-secret-12345");

        Method read = configGetTool.getClass().getMethod("configGet", String.class);
        ONode providerSecretRead =
                ONode.ofJson((String) read.invoke(configGetTool, "providers.default.apiKey"));
        assertThat(providerSecretRead.get("status").getString()).isEqualTo("success");
        assertThat(providerSecretRead.get("value").getString()).isEqualTo("***");
        assertThat(providerSecretRead.get("redacted").getBoolean()).isTrue();
        assertThat(providerSecretRead.get("preview").getString())
                .isEqualTo("providers.default.apiKey=***");
        assertThat(providerSecretRead.toString()).doesNotContain("sk-test-real-secret-12345");

        ONode providerModelRead =
                ONode.ofJson((String) read.invoke(configGetTool, "providers.default.defaultModel"));
        assertThat(providerModelRead.get("status").getString()).isEqualTo("success");
        assertThat(providerModelRead.get("value").getString()).isNotBlank();

        Method writeSecret =
                configSetSecretTool
                        .getClass()
                        .getMethod("configSetSecret", String.class, String.class);
        ONode writeSecretResponse =
                ONode.ofJson(
                        (String)
                                writeSecret.invoke(
                                        configSetSecretTool,
                                        "solonclaw.gateway.injectionSecret",
                                        "gateway-secret-12345"));
        assertThat(writeSecretResponse.get("status").getString()).isEqualTo("success");
        assertThat(writeSecretResponse.toString()).doesNotContain("gateway-secret-12345");

        ONode readResponse =
                ONode.ofJson((String) read.invoke(configGetTool, "gateway.injectionSecret"));
        assertThat(readResponse.get("status").getString()).isEqualTo("success");
        assertThat(readResponse.get("value").getString()).isEqualTo("***");
        assertThat(readResponse.get("redacted").getBoolean()).isTrue();
        assertThat(readResponse.get("preview").getString())
                .isEqualTo("gateway.injectionSecret=***");
        assertThat(readResponse.toString()).doesNotContain("sk-test-real-secret-12345");
    }

    @Test
    void shouldExposeConfigEnvProbeToolWithoutLeakingSecretLikeNames() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object configGetTool = null;
        for (Object tool : env.toolRegistry.resolveEnabledTools("MEMORY:chat-1:user-1")) {
            for (Method method : tool.getClass().getMethods()) {
                if ("configEnvProbe".equals(method.getName())) {
                    configGetTool = tool;
                    break;
                }
            }
            if (configGetTool != null) {
                break;
            }
        }

        assertThat(configGetTool).isNotNull();
        Method method = configGetTool.getClass().getMethod("configEnvProbe", String.class);
        ONode response =
                ONode.ofJson(
                        (String)
                                method.invoke(
                                        configGetTool,
                                        "[\"PATH\",\"TENOR_API_KEY\",\"OPENAI_API_KEY\",\"_SOLONCLAW_FORCE_CUSTOM_TOKEN\",\"ghp_probe1234567890\"]"));

        assertThat(response.get("status").getString()).isEqualTo("success");
        assertThat(response.get("requestedCount").getInt()).isEqualTo(5);
        assertThat(response.get("decisionCategories").toJson())
                .contains("force")
                .contains("provider-blocked")
                .contains("high-risk");
        String json = response.toJson();
        assertThat(json)
                .contains("PATH")
                .contains("decision")
                .contains("provider-blocked")
                .contains("force")
                .contains("***")
                .doesNotContain("ghp_probe1234567890");
    }

    @Test
    void shouldKeepRegularWritesSeparateFromSecretUpdates() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object configSetTool = null;
        Object configSetSecretTool = null;
        Object configGetTool = null;
        for (Object tool : env.toolRegistry.resolveEnabledTools("MEMORY:chat-1:user-1")) {
            for (Method method : tool.getClass().getMethods()) {
                if ("configSet".equals(method.getName())) {
                    configSetTool = tool;
                } else if ("configSetSecret".equals(method.getName())) {
                    configSetSecretTool = tool;
                } else if ("configGet".equals(method.getName())) {
                    configGetTool = tool;
                }
            }
        }

        assertThat(configSetTool).isNotNull();
        assertThat(configSetSecretTool).isNotNull();
        assertThat(configGetTool).isNotNull();

        Method write = configSetTool.getClass().getMethod("configSet", String.class, String.class);
        ONode nonSecretWrite =
                ONode.ofJson((String) write.invoke(configSetTool, "react.delegateMaxSteps", "25"));
        assertThat(nonSecretWrite.get("status").getString()).isEqualTo("success");
        assertThat(env.appConfig.getReact().getDelegateMaxSteps()).isEqualTo(25);

        ONode rejectedSecretWrite =
                ONode.ofJson(
                        (String)
                                write.invoke(
                                        configSetTool,
                                        "providers.default.apiKey",
                                        "sk-regular-write-secret-12345"));
        assertThat(rejectedSecretWrite.get("status").getString()).isEqualTo("error");
        assertThat(rejectedSecretWrite.get("error").getString())
                .contains("config_set_secret")
                .doesNotContain("sk-regular-write-secret-12345");

        Method writeSecret =
                configSetSecretTool
                        .getClass()
                        .getMethod("configSetSecret", String.class, String.class);
        ONode rejectedNonSecret =
                ONode.ofJson(
                        (String)
                                writeSecret.invoke(
                                        configSetSecretTool,
                                        "providers.default.defaultModel",
                                        "gpt-5"));
        assertThat(rejectedNonSecret.get("status").getString()).isEqualTo("error");
        assertThat(rejectedNonSecret.get("error").getString()).contains("不是密钥配置");

        ONode secretWrite =
                ONode.ofJson(
                        (String)
                                writeSecret.invoke(
                                        configSetSecretTool,
                                        "providers.default.apiKey",
                                        "sk-secret-update-real-12345"));
        assertThat(secretWrite.get("status").getString()).isEqualTo("success");
        assertThat(secretWrite.toString()).doesNotContain("sk-secret-update-real-12345");
        assertThat(env.appConfig.getProviders().get("default").getApiKey())
                .isEqualTo("sk-secret-update-real-12345");
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("apiKey: sk-secret-update-real-12345");

        Method read = configGetTool.getClass().getMethod("configGet", String.class);
        ONode secretRead =
                ONode.ofJson((String) read.invoke(configGetTool, "providers.default.apiKey"));
        assertThat(secretRead.get("status").getString()).isEqualTo("success");
        assertThat(secretRead.get("value").getString()).isEqualTo("***");
        assertThat(secretRead.get("redacted").getBoolean()).isTrue();
        assertThat(secretRead.toString()).doesNotContain("sk-secret-update-real-12345");
    }
}
