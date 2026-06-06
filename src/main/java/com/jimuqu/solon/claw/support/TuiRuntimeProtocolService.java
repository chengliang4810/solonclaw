package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 为独立终端前端提供模型、配置与初始化状态的轻量协议服务。 */
public class TuiRuntimeProtocolService {
    /** 应用配置，用于读取当前模型 provider 和运行时目录。 */
    private final AppConfig appConfig;

    /** 共享初始化写入服务，确保 TUI 保存行为与 CLI/setup 命令一致。 */
    private final RuntimeSetupService setupService;

    /**
     * 创建 TUI 运行时协议服务。
     *
     * @param appConfig 当前应用配置。
     */
    public TuiRuntimeProtocolService(AppConfig appConfig) {
        this.appConfig = appConfig == null ? new AppConfig() : appConfig;
        this.setupService = new RuntimeSetupService(this.appConfig);
    }

    /**
     * 返回首次启动前端需要的 setup 状态。
     *
     * @return provider 是否配置完整、当前 provider 与模型等信息。
     */
    public Map<String, Object> setupStatus() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String providerKey = activeProviderKey();
        AppConfig.ProviderConfig provider = provider(providerKey);
        String model = activeModel(provider);
        boolean configured = providerConfigured(providerKey, provider);
        result.put("provider_configured", Boolean.valueOf(configured));
        result.put("provider", providerKey);
        result.put("model", model);
        result.put("api_key", configured ? "configured" : "missing");
        result.put("runtime_config", configResolver().configFile().getPath());
        return result;
    }

    /**
     * 返回模型选择器需要的 provider 和模型列表。
     *
     * @param sessionId 可选会话标识，当前实现不做 session 级差异化。
     * @return 模型选择器响应。
     */
    public Map<String, Object> modelOptions(String sessionId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("model", activeModelFromRuntime());
        result.put("provider", activeProviderKeyFromRuntime());
        List<Map<String, Object>> providers = new ArrayList<Map<String, Object>>();
        for (String key : providerKeys()) {
            providers.add(providerOption(key));
        }
        result.put("providers", providers);
        return result;
    }

    /**
     * 保存 provider API Key，并返回更新后的 provider 选择器条目。
     *
     * @param slug provider 标识。
     * @param apiKey 用户输入的 API Key。
     * @param sessionId 可选会话标识，当前保存到全局 runtime/config.yml。
     * @return 保存结果。
     */
    public Map<String, Object> modelSaveKey(String slug, String apiKey, String sessionId) {
        String providerKey = StrUtil.blankToDefault(StrUtil.nullToEmpty(slug).trim(), activeProviderKey());
        AppConfig.ProviderConfig provider = provider(providerKey);
        RuntimeProviderSetupSpec.ProviderTemplate template =
                RuntimeProviderSetupSpec.provider(providerKey);
        RuntimeSetupService.ModelSetupRequest request =
                new RuntimeSetupService.ModelSetupRequest();
        request.setProviderKey(providerKey);
        request.setProviderName(providerName(providerKey, provider, template));
        request.setBaseUrl(providerBaseUrl(providerKey, provider, template));
        request.setApiKey(StrUtil.nullToEmpty(apiKey).trim());
        request.setModel(providerModel(providerKey, provider, template));
        request.setDialect(providerDialect(provider, template));
        RuntimeSetupService.SetupResult saved = setupService.configureModel(request);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", Boolean.valueOf(saved.isSuccess()));
        if (!saved.isSuccess()) {
            result.put("error", saved.getMessage());
            result.put("detail", saved.getValues().get("detail"));
            return result;
        }
        result.put("provider", providerOption(providerKey));
        return result;
    }

    /**
     * 返回国内消息渠道设置页需要的渠道列表。
     *
     * @return 渠道标识、状态、必填字段和允许字段。
     */
    public Map<String, Object> channelOptions() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> channels = new ArrayList<Map<String, Object>>();
        for (String channel : RuntimeSetupSpec.domesticChannels()) {
            channels.add(channelOption(channel));
        }
        result.put("channels", channels);
        result.put("mtime", Long.valueOf(configMtime()));
        return result;
    }

    /**
     * 返回单个国内消息渠道的当前配置状态。
     *
     * @param channel 渠道标识。
     * @return 渠道状态；未知渠道返回错误对象。
     */
    public Map<String, Object> channelStatus(String channel) {
        String normalized = normalizeChannel(channel);
        if (!RuntimeSetupSpec.domesticChannels().contains(normalized)) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("ok", Boolean.FALSE);
            result.put("error", "unsupported_channel");
            result.put("channel", normalized);
            return result;
        }
        return channelOption(normalized);
    }

    /**
     * 写入国内消息渠道配置，并返回写入后的状态。
     *
     * @param channel 渠道标识。
     * @param values 要写入 runtime/config.yml 的字段。
     * @param sessionId 可选会话标识；当前仅回传给 TUI 便于前端关联请求。
     * @return 已脱敏的保存结果。
     */
    public Map<String, Object> channelSave(
            String channel, Map<String, String> values, String sessionId) {
        String normalized = normalizeChannel(channel);
        RuntimeSetupService.SetupResult saved =
                setupService.configureGatewayChannel(normalized, values);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", Boolean.valueOf(saved.isSuccess()));
        result.put("saved", Boolean.valueOf(saved.isSuccess()));
        result.put("channel", normalized);
        if (!saved.isSuccess()) {
            result.put("error", saved.getMessage());
            result.put("detail", saved.getValues().get("detail"));
            return result;
        }
        Map<String, Object> status = channelOption(normalized);
        result.put("status", status.get("status"));
        result.put("enabled", status.get("enabled"));
        result.put("values", saved.getValues());
        result.put("mtime", Long.valueOf(configMtime()));
        if (StrUtil.isNotBlank(sessionId)) {
            result.put("session_id", sessionId);
        }
        return result;
    }

    /**
     * 读取 TUI 前端常用配置。
     *
     * @param key 配置键；支持 full、model、mtime 和 runtime/config.yml 键。
     * @return 配置读取结果。
     */
    public Map<String, Object> configGet(String key) {
        String normalized = StrUtil.blankToDefault(StrUtil.nullToEmpty(key).trim(), "full");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("key", normalized);
        if ("full".equals(normalized)) {
            result.put("config", fullConfig());
            result.put("mtime", Long.valueOf(configMtime()));
            return result;
        }
        if ("mtime".equals(normalized)) {
            result.put("mtime", Long.valueOf(configMtime()));
            return result;
        }
        if ("model".equals(normalized)) {
            result.put("value", activeModelFromRuntime());
            result.put("provider", activeProviderKeyFromRuntime());
            return result;
        }
        result.put("value", configResolver().get(normalized));
        return result;
    }

    /**
     * 写入 TUI 前端常用配置。
     *
     * @param key 配置键；model 会解析 provider，其他键写入 runtime/config.yml。
     * @param value 配置值。
     * @param sessionId 可选会话标识；当前仅保留在返回结果中，便于后续扩展 session scope。
     * @return 配置写入结果。
     */
    public Map<String, Object> configSet(String key, String value, String sessionId) {
        String normalized = StrUtil.nullToEmpty(key).trim();
        String rawValue = StrUtil.nullToEmpty(value).trim();
        if ("model".equals(normalized)) {
            return setModelValue(rawValue, sessionId);
        }
        RuntimeConfigResolver resolver = configResolver();
        resolver.setFileValue(normalized, rawValue);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", Boolean.TRUE);
        result.put("key", normalized);
        result.put("value", RuntimeSetupService.safeConfigValue(normalized, rawValue));
        result.put("mtime", Long.valueOf(configMtime()));
        if (StrUtil.isNotBlank(sessionId)) {
            result.put("session_id", sessionId);
        }
        return result;
    }

    /**
     * 保存模型选择器传回的模型值。
     *
     * @param value 模型文本，允许附带 --provider。
     * @param sessionId 可选会话标识。
     * @return 写入结果。
     */
    private Map<String, Object> setModelValue(String value, String sessionId) {
        ModelSelection selection = parseModelSelection(value);
        RuntimeConfigResolver resolver = configResolver();
        resolver.setFileValue("model.providerKey", selection.providerKey);
        resolver.setFileValue("model.default", selection.model);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", Boolean.TRUE);
        result.put("key", "model");
        result.put("value", selection.model);
        result.put("provider", selection.providerKey);
        result.put("global", Boolean.valueOf(selection.global));
        result.put("mtime", Long.valueOf(configMtime()));
        if (StrUtil.isNotBlank(sessionId)) {
            result.put("session_id", sessionId);
        }
        return result;
    }

    /**
     * 构建完整配置快照，字段名保持终端前端易消费的扁平形态。
     *
     * @return 已脱敏配置快照。
     */
    private Map<String, Object> fullConfig() {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("model", activeModelFromRuntime());
        config.put("provider", activeProviderKeyFromRuntime());
        config.put("runtime_config", configResolver().configFile().getPath());
        Map<String, Object> display = new LinkedHashMap<String, Object>();
        display.put("show_reasoning", readBoolean("display.showReasoning", true));
        display.put("show_cost", readBoolean("display.showCost", false));
        display.put("streaming", readBoolean("display.streaming", true));
        display.put("tui_compact", readBoolean("display.tuiCompact", false));
        display.put("bell_on_complete", readBoolean("display.bellOnComplete", false));
        config.put("display", display);
        config.put("providers", providerOptionMap());
        return config;
    }

    /** 返回 provider 选择器条目映射。 */
    private Map<String, Object> providerOptionMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (String key : providerKeys()) {
            result.put(key, providerOption(key));
        }
        return result;
    }

    /**
     * 构建单个 provider 选择器条目。
     *
     * @param providerKey provider 标识。
     * @return 前端模型选择器条目。
     */
    private Map<String, Object> providerOption(String providerKey) {
        AppConfig.ProviderConfig provider = provider(providerKey);
        RuntimeProviderSetupSpec.ProviderTemplate template =
                RuntimeProviderSetupSpec.provider(providerKey);
        String model = providerModel(providerKey, provider, template);
        List<String> models = new ArrayList<String>();
        if (providerKey.equals(activeProviderKeyFromRuntime())
                && StrUtil.isNotBlank(activeModelFromRuntime())) {
            models.add(activeModelFromRuntime());
        }
        if (StrUtil.isNotBlank(model)) {
            if (!models.contains(model)) {
                models.add(model);
            }
        }
        String configuredDefaultModel = configuredProviderModel(provider);
        if (StrUtil.isNotBlank(configuredDefaultModel) && !models.contains(configuredDefaultModel)) {
            models.add(configuredDefaultModel);
        }
        if (template != null) {
            for (String candidate : template.getModels()) {
                if (StrUtil.isNotBlank(candidate) && !models.contains(candidate)) {
                    models.add(candidate);
                }
            }
        }
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("slug", providerKey);
        item.put("name", providerName(providerKey, provider, template));
        item.put("auth_type", "api_key");
        item.put("key_env", providerKeyEnv(providerKey, template));
        item.put("authenticated", Boolean.valueOf(providerConfigured(providerKey, provider)));
        item.put("is_current", Boolean.valueOf(providerKey.equals(activeProviderKeyFromRuntime())));
        item.put("models", models);
        item.put("total_models", Integer.valueOf(models.size()));
        item.put("default_model", model);
        item.put("dialect", providerDialect(provider, template));
        item.put("base_url", SecretRedactor.maskUrl(providerBaseUrl(providerKey, provider, template)));
        if (!providerConfigured(providerKey, provider)) {
            item.put("warning", "paste " + providerKeyEnv(providerKey, template) + " to activate");
        }
        return item;
    }

    /**
     * 构建单个国内消息渠道的 TUI 状态条目。
     *
     * @param channel 渠道标识。
     * @return 已脱敏的渠道状态。
     */
    private Map<String, Object> channelOption(String channel) {
        AppConfig.ChannelConfig config = channelConfig(channel);
        List<String> requiredKeys = RuntimeSetupSpec.requiredChannelKeys(channel);
        String status = channelStatusValue(channel, config, requiredKeys);
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("key", channel);
        item.put("label", channelLabel(channel));
        item.put("channel", channel);
        item.put("enabled", Boolean.valueOf(channelEnabled(channel, config)));
        item.put("configured", Boolean.valueOf("configured".equals(status)));
        item.put("status", status);
        item.put("fields", channelFields(channel, requiredKeys));
        item.put("required_keys", requiredKeys);
        item.put("allowed_keys", RuntimeSetupSpec.allowedChannelKeys(channel));
        Map<String, Object> required = new LinkedHashMap<String, Object>();
        for (String key : requiredKeys) {
            required.put(key, Boolean.valueOf(StrUtil.isNotBlank(channelFieldValue(channel, config, key))));
        }
        item.put("required_configured", required);
        return item;
    }

    /**
     * 返回国内渠道在终端 UI 中展示的名称。
     *
     * @param channel 渠道标识。
     * @return 用户可读名称。
     */
    private String channelLabel(String channel) {
        if ("feishu".equals(channel)) {
            return "Feishu";
        }
        if ("dingtalk".equals(channel)) {
            return "DingTalk";
        }
        if ("wecom".equals(channel)) {
            return "WeCom";
        }
        if ("weixin".equals(channel)) {
            return "Weixin";
        }
        if ("qqbot".equals(channel)) {
            return "QQBot";
        }
        if ("yuanbao".equals(channel)) {
            return "Yuanbao";
        }
        return channel;
    }

    /**
     * 构建终端 UI 渠道配置表单字段。
     *
     * @param channel 渠道标识。
     * @param requiredKeys 必填字段列表。
     * @return 前端可直接渲染的字段描述。
     */
    private List<Map<String, Object>> channelFields(String channel, List<String> requiredKeys) {
        List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
        for (String key : requiredKeys) {
            Map<String, Object> field = new LinkedHashMap<String, Object>();
            field.put("key", key);
            field.put("label", channelFieldLabel(key));
            field.put("description", channelFieldDescription(channel, key));
            field.put("required", Boolean.TRUE);
            field.put("secret", Boolean.valueOf(isSecretChannelField(key)));
            fields.add(field);
        }
        return fields;
    }

    /**
     * 返回渠道字段的简短标签。
     *
     * @param key 配置字段名。
     * @return 表单标签。
     */
    private String channelFieldLabel(String key) {
        if ("appId".equals(key)) {
            return "App ID";
        }
        if ("appSecret".equals(key)) {
            return "App Secret";
        }
        if ("clientId".equals(key)) {
            return "Client ID";
        }
        if ("clientSecret".equals(key)) {
            return "Client Secret";
        }
        if ("robotCode".equals(key)) {
            return "Robot Code";
        }
        if ("botId".equals(key)) {
            return "Bot ID";
        }
        if ("secret".equals(key)) {
            return "Secret";
        }
        if ("token".equals(key)) {
            return "Token";
        }
        if ("accountId".equals(key)) {
            return "Account ID";
        }
        return key;
    }

    /**
     * 返回渠道字段输入提示。
     *
     * @param channel 渠道标识。
     * @param key 配置字段名。
     * @return 简短说明。
     */
    private String channelFieldDescription(String channel, String key) {
        if ("enabled".equals(key)) {
            return "true 表示启用该渠道。";
        }
        return "Saved to runtime/config.yml under solonclaw.channels." + channel + "." + key + ".";
    }

    /**
     * 判断渠道字段是否应在 TUI 中掩码显示。
     *
     * @param key 配置字段名。
     * @return 敏感字段返回 true。
     */
    private boolean isSecretChannelField(String key) {
        String normalized = StrUtil.nullToEmpty(key).toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("password")
                || normalized.contains("credential")
                || normalized.contains("privatekey")
                || normalized.contains("private_key");
    }

    /**
     * 计算渠道状态，runtime/config.yml 覆盖值优先于启动时配置。
     *
     * @param channel 渠道标识。
     * @param config 启动时渠道配置。
     * @param requiredKeys 必填字段列表。
     * @return configured / disabled / missing_config。
     */
    private String channelStatusValue(
            String channel, AppConfig.ChannelConfig config, List<String> requiredKeys) {
        if (!channelEnabled(channel, config)) {
            return "disabled";
        }
        for (String key : requiredKeys) {
            if (StrUtil.isBlank(channelFieldValue(channel, config, key))) {
                return "missing_config";
            }
        }
        return "configured";
    }

    /** 判断渠道是否启用，优先读取 runtime/config.yml 覆盖值。 */
    private boolean channelEnabled(String channel, AppConfig.ChannelConfig config) {
        String fileValue = configResolver().get("solonclaw.channels." + channel + ".enabled");
        if (StrUtil.isNotBlank(fileValue)) {
            return "true".equalsIgnoreCase(fileValue)
                    || "1".equals(fileValue)
                    || "yes".equalsIgnoreCase(fileValue);
        }
        return config != null && config.isEnabled();
    }

    /** 读取渠道字段，优先读取 runtime/config.yml 覆盖值。 */
    private String channelFieldValue(String channel, AppConfig.ChannelConfig config, String key) {
        String fileValue = configResolver().get("solonclaw.channels." + channel + "." + key);
        if (StrUtil.isNotBlank(fileValue)) {
            return fileValue;
        }
        if (config == null) {
            return "";
        }
        if ("appId".equals(key)) {
            return config.getAppId();
        }
        if ("appSecret".equals(key)) {
            return config.getAppSecret();
        }
        if ("clientId".equals(key)) {
            return config.getClientId();
        }
        if ("clientSecret".equals(key)) {
            return config.getClientSecret();
        }
        if ("botId".equals(key)) {
            return config.getBotId();
        }
        if ("secret".equals(key)) {
            return config.getSecret();
        }
        if ("token".equals(key)) {
            return config.getToken();
        }
        if ("accountId".equals(key)) {
            return config.getAccountId();
        }
        if ("robotCode".equals(key)) {
            return config.getRobotCode();
        }
        return "";
    }

    /** 读取指定国内渠道的启动时配置。 */
    private AppConfig.ChannelConfig channelConfig(String channel) {
        if (appConfig.getChannels() == null) {
            return null;
        }
        if ("feishu".equals(channel)) {
            return appConfig.getChannels().getFeishu();
        }
        if ("dingtalk".equals(channel)) {
            return appConfig.getChannels().getDingtalk();
        }
        if ("wecom".equals(channel)) {
            return appConfig.getChannels().getWecom();
        }
        if ("weixin".equals(channel)) {
            return appConfig.getChannels().getWeixin();
        }
        if ("qqbot".equals(channel)) {
            return appConfig.getChannels().getQqbot();
        }
        if ("yuanbao".equals(channel)) {
            return appConfig.getChannels().getYuanbao();
        }
        return null;
    }

    /** 归一化渠道标识。 */
    private String normalizeChannel(String channel) {
        return StrUtil.nullToEmpty(channel).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 返回 provider key 列表，包含启动配置与 runtime/config.yml 动态 provider。 */
    private List<String> providerKeys() {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<String>();
        if (appConfig.getProviders() != null) {
            keys.addAll(appConfig.getProviders().keySet());
        }
        for (String key : configResolver().fileValues().keySet()) {
            if (key.startsWith("providers.")) {
                String remaining = key.substring("providers.".length());
                int dot = remaining.indexOf('.');
                if (dot > 0) {
                    keys.add(remaining.substring(0, dot));
                }
            }
        }
        for (RuntimeProviderSetupSpec.ProviderTemplate template : RuntimeProviderSetupSpec.providers()) {
            keys.add(template.getSlug());
        }
        if (keys.isEmpty()) {
            keys.add("default");
        }
        return new ArrayList<String>(keys);
    }

    /** 解析 TUI 传回的模型选择文本。 */
    private ModelSelection parseModelSelection(String value) {
        List<String> tokens = shellTokens(value);
        ModelSelection selection = new ModelSelection();
        selection.providerKey = activeProviderKeyFromRuntime();
        if (!tokens.isEmpty()) {
            selection.model = tokens.get(0);
        }
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String next = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
            if ("--provider".equals(token) && StrUtil.isNotBlank(next)) {
                selection.providerKey = next;
                i++;
            } else if ("--global".equals(token)) {
                selection.global = true;
            }
        }
        selection.providerKey = StrUtil.blankToDefault(selection.providerKey, activeProviderKey());
        selection.model =
                StrUtil.blankToDefault(
                        selection.model,
                        providerModel(
                                selection.providerKey,
                                provider(selection.providerKey),
                                RuntimeProviderSetupSpec.provider(selection.providerKey)));
        return selection;
    }

    /** 按常见 shell 规则切分模型选择参数。 */
    private List<String> shellTokens(String input) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < StrUtil.nullToEmpty(input).length(); i++) {
            char ch = input.charAt(i);
            if (ch == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (Character.isWhitespace(ch) && !singleQuoted && !doubleQuoted) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** 读取运行时配置布尔值。 */
    private boolean readBoolean(String key, boolean defaultValue) {
        String value = configResolver().get(key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    /** 返回配置文件修改时间。 */
    private long configMtime() {
        File file = configResolver().configFile();
        return file.exists() ? file.lastModified() : 0L;
    }

    /** 读取当前运行时配置解析器。 */
    private RuntimeConfigResolver configResolver() {
        String home = appConfig.getRuntime() == null ? "" : appConfig.getRuntime().getHome();
        return RuntimeConfigResolver.initialize(home);
    }

    /** 返回运行时覆盖后的当前 provider key。 */
    private String activeProviderKeyFromRuntime() {
        String value = configResolver().get("model.providerKey");
        return StrUtil.blankToDefault(value, activeProviderKey());
    }

    /** 返回运行时覆盖后的当前模型。 */
    private String activeModelFromRuntime() {
        String value = configResolver().get("model.default");
        return StrUtil.blankToDefault(value, activeModel(activeProvider()));
    }

    /** 返回当前主 provider key。 */
    private String activeProviderKey() {
        String providerKey = appConfig.getModel() == null ? "" : appConfig.getModel().getProviderKey();
        if (StrUtil.isBlank(providerKey) && appConfig.getLlm() != null) {
            providerKey = appConfig.getLlm().getProvider();
        }
        return StrUtil.blankToDefault(providerKey, "default").trim();
    }

    /** 返回当前主 provider 配置。 */
    private AppConfig.ProviderConfig activeProvider() {
        return provider(activeProviderKey());
    }

    /** 返回 provider 配置。 */
    private AppConfig.ProviderConfig provider(String providerKey) {
        return appConfig.getProviders() == null ? null : appConfig.getProviders().get(providerKey);
    }

    /** 返回 provider 展示名。 */
    private String providerName(
            String providerKey,
            AppConfig.ProviderConfig provider,
            RuntimeProviderSetupSpec.ProviderTemplate template) {
        String fileValue = configResolver().get("providers." + providerKey + ".name");
        if (StrUtil.isNotBlank(fileValue)) {
            return fileValue;
        }
        if (provider != null) {
            return StrUtil.blankToDefault(provider.getName(), providerKey);
        }
        return template == null ? providerKey : StrUtil.blankToDefault(template.getName(), providerKey);
    }

    /** 返回 provider baseUrl。 */
    private String providerBaseUrl(
            String providerKey,
            AppConfig.ProviderConfig provider,
            RuntimeProviderSetupSpec.ProviderTemplate template) {
        String fileValue = configResolver().get("providers." + providerKey + ".baseUrl");
        if (StrUtil.isNotBlank(fileValue)) {
            return fileValue;
        }
        if (provider != null && StrUtil.isNotBlank(provider.getBaseUrl())) {
            return StrUtil.nullToEmpty(provider.getBaseUrl()).trim();
        }
        return template == null ? "" : template.getBaseUrl();
    }

    /** 返回 provider 默认模型。 */
    private String providerModel(
            String providerKey,
            AppConfig.ProviderConfig provider,
            RuntimeProviderSetupSpec.ProviderTemplate template) {
        String fileValue = configResolver().get("providers." + providerKey + ".defaultModel");
        if (StrUtil.isNotBlank(fileValue)) {
            return fileValue;
        }
        if (providerKey.equals(activeProviderKeyFromRuntime())
                && StrUtil.isNotBlank(activeModelFromRuntime())) {
            return activeModelFromRuntime();
        }
        if (provider != null && StrUtil.isNotBlank(provider.getDefaultModel())) {
            return StrUtil.nullToEmpty(provider.getDefaultModel()).trim();
        }
        return template == null ? "" : template.getDefaultModel();
    }

    /** 返回 provider 自身声明的默认模型，不受 runtime 当前选择影响。 */
    private String configuredProviderModel(AppConfig.ProviderConfig provider) {
        return provider == null ? "" : StrUtil.nullToEmpty(provider.getDefaultModel()).trim();
    }

    /** 返回当前模型名。 */
    private String activeModel(AppConfig.ProviderConfig provider) {
        String model = appConfig.getModel() == null ? "" : appConfig.getModel().getDefault();
        if (StrUtil.isBlank(model) && appConfig.getLlm() != null) {
            model = appConfig.getLlm().getModel();
        }
        if (StrUtil.isBlank(model) && provider != null) {
            model = provider.getDefaultModel();
        }
        return StrUtil.nullToEmpty(model).trim();
    }

    /** 返回 provider 协议方言。 */
    private String providerDialect(
            AppConfig.ProviderConfig provider, RuntimeProviderSetupSpec.ProviderTemplate template) {
        if (provider != null && StrUtil.isNotBlank(provider.getDialect())) {
            return provider.getDialect().trim();
        }
        return template == null ? "openai" : StrUtil.blankToDefault(template.getDialect(), "openai");
    }

    /** 返回 provider API Key 提示环境变量名。 */
    private String providerKeyEnv(
            String providerKey, RuntimeProviderSetupSpec.ProviderTemplate template) {
        if (template != null && StrUtil.isNotBlank(template.getKeyEnv())) {
            return template.getKeyEnv();
        }
        return providerKey.toUpperCase(java.util.Locale.ROOT).replace('-', '_') + "_API_KEY";
    }

    /** 判断 provider 是否已有可用凭据。 */
    private boolean providerConfigured(String providerKey, AppConfig.ProviderConfig provider) {
        String fileValue = configResolver().get("providers." + providerKey + ".apiKey");
        if (SecretValueGuard.hasUsableSecret(fileValue)) {
            return true;
        }
        return provider != null && SecretValueGuard.hasUsableSecret(provider.getApiKey());
    }

    /** 模型选择请求。 */
    private static class ModelSelection {
        /** provider 标识。 */
        private String providerKey;

        /** 模型名称。 */
        private String model;

        /** 是否写入全局配置。 */
        private boolean global;
    }
}
