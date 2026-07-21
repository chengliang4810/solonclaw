package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.profile.ProfileMutationLock;
import com.jimuqu.solon.claw.web.profile.DashboardProfileConfigFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 统一承载模型、渠道和初始化命令的配置写入逻辑，避免 TUI 与 slash 各自复制一套规则。 */
public class RuntimeSetupService {
    /** 应用配置，用于定位运行时目录和读取默认 provider 信息。 */
    private final AppConfig appConfig;

    /**
     * 创建运行时初始化配置服务。
     *
     * @param appConfig 当前应用配置。
     */
    public RuntimeSetupService(AppConfig appConfig) {
        this.appConfig = appConfig == null ? new AppConfig() : appConfig;
    }

    /**
     * 写入默认模型 provider 配置。
     *
     * @param request 模型 provider 配置请求。
     * @return 已脱敏的写入结果。
     */
    public SetupResult configureModel(ModelSetupRequest request) {
        return configureProvider(request, true);
    }

    /**
     * 原子写入 Provider 配置，并按需将其设为当前 Profile 的默认模型。
     *
     * @param request 模型 Provider 配置请求。
     * @param activate 是否同时更新当前 Profile 默认模型。
     * @return 已脱敏的写入结果。
     */
    public SetupResult configureProvider(ModelSetupRequest request, boolean activate) {
        if (request == null
                || StrUtil.isBlank(request.getProviderKey())
                || StrUtil.isBlank(request.getBaseUrl())
                || StrUtil.isBlank(request.getApiKey())
                || StrUtil.isBlank(request.getModel())
                || StrUtil.isBlank(request.getDialect())) {
            return SetupResult.error("missing_model_setup_fields");
        }
        try {
            LlmProviderSupport.validateBaseUrl(request.getBaseUrl());
        } catch (IllegalArgumentException e) {
            return SetupResult.error("invalid_base_url", e.getMessage());
        }
        String dialect = LlmProviderSupport.normalizeDialect(request.getDialect());
        if (!LlmProviderSupport.isSupportedDialect(dialect)) {
            return SetupResult.error("unsupported_dialect", request.getDialect());
        }
        String providerKey = normalizeText(request.getProviderKey());
        String model = normalizeText(request.getModel());
        String apiKey = normalizeText(request.getApiKey());
        if (SecretValueGuard.isPlaceholderSecret(apiKey)) {
            return SetupResult.error("placeholder_secret", "apiKey 不能使用示例或占位符密钥。");
        }
        String providerName =
                StrUtil.blankToDefault(normalizeText(request.getProviderName()), providerKey);
        String baseUrl = normalizeText(request.getBaseUrl());
        try {
            writeProviderConfiguration(
                    providerKey, providerName, baseUrl, apiKey, model, dialect, activate);
        } catch (Exception e) {
            return SetupResult.error("model_setup_failed", ErrorTextSupport.safeError(e));
        }
        applyModelSetupToAppConfig(
                providerKey, providerName, baseUrl, apiKey, model, dialect, activate);

        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("provider", providerKey);
        values.put("model", model);
        values.put("baseUrl", SecretRedactor.maskUrl(baseUrl));
        values.put("apiKey", "***");
        values.put("dialect", dialect);
        return SetupResult.ok("model", values);
    }

    /**
     * 原子切换当前 Profile 的默认模型。
     *
     * @param providerKey 已登记 Provider 键。
     * @param model 已登记模型名。
     * @return 模型切换结果。
     */
    public SetupResult selectDefaultModel(String providerKey, String model) {
        try {
            LlmProviderService.ResolvedProvider resolved =
                    new LlmProviderService(appConfig).resolveProvider(providerKey, model);
            new ProfileMutationLock(appConfig)
                    .withLock(
                            () -> {
                                writeModelReference(
                                        currentConfigFile(),
                                        resolved.getProviderKey(),
                                        resolved.getModel());
                                return null;
                            });
            applySelectedModelToAppConfig(resolved.getProviderKey(), resolved.getModel());
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put("provider", resolved.getProviderKey());
            values.put("model", resolved.getModel());
            return SetupResult.ok("model", values);
        } catch (Exception e) {
            return SetupResult.error("model_selection_failed", ErrorTextSupport.safeError(e));
        }
    }

    /**
     * 原子清除全局 Provider 注册表中的 API Key。
     *
     * @param providerKey Provider 键。
     * @return 凭据清理结果。
     */
    public SetupResult clearProviderCredential(String providerKey) {
        String key = normalizeText(providerKey);
        if (StrUtil.isBlank(key)) {
            return SetupResult.error("missing_provider");
        }
        try {
            new ProfileMutationLock(appConfig)
                    .withLock(
                            () -> {
                                Path configFile = globalConfigFile();
                                synchronized (DashboardProfileConfigFile.lockFor(configFile)) {
                                    DashboardProfileConfigFile config =
                                            new DashboardProfileConfigFile(configFile);
                                    Map<String, Object> root = config.readRoot();
                                    Map<String, Object> providers = mutableMap(root, "providers");
                                    Object rawProvider = providers.get(key);
                                    if (!(rawProvider instanceof Map)) {
                                        throw new IllegalArgumentException("Provider 不存在：" + key);
                                    }
                                    Map<String, Object> provider =
                                            BasicValueSupport.sanitizeMap((Map<?, ?>) rawProvider);
                                    provider.remove("apiKey");
                                    providers.put(key, provider);
                                    config.writeRoot(root);
                                }
                                return null;
                            });
            AppConfig.ProviderConfig provider = appConfig.getProviders().get(key);
            if (provider != null) {
                provider.setApiKey("");
            }
            if (StrUtil.equals(key, appConfig.getModel().getProviderKey())) {
                appConfig.getLlm().setApiKey("");
            }
            return SetupResult.ok("provider", Collections.singletonMap("provider", key));
        } catch (Exception e) {
            return SetupResult.error(
                    "provider_credential_clear_failed", ErrorTextSupport.safeError(e));
        }
    }

    /**
     * 原子替换当前 Profile 的备用模型链。
     *
     * @param items Provider 与模型成对列表。
     * @return 备用链写入结果。
     */
    public SetupResult updateFallbackProviders(List<? extends Map<String, String>> items) {
        try {
            List<Object> nodes = new ArrayList<Object>();
            List<AppConfig.FallbackProviderConfig> runtimeValues =
                    new ArrayList<AppConfig.FallbackProviderConfig>();
            LlmProviderService providers = new LlmProviderService(appConfig);
            if (items != null) {
                for (Map<String, String> item : items) {
                    if (item == null || StrUtil.isBlank(item.get("provider"))) {
                        continue;
                    }
                    LlmProviderService.ResolvedProvider resolved =
                            providers.resolveProvider(item.get("provider"), item.get("model"));
                    Map<String, Object> node = new LinkedHashMap<String, Object>();
                    node.put("provider", resolved.getProviderKey());
                    node.put("model", resolved.getModel());
                    nodes.add(node);
                    AppConfig.FallbackProviderConfig runtimeValue =
                            new AppConfig.FallbackProviderConfig();
                    runtimeValue.setProvider(resolved.getProviderKey());
                    runtimeValue.setModel(resolved.getModel());
                    runtimeValues.add(runtimeValue);
                }
            }
            new ProfileMutationLock(appConfig)
                    .withLock(
                            () -> {
                                Path configFile = currentConfigFile();
                                synchronized (DashboardProfileConfigFile.lockFor(configFile)) {
                                    DashboardProfileConfigFile config =
                                            new DashboardProfileConfigFile(configFile);
                                    Map<String, Object> root = config.readRoot();
                                    root.put("fallbackProviders", nodes);
                                    config.writeRoot(root);
                                }
                                return null;
                            });
            appConfig.setFallbackProviders(runtimeValues);
            return SetupResult.ok(
                    "fallback",
                    Collections.singletonMap("count", String.valueOf(runtimeValues.size())));
        } catch (Exception e) {
            return SetupResult.error("fallback_update_failed", ErrorTextSupport.safeError(e));
        }
    }

    /**
     * 写入国内消息渠道配置。
     *
     * @param channel 渠道标识。
     * @param values 要写入的渠道配置字段。
     * @return 已脱敏的写入结果。
     */
    public SetupResult configureGatewayChannel(String channel, Map<String, String> values) {
        String normalizedChannel = normalizeText(channel).toLowerCase(java.util.Locale.ROOT);
        if (!RuntimeSetupSpec.domesticChannels().contains(normalizedChannel)) {
            return SetupResult.error("unsupported_channel", normalizedChannel);
        }
        if (values == null || values.isEmpty()) {
            return SetupResult.error("missing_channel_values");
        }
        for (String key : values.keySet()) {
            if (!RuntimeSetupSpec.isAllowedChannelKey(normalizedChannel, key)) {
                return SetupResult.error("unsupported_channel_field", key);
            }
        }
        RuntimeConfigResolver resolver = configResolver();
        String prefix = "solonclaw.channels." + normalizedChannel + ".";
        Map<String, String> safeValues = new LinkedHashMap<String, String>();
        Map<String, String> normalizedValues = new LinkedHashMap<String, String>();
        safeValues.put("channel", normalizedChannel);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = normalizeText(entry.getKey());
            String value = normalizeText(entry.getValue());
            if (isSecretSetupKey(key) && SecretValueGuard.isPlaceholderSecret(value)) {
                return SetupResult.error("placeholder_secret", key + " 不能使用示例或占位符密钥。");
            }
            normalizedValues.put(key, value);
            safeValues.put(key, safeConfigValue(key, value));
        }
        for (Map.Entry<String, String> entry : normalizedValues.entrySet()) {
            resolver.setFileValue(prefix + entry.getKey(), entry.getValue());
        }
        applyGatewayChannelSetupToAppConfig(normalizedChannel, normalizedValues);
        return SetupResult.ok("gateway", safeValues);
    }

    /** 读取当前工作区配置解析器。 */
    private RuntimeConfigResolver configResolver() {
        return RuntimeConfigResolverSupport.fromAppConfig(appConfig);
    }

    /**
     * 将 setup 写入同步到当前进程的 AppConfig，保证 doctor 和下一次模型请求立即看到新配置。
     *
     * @param providerKey provider 键。
     * @param providerName provider 展示名。
     * @param baseUrl 模型 API 基础地址。
     * @param apiKey 模型 API Key。
     * @param model 默认模型。
     * @param dialect 协议方言。
     * @param activate 是否更新当前 Profile 默认模型。
     */
    private void applyModelSetupToAppConfig(
            String providerKey,
            String providerName,
            String baseUrl,
            String apiKey,
            String model,
            String dialect,
            boolean activate) {
        if (appConfig.getProviders() == null) {
            appConfig.setProviders(new java.util.LinkedHashMap<String, AppConfig.ProviderConfig>());
        }
        AppConfig.ProviderConfig provider = appConfig.getProviders().get(providerKey);
        if (provider == null) {
            provider = new AppConfig.ProviderConfig();
            appConfig.getProviders().put(providerKey, provider);
        }
        provider.setName(providerName);
        provider.setBaseUrl(baseUrl);
        provider.setApiKey(apiKey);
        provider.setDefaultModel(model);
        provider.setModels(modelsWithDefault(provider, model));
        provider.setDialect(dialect);
        if (!activate) {
            return;
        }
        appConfig.getModel().setProviderKey(providerKey);
        appConfig.getModel().setDefault(model);
        appConfig.getLlm().setProvider(providerKey);
        appConfig.getLlm().setDialect(dialect);
        appConfig.getLlm().setApiUrl(LlmProviderSupport.buildApiUrl(baseUrl, dialect));
        appConfig.getLlm().setApiKey(apiKey);
        appConfig.getLlm().setModel(model);
    }

    /**
     * 在共享 Profile 变更锁内写入全局 Provider，并按需更新当前 Profile 模型引用。
     *
     * @param providerKey Provider 键。
     * @param providerName Provider 展示名。
     * @param baseUrl Provider 基础地址。
     * @param apiKey Provider API Key。
     * @param model Provider 默认模型。
     * @param dialect Provider 协议方言。
     * @param activate 是否更新当前 Profile 默认模型。
     * @throws Exception 文件锁或配置写入失败。
     */
    private void writeProviderConfiguration(
            String providerKey,
            String providerName,
            String baseUrl,
            String apiKey,
            String model,
            String dialect,
            boolean activate)
            throws Exception {
        new ProfileMutationLock(appConfig)
                .withLock(
                        () -> {
                            Path providerFile = globalConfigFile();
                            Path modelFile = currentConfigFile();
                            if (providerFile.equals(modelFile)) {
                                synchronized (DashboardProfileConfigFile.lockFor(providerFile)) {
                                    DashboardProfileConfigFile config =
                                            new DashboardProfileConfigFile(providerFile);
                                    Map<String, Object> root = config.readRoot();
                                    putProvider(
                                            root,
                                            providerKey,
                                            providerName,
                                            baseUrl,
                                            apiKey,
                                            model,
                                            dialect);
                                    if (activate) {
                                        putModelReference(root, providerKey, model, false);
                                    }
                                    config.writeRoot(root);
                                }
                                return null;
                            }
                            writeProvider(
                                    providerFile,
                                    providerKey,
                                    providerName,
                                    baseUrl,
                                    apiKey,
                                    model,
                                    dialect);
                            if (activate) {
                                writeModelReference(modelFile, providerKey, model);
                            }
                            return null;
                        });
    }

    /** 原子写入一个全局 Provider 节点。 */
    private void writeProvider(
            Path configFile,
            String providerKey,
            String providerName,
            String baseUrl,
            String apiKey,
            String model,
            String dialect) {
        synchronized (DashboardProfileConfigFile.lockFor(configFile)) {
            DashboardProfileConfigFile config = new DashboardProfileConfigFile(configFile);
            Map<String, Object> root = config.readRoot();
            putProvider(root, providerKey, providerName, baseUrl, apiKey, model, dialect);
            config.writeRoot(root);
        }
    }

    /** 原子写入当前 Profile 的模型引用。 */
    private void writeModelReference(Path configFile, String providerKey, String model) {
        synchronized (DashboardProfileConfigFile.lockFor(configFile)) {
            DashboardProfileConfigFile config = new DashboardProfileConfigFile(configFile);
            Map<String, Object> root = config.readRoot();
            putModelReference(root, providerKey, model, !configFile.equals(globalConfigFile()));
            config.writeRoot(root);
        }
    }

    /** 把 Provider 完整配置合并到已加载的 YAML 根映射。 */
    private void putProvider(
            Map<String, Object> root,
            String providerKey,
            String providerName,
            String baseUrl,
            String apiKey,
            String model,
            String dialect) {
        Map<String, Object> providers = mutableMap(root, "providers");
        Map<String, Object> provider = mutableMap(providers, providerKey);
        provider.put("name", providerName);
        provider.put("baseUrl", baseUrl);
        provider.put("apiKey", apiKey);
        provider.put("defaultModel", model);
        provider.put("models", mergeProviderModels(provider, providerKey, model));
        provider.put("dialect", dialect);
    }

    /** 把 Provider 与模型引用写入已加载的 YAML 根映射。 */
    private void putModelReference(
            Map<String, Object> root, String providerKey, String model, boolean namedProfile) {
        Map<String, Object> modelNode = mutableMap(root, "model");
        modelNode.put("providerKey", providerKey);
        modelNode.put("default", model);
        if (namedProfile) {
            root.remove("providers");
        }
    }

    /** 返回可变子映射，并保留已有节点内容。 */
    private Map<String, Object> mutableMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        Map<String, Object> result =
                value instanceof Map
                        ? BasicValueSupport.sanitizeMap((Map<?, ?>) value)
                        : new LinkedHashMap<String, Object>();
        parent.put(key, result);
        return result;
    }

    /** 合并 YAML 和当前进程中的 Provider 模型列表。 */
    private List<String> mergeProviderModels(
            Map<String, Object> providerNode, String providerKey, String defaultModel) {
        java.util.LinkedHashSet<String> models =
                new java.util.LinkedHashSet<String>(
                        modelsWithDefault(appConfig.getProviders().get(providerKey), defaultModel));
        Object rawModels = providerNode.get("models");
        if (rawModels instanceof Iterable) {
            for (Object item : (Iterable<?>) rawModels) {
                String model = normalizeText(item == null ? "" : String.valueOf(item));
                if (StrUtil.isNotBlank(model)) {
                    models.add(model);
                }
            }
        }
        Object rawDefault = providerNode.get("defaultModel");
        String oldDefault = normalizeText(rawDefault == null ? "" : String.valueOf(rawDefault));
        if (StrUtil.isNotBlank(oldDefault)) {
            models.add(oldDefault);
        }
        return new ArrayList<String>(models);
    }

    /** 将已校验模型选择同步到当前进程配置。 */
    private void applySelectedModelToAppConfig(String providerKey, String model) {
        AppConfig.ProviderConfig provider = appConfig.getProviders().get(providerKey);
        appConfig.getModel().setProviderKey(providerKey);
        appConfig.getModel().setDefault(model);
        if (provider == null) {
            return;
        }
        appConfig.getLlm().setProvider(providerKey);
        appConfig.getLlm().setDialect(provider.getDialect());
        appConfig
                .getLlm()
                .setApiUrl(
                        LlmProviderSupport.buildApiUrl(
                                provider.getBaseUrl(), provider.getDialect()));
        appConfig.getLlm().setApiKey(provider.getApiKey());
        appConfig.getLlm().setModel(model);
    }

    /** 返回当前 Profile 的 config.yml。 */
    private Path currentConfigFile() {
        return configResolver().configFile().toPath().toAbsolutePath().normalize();
    }

    /** 返回全局 Provider 注册表所在的 config.yml。 */
    private Path globalConfigFile() {
        Path home = currentConfigFile().getParent();
        Path parent = home == null ? null : home.getParent();
        if (parent != null && "profiles".equals(String.valueOf(parent.getFileName()))) {
            Path root = parent.getParent();
            if (root != null) {
                return root.resolve("config.yml").toAbsolutePath().normalize();
            }
        }
        return currentConfigFile();
    }

    /**
     * 合并 Provider 已登记模型和新的默认模型，并保证默认模型位于首位。
     *
     * @param provider 当前 Provider 配置。
     * @param defaultModel 新默认模型。
     * @return 去空去重后的模型清单。
     */
    private List<String> modelsWithDefault(AppConfig.ProviderConfig provider, String defaultModel) {
        java.util.LinkedHashSet<String> models = new java.util.LinkedHashSet<String>();
        if (StrUtil.isNotBlank(defaultModel)) {
            models.add(defaultModel.trim());
        }
        if (provider != null && provider.getModels() != null) {
            for (String model : provider.getModels()) {
                if (StrUtil.isNotBlank(model)) {
                    models.add(model.trim());
                }
            }
        }
        return new ArrayList<String>(models);
    }

    /**
     * 将渠道 setup 写入同步到当前进程配置，避免 doctor 和网关状态读取到本轮更新前的配置。
     *
     * @param channel 已归一化的渠道标识。
     * @param values 已归一化的字段值。
     */
    private void applyGatewayChannelSetupToAppConfig(String channel, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        AppConfig.ChannelConfig config = channelConfig(channel);
        if (config == null) {
            return;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            applyChannelField(config, entry.getKey(), entry.getValue());
        }
    }

    /**
     * 读取可变渠道配置，必要时初始化渠道配置块。
     *
     * @param channel 渠道标识。
     * @return 对应渠道配置；未知渠道返回 null。
     */
    private AppConfig.ChannelConfig channelConfig(String channel) {
        return ChannelConfigSupport.getOrCreate(appConfig, channel);
    }

    /**
     * 将单个渠道字段写入 ChannelConfig。
     *
     * @param config 渠道配置对象。
     * @param key 字段名。
     * @param value 字段值。
     */
    private void applyChannelField(AppConfig.ChannelConfig config, String key, String value) {
        if ("enabled".equals(key)) {
            config.setEnabled(parseBoolean(value));
        } else if ("appId".equals(key)) {
            config.setAppId(value);
        } else if ("appSecret".equals(key)) {
            config.setAppSecret(value);
        } else if ("domain".equals(key)) {
            config.setDomain(value);
        } else if ("clientId".equals(key)) {
            config.setClientId(value);
        } else if ("clientSecret".equals(key)) {
            config.setClientSecret(value);
        } else if ("robotCode".equals(key)) {
            config.setRobotCode(value);
        } else if ("coolAppCode".equals(key)) {
            config.setCoolAppCode(value);
        } else if ("botId".equals(key)) {
            config.setBotId(value);
        } else if ("secret".equals(key)) {
            config.setSecret(value);
        } else if ("token".equals(key)) {
            config.setToken(value);
        } else if ("accountId".equals(key)) {
            config.setAccountId(value);
        } else if ("baseUrl".equals(key)) {
            config.setBaseUrl(value);
        } else if ("cdnBaseUrl".equals(key)) {
            config.setCdnBaseUrl(value);
        } else if ("websocketUrl".equals(key)) {
            config.setWebsocketUrl(value);
        } else if ("streamUrl".equals(key)) {
            config.setStreamUrl(value);
        } else if ("longPollUrl".equals(key)) {
            config.setLongPollUrl(value);
        } else if ("apiDomain".equals(key)) {
            config.setApiDomain(value);
        } else if ("botOpenId".equals(key)) {
            config.setBotOpenId(value);
        } else if ("botUserId".equals(key)) {
            config.setBotUserId(value);
        } else if ("botName".equals(key)) {
            config.setBotName(value);
        } else if ("dmPolicy".equals(key)) {
            config.setDmPolicy(value);
        } else if ("groupPolicy".equals(key)) {
            config.setGroupPolicy(value);
        } else if ("unauthorizedDmBehavior".equals(key)) {
            config.setUnauthorizedDmBehavior(value);
        } else if ("toolProgress".equals(key)) {
            config.setToolProgress(value);
        } else if ("progressCardTemplateId".equals(key)) {
            config.setProgressCardTemplateId(value);
        } else if ("approvalCardTemplateId".equals(key)) {
            config.setApprovalCardTemplateId(value);
        } else if ("allowedUsers".equals(key)) {
            config.setAllowedUsers(splitList(value));
        } else if ("groupAllowedUsers".equals(key)) {
            config.setGroupAllowedUsers(splitList(value));
        } else if ("allowedChats".equals(key)) {
            config.setAllowedChats(splitList(value));
        } else if ("freeResponseChats".equals(key)) {
            config.setFreeResponseChats(splitList(value));
        } else if ("mentionPatterns".equals(key)) {
            config.setMentionPatterns(splitList(value));
        } else if ("allowAllUsers".equals(key)) {
            config.setAllowAllUsers(parseBoolean(value));
        } else if ("requireMention".equals(key)) {
            config.setRequireMention(parseBoolean(value));
        } else if ("splitMultilineMessages".equals(key)) {
            config.setSplitMultilineMessages(parseBoolean(value));
        } else if ("markdownSupport".equals(key)) {
            config.setMarkdownSupport(parseBoolean(value));
        } else if ("comment.enabled".equals(key)) {
            config.setCommentEnabled(parseBoolean(value));
        } else if ("comment.pairingFile".equals(key)) {
            config.setCommentPairingFile(value);
        } else if ("aiCardStreaming.enabled".equals(key)) {
            config.setAiCardStreamingEnabled(parseBoolean(value));
        } else if ("textBatchDelaySeconds".equals(key)) {
            config.setTextBatchDelaySeconds(parseDouble(value, config.getTextBatchDelaySeconds()));
        } else if ("textBatchSplitDelaySeconds".equals(key)) {
            config.setTextBatchSplitDelaySeconds(
                    parseDouble(value, config.getTextBatchSplitDelaySeconds()));
        } else if ("sendChunkDelaySeconds".equals(key)) {
            config.setSendChunkDelaySeconds(parseDouble(value, config.getSendChunkDelaySeconds()));
        } else if ("sendChunkRetries".equals(key)) {
            config.setSendChunkRetries(parseInt(value, config.getSendChunkRetries()));
        } else if ("sendChunkRetryDelaySeconds".equals(key)) {
            config.setSendChunkRetryDelaySeconds(
                    parseDouble(value, config.getSendChunkRetryDelaySeconds()));
        }
    }

    /**
     * 解析布尔值，兼容命令行常见开关写法。
     *
     * @param value 用户输入值。
     * @return true/false。
     */
    private boolean parseBoolean(String value) {
        String normalized = normalizeText(value).toLowerCase(java.util.Locale.ROOT);
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "y".equals(normalized)
                || "on".equals(normalized);
    }

    /**
     * 解析整数配置，非法值保留当前配置。
     *
     * @param value 用户输入值。
     * @param fallback 当前值。
     * @return 解析后的整数。
     */
    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(normalizeText(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 解析小数配置，非法值保留当前配置。
     *
     * @param value 用户输入值。
     * @param fallback 当前值。
     * @return 解析后的小数。
     */
    private double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(normalizeText(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 将逗号分隔文本解析为列表字段。
     *
     * @param value 用户输入值。
     * @return 去空白后的列表。
     */
    private List<String> splitList(String value) {
        List<String> result = new ArrayList<String>();
        for (String item : StrUtil.nullToEmpty(value).split(",")) {
            String normalized = normalizeText(item);
            if (StrUtil.isNotBlank(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    /**
     * 归一化用户输入文本。
     *
     * @param value 原始文本。
     * @return 去掉首尾空白的文本。
     */
    private String normalizeText(String value) {
        return StrUtil.nullToEmpty(value).trim();
    }

    /**
     * 判断 setup 字段是否是凭据类字段，便于批量写入前统一阻止展示态占位值。
     *
     * @param key 配置字段名。
     * @return 凭据类字段返回 true。
     */
    private boolean isSecretSetupKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("apikey")
                || normalized.contains("api_key")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("credential")
                || normalized.contains("authorization")
                || normalized.contains("privatekey")
                || normalized.contains("private_key");
    }

    /**
     * 生成用于终端或 TUI 展示的安全配置值。
     *
     * @param key 配置字段名。
     * @param value 原始值。
     * @return 敏感字段返回 ***，普通字段返回原值。
     */
    public static String safeConfigValue(String key, String value) {
        String normalized = StrUtil.nullToEmpty(key).toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("apikey")
                || normalized.contains("api_key")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("credential")
                || normalized.contains("authorization")
                || normalized.contains("privatekey")
                || normalized.contains("private_key")) {
            return "***";
        }
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 800);
    }

    /** 模型 provider 写入请求。 */
    public static class ModelSetupRequest {
        /** provider 键，用于 providers.<key> 配置段。 */
        private String providerKey;

        /** provider 展示名。 */
        private String providerName;

        /** 模型 API 基础地址。 */
        private String baseUrl;

        /** 模型 API Key。 */
        private String apiKey;

        /** 默认模型名称。 */
        private String model;

        /** 协议方言。 */
        private String dialect;

        public String getProviderKey() {
            return providerKey;
        }

        public void setProviderKey(String providerKey) {
            this.providerKey = providerKey;
        }

        public String getProviderName() {
            return providerName;
        }

        public void setProviderName(String providerName) {
            this.providerName = providerName;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getDialect() {
            return dialect;
        }

        public void setDialect(String dialect) {
            this.dialect = dialect;
        }
    }

    /** 运行时初始化配置写入结果。 */
    public static class SetupResult {
        /** 是否写入成功。 */
        private final boolean success;

        /** 写入分类，例如 model 或 gateway。 */
        private final String category;

        /** 失败原因或成功消息。 */
        private final String message;

        /** 已脱敏的关键结果值。 */
        private final Map<String, String> values;

        private SetupResult(
                boolean success, String category, String message, Map<String, String> values) {
            this.success = success;
            this.category = category;
            this.message = message;
            this.values = values == null ? Collections.<String, String>emptyMap() : values;
        }

        /** 创建成功结果。 */
        public static SetupResult ok(String category, Map<String, String> values) {
            return new SetupResult(true, category, "", values);
        }

        /** 创建失败结果。 */
        public static SetupResult error(String message) {
            return error(message, "");
        }

        /** 创建带细节的失败结果。 */
        public static SetupResult error(String message, String detail) {
            Map<String, String> values = new LinkedHashMap<String, String>();
            if (StrUtil.isNotBlank(detail)) {
                values.put("detail", detail);
            }
            return new SetupResult(false, "", message, values);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getCategory() {
            return category;
        }

        public String getMessage() {
            return message;
        }

        public Map<String, String> getValues() {
            return Collections.unmodifiableMap(values);
        }
    }
}
