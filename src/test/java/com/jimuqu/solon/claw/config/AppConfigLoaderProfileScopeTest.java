package com.jimuqu.solon.claw.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.profile.ProfileEnvironmentLoader;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.RuntimeConfigResolverSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

/** 验证 Profile detached 配置只读取当前作用域内的项目环境开关。 */
public class AppConfigLoaderProfileScopeTest {
    @Test
    void shouldLoadIndependentEnvironmentOverridesForProfilesAAndB() throws Exception {
        Path root = Files.createTempDirectory("profile-config-loader");
        Props propsA = props(root.resolve("profiles/a"), false, true);
        Props propsB = props(root.resolve("profiles/b"), true, false);

        AppConfig configA;
        Map<String, String> environmentA = new LinkedHashMap<String, String>();
        environmentA.put("SOLONCLAW_ALLOW_PRIVATE_URLS", "true");
        environmentA.put("SOLONCLAW_GATEWAY_MULTIPLEX_PROFILES", "false");
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open("a", root.resolve("profiles/a"), environmentA, null)) {
            configA = AppConfig.loadDetached(propsA);
        }

        AppConfig configB;
        Map<String, String> environmentB = new LinkedHashMap<String, String>();
        environmentB.put("SOLONCLAW_ALLOW_PRIVATE_URLS", "false");
        environmentB.put("SOLONCLAW_GATEWAY_MULTIPLEX_PROFILES", "true");
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open("b", root.resolve("profiles/b"), environmentB, null)) {
            configB = AppConfig.loadDetached(propsB);
        }

        assertThat(configA.getSecurity().isAllowPrivateUrls()).isTrue();
        assertThat(configA.getGateway().isMultiplexProfiles()).isFalse();
        assertThat(configB.getSecurity().isAllowPrivateUrls()).isFalse();
        assertThat(configB.getGateway().isMultiplexProfiles()).isTrue();
    }

    @Test
    void shouldKeepUnscopedProcessEnvironmentBehavior() throws Exception {
        Path home = Files.createTempDirectory("default-config-loader");
        Props props = props(home, false, true);
        AppConfig config = AppConfig.loadDetached(props);

        String allowPrivate = System.getenv("SOLONCLAW_ALLOW_PRIVATE_URLS");
        boolean expectedAllowPrivate =
                allowPrivate == null || allowPrivate.trim().length() == 0
                        ? false
                        : isTrue(allowPrivate);
        boolean expectedMultiplex =
                AppConfigLoader.resolveMultiplexProfiles(
                        System.getenv("SOLONCLAW_GATEWAY_MULTIPLEX_PROFILES"), true);

        assertThat(config.getSecurity().isAllowPrivateUrls()).isEqualTo(expectedAllowPrivate);
        assertThat(config.getGateway().isMultiplexProfiles()).isEqualTo(expectedMultiplex);
    }

    /** 命名 Profile 必须引用根工作区 Provider，且本地重复注册表不能覆盖全局定义。 */
    @Test
    void shouldUseRootProviderRegistryForNamedProfile() throws Exception {
        Path root = Files.createTempDirectory("profile-global-providers");
        Path profile = root.resolve("profiles/writer");
        Files.createDirectories(profile);
        Files.writeString(
                root.resolve("config.yml"),
                "providers:\n"
                        + "  dwf:\n"
                        + "    baseUrl: https://global.example/v1\n"
                        + "    defaultModel: qwen3.7-plus\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: dwf\n"
                        + "  default: qwen3.7-plus\n");
        Files.writeString(root.resolve(".env"), "SOLONCLAW_PROVIDER_DWF_API_KEY=root-secret\n");
        Files.writeString(
                profile.resolve("config.yml"),
                "providers:\n"
                        + "  evil:\n"
                        + "    baseUrl: https://profile.example/v1\n"
                        + "    defaultModel: wrong-model\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: dwf\n"
                        + "  default: qwen3.7-plus\n");
        Files.writeString(
                profile.resolve(".env"), "SOLONCLAW_PROVIDER_DWF_API_KEY=profile-secret\n");

        AppConfig config = AppConfig.loadDetached(props(profile, false, true));

        assertThat(config.getProviders()).containsKey("dwf").doesNotContainKey("evil");
        assertThat(config.getProviders().get("dwf").getBaseUrl())
                .isEqualTo("https://global.example/v1");
        assertThat(config.getProviders().get("dwf").getApiKey()).isEqualTo("root-secret");
        assertThat(config.getModel().getProviderKey()).isEqualTo("dwf");
    }

    /** Provider 凭据只接受当前项目专属键，不再读取协议通用环境变量。 */
    @Test
    void shouldOnlyLoadExplicitSolonclawProviderCredential() throws Exception {
        Path root = Files.createTempDirectory("profile-explicit-provider-env");
        Files.writeString(
                root.resolve(".env"),
                "OPENAI_API_KEY=legacy-openai\n"
                        + "GEMINI_API_KEY=legacy-gemini\n"
                        + "GOOGLE_API_KEY=legacy-google\n"
                        + "ANTHROPIC_API_KEY=legacy-anthropic\n");
        AppConfig config = providerConfig("dwf", "openai");

        ProfileEnvironmentLoader.applyGlobalProviderCredentials(config, root);

        assertThat(config.getProviders().get("dwf").getApiKey()).isBlank();

        Files.writeString(root.resolve(".env"), "SOLONCLAW_PROVIDER_DWF_API_KEY=current-key\n");
        ProfileEnvironmentLoader.applyGlobalProviderCredentials(config, root);

        assertThat(config.getProviders().get("dwf").getApiKey()).isEqualTo("current-key");
    }

    /** 语音凭据只接受当前项目专属键，不再隐式复用模型 Provider 密钥。 */
    @Test
    void shouldOnlyLoadExplicitSolonclawSpeechCredential() {
        AppConfig config = new AppConfig();
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("OPENAI_API_KEY", "legacy-openai");

        ProfileEnvironmentLoader.apply(config, environment);

        assertThat(config.getSpeech().getTts().getApiKey()).isBlank();
        assertThat(config.getSpeech().getStt().getApiKey()).isBlank();

        environment.put("SOLONCLAW_SPEECH_API_KEY", "speech-key");
        ProfileEnvironmentLoader.apply(config, environment);

        assertThat(config.getSpeech().getTts().getApiKey()).isEqualTo("speech-key");
        assertThat(config.getSpeech().getStt().getApiKey()).isEqualTo("speech-key");
    }

    /** 每个 Profile 的运行时解析器必须独立重载自己的配置，并且不能替换进程全局解析器。 */
    @Test
    void shouldKeepRuntimeConfigResolversIndependentAcrossProfiles() throws Exception {
        Path previousHome =
                RuntimeConfigResolver.getInstance()
                        .configFile()
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                        .getParent();
        Path root = Files.createTempDirectory("profile-runtime-resolvers");
        Path profileA = root.resolve("profiles/a");
        Path profileB = root.resolve("profiles/b");
        Files.createDirectories(profileA);
        Files.createDirectories(profileB);
        Files.writeString(profileA.resolve("config.yml"), "model:\n  default: model-a\n");
        Files.writeString(profileB.resolve("config.yml"), "model:\n  default: model-b\n");

        try {
            RuntimeConfigResolver global = RuntimeConfigResolver.initialize(profileA.toString());
            AppConfig configB = new AppConfig();
            configB.getRuntime().setHome(profileB.toString());
            RuntimeConfigResolver independent = RuntimeConfigResolverSupport.fromAppConfig(configB);

            assertThat(global.get("model.default")).isEqualTo("model-a");
            assertThat(independent.get("model.default")).isEqualTo("model-b");
            assertThat(RuntimeConfigResolver.getInstance()).isSameAs(global);

            Files.writeString(
                    profileA.resolve("config.yml"), "model:\n  default: model-a-reloaded\n");
            Files.writeString(
                    profileB.resolve("config.yml"), "model:\n  default: model-b-reloaded\n");
            global.reload();
            independent.reload();

            assertThat(global.get("model.default")).isEqualTo("model-a-reloaded");
            assertThat(independent.get("model.default")).isEqualTo("model-b-reloaded");
            assertThat(RuntimeConfigResolver.getInstance()).isSameAs(global);
        } finally {
            RuntimeConfigResolver.initialize(previousHome.toString());
        }
    }

    /** 创建只声明本测试两个配置开关的独立 Props。 */
    private Props props(Path home, boolean allowPrivateUrls, boolean multiplexProfiles) {
        Props props = new Props();
        props.put("solonclaw.workspace", home.toAbsolutePath().normalize().toString());
        props.put("security.allowPrivateUrls", String.valueOf(allowPrivateUrls));
        props.put("solonclaw.gateway.multiplexProfiles", String.valueOf(multiplexProfiles));
        return props;
    }

    /** 创建仅含一个 Provider 的最小配置，用于验证根工作区凭据加载。 */
    private AppConfig providerConfig(String providerKey, String dialect) {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDialect(dialect);
        config.getProviders().put(providerKey, provider);
        config.getModel().setProviderKey(providerKey);
        return config;
    }

    /** 复现配置加载器认可的 true 令牌，用于计算当前进程环境预期值。 */
    private boolean isTrue(String raw) {
        String value = raw == null ? "" : raw.trim();
        return "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value);
    }
}
