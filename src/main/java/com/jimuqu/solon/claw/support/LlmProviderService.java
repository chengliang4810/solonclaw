package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** LLM provider 解析服务。 */
public class LlmProviderService {
    /** 注入应用配置，用于大模型提供方。 */
    private final AppConfig appConfig;

    /** 模型元数据服务，用于为每个候选模型冻结独立的上下文窗口。 */
    private final ModelMetadataService modelMetadataService;

    /**
     * 创建大模型提供方服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public LlmProviderService(AppConfig appConfig) {
        this(appConfig, new ModelMetadataService(appConfig));
    }

    /**
     * 创建使用完整模型元数据解析链的提供方服务。
     *
     * @param appConfig 应用运行配置。
     * @param modelMetadataService 已注入在线目录与持久化缓存的模型元数据服务。
     */
    public LlmProviderService(AppConfig appConfig, ModelMetadataService modelMetadataService) {
        this.appConfig = appConfig;
        this.modelMetadataService = modelMetadataService;
    }

    /**
     * 解析生效提供方。
     *
     * @param session 会话参数。
     * @return 返回解析后的生效提供方。
     */
    public ResolvedProvider resolveEffectiveProvider(SessionRecord session) {
        return resolveEffectiveProvider(session, null);
    }

    /**
     * 解析生效提供方。
     *
     * @param session 会话参数。
     * @param agentDefaultModel Agent默认模型参数。
     * @return 返回解析后的生效提供方。
     */
    public ResolvedProvider resolveEffectiveProvider(
            SessionRecord session, String agentDefaultModel) {
        RuntimeConfigResolver resolver = configResolver();
        String providerKey =
                StrUtil.blankToDefault(
                        resolver.get("model.providerKey"),
                        StrUtil.nullToEmpty(appConfig.getModel().getProviderKey()).trim());
        String model = "";
        String transientProvider =
                session == null
                        ? ""
                        : StrUtil.nullToEmpty(session.getTransientProviderOverride()).trim();
        String transientModel =
                session == null
                        ? ""
                        : StrUtil.nullToEmpty(session.getTransientModelOverride()).trim();
        String transientBaseUrl =
                session == null
                        ? ""
                        : StrUtil.nullToEmpty(session.getTransientBaseUrlOverride()).trim();
        if (StrUtil.isNotBlank(transientProvider)) {
            providerKey = transientProvider;
        }
        if (StrUtil.isNotBlank(transientModel)) {
            model = transientModel;
        }
        String override =
                session == null ? "" : StrUtil.nullToEmpty(session.getModelOverride()).trim();
        if (StrUtil.isBlank(model) && StrUtil.isNotBlank(override)) {
            if (override.contains(":")) {
                String[] parts = override.split(":", 2);
                providerKey = StrUtil.nullToEmpty(parts[0]).trim();
                model = StrUtil.nullToEmpty(parts[1]).trim();
            } else {
                model = override;
            }
        } else if (StrUtil.isBlank(model) && StrUtil.isNotBlank(agentDefaultModel)) {
            model = agentDefaultModel.trim();
        } else if (StrUtil.isBlank(model)) {
            model = StrUtil.nullToEmpty(resolver.get("model.default")).trim();
        }
        ResolvedProvider resolved = resolveProvider(providerKey, model);
        if (StrUtil.isNotBlank(transientBaseUrl)) {
            resolved.setBaseUrl(transientBaseUrl);
            resolved.setApiUrl(
                    LlmProviderSupport.buildApiUrl(transientBaseUrl, resolved.getDialect()));
            resolveContextWindow(resolved);
        }
        return resolved;
    }

    /**
     * 解析提供方。
     *
     * @param providerKey 提供方键标识或键值。
     * @param explicitModel explicit模型参数。
     * @return 返回解析后的提供方。
     */
    public ResolvedProvider resolveProvider(String providerKey, String explicitModel) {
        return resolveProvider(providerKey, explicitModel, true);
    }

    /**
     * 解析兜底候选，跳过只属于主模型的显式上下文窗口覆盖。
     *
     * @param providerKey 提供方键标识。
     * @param explicitModel 兜底模型。
     * @return 返回解析后的兜底候选。
     */
    public ResolvedProvider resolveFallbackProvider(String providerKey, String explicitModel) {
        return resolveProvider(providerKey, explicitModel, false);
    }

    /** 按候选类型解析提供方，fallback 不继承只属于主模型的显式上下文窗口。 */
    private ResolvedProvider resolveProvider(
            String providerKey, String explicitModel, boolean allowConfiguredContextWindow) {
        String key = StrUtil.nullToEmpty(providerKey).trim();
        AppConfig.ProviderConfig provider = appConfig.getProviders().get(key);
        if (provider == null) {
            throw new IllegalStateException("未找到 provider：" + key);
        }
        RuntimeProvider runtimeProvider = runtimeProvider(key, provider);

        String model = StrUtil.nullToEmpty(explicitModel).trim();
        if (StrUtil.isBlank(model)) {
            model = StrUtil.nullToEmpty(runtimeProvider.defaultModel).trim();
        }
        if (StrUtil.isBlank(model)) {
            model = StrUtil.nullToEmpty(configResolver().get("model.default")).trim();
        }
        if (StrUtil.isBlank(model)) {
            model = StrUtil.nullToEmpty(appConfig.getModel().getDefault()).trim();
        }

        ResolvedProvider resolved = new ResolvedProvider();
        resolved.setProviderKey(key);
        resolved.setLabel(StrUtil.blankToDefault(runtimeProvider.name, key));
        resolved.setDialect(LlmProviderSupport.normalizeDialect(runtimeProvider.dialect));
        resolved.setBaseUrl(StrUtil.nullToEmpty(runtimeProvider.baseUrl).trim());
        resolved.setApiUrl(
                LlmProviderSupport.buildApiUrl(runtimeProvider.baseUrl, runtimeProvider.dialect));
        resolved.setApiKey(StrUtil.nullToEmpty(runtimeProvider.apiKey).trim());
        resolved.setModel(model);
        resolveContextWindow(resolved, allowConfiguredContextWindow);
        return resolved;
    }

    /** 按候选最终 provider、模型和地址刷新上下文窗口快照。 */
    private void resolveContextWindow(ResolvedProvider resolved) {
        resolveContextWindow(resolved, true);
    }

    /** 按候选类型刷新上下文窗口快照。 */
    private void resolveContextWindow(
            ResolvedProvider resolved, boolean allowConfiguredContextWindow) {
        resolved.setContextWindowTokens(
                modelMetadataService.resolveContextWindowForRuntime(
                        resolved.getProviderKey(),
                        resolved.getDialect(),
                        resolved.getBaseUrl(),
                        resolved.getModel(),
                        allowConfiguredContextWindow));
    }

    /**
     * 解析兜底Providers。
     *
     * @return 返回解析后的兜底Providers。
     */
    public List<ResolvedProvider> resolveFallbackProviders() {
        if (appConfig.getFallbackProviders() == null
                || appConfig.getFallbackProviders().isEmpty()) {
            return Collections.emptyList();
        }

        List<ResolvedProvider> result = new ArrayList<ResolvedProvider>();
        for (AppConfig.FallbackProviderConfig fallback : appConfig.getFallbackProviders()) {
            if (fallback == null || StrUtil.isBlank(fallback.getProvider())) {
                continue;
            }
            result.add(resolveFallbackProvider(fallback.getProvider().trim(), fallback.getModel()));
        }
        return result;
    }

    /**
     * 在发起远程请求前检查本地即可确定的 provider 配置错误。
     *
     * @param resolved 已解析的 provider 快照。
     * @return 空字符串表示可以尝试；否则返回不含凭据的跳过原因。
     */
    public String preflightFailure(ResolvedProvider resolved) {
        if (resolved == null) {
            return "provider 配置无法解析";
        }
        String dialect = LlmProviderSupport.normalizeDialect(resolved.getDialect());
        if (!LlmProviderSupport.isSupportedDialect(dialect)) {
            return "不支持的协议方言：" + StrUtil.blankToDefault(dialect, "未配置");
        }
        if (StrUtil.isBlank(resolved.getModel())) {
            return "未配置模型";
        }
        if (StrUtil.isBlank(resolved.getApiUrl())) {
            return "未配置可用的 API 地址";
        }
        String host = LlmProviderSupport.baseUrlHostname(resolved.getBaseUrl());
        if (!LlmConstants.PROVIDER_OLLAMA.equals(dialect)
                && !isLocalHost(host)
                && !SecretValueGuard.hasUsableSecret(resolved.getApiKey())) {
            return "缺少可用 API 凭据";
        }
        String path = StrUtil.nullToEmpty(resolved.getApiUrl()).toLowerCase();
        if ((path.endsWith("/messages") && !LlmConstants.PROVIDER_ANTHROPIC.equals(dialect))
                || (path.endsWith("/responses")
                        && !LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect))
                || (path.endsWith("/chat/completions")
                        && !LlmConstants.PROVIDER_OPENAI.equals(dialect))) {
            return "API 地址与协议方言不匹配";
        }
        String model = resolved.getModel().toLowerCase();
        if ((LlmProviderSupport.baseUrlHostMatches(resolved.getBaseUrl(), "api.anthropic.com")
                        && !model.startsWith("claude"))
                || (LlmProviderSupport.baseUrlHostMatches(
                                resolved.getBaseUrl(), "generativelanguage.googleapis.com")
                        && !model.startsWith("gemini"))
                || (LlmProviderSupport.baseUrlHostMatches(resolved.getBaseUrl(), "api.openai.com")
                        && (model.startsWith("claude") || model.startsWith("gemini")))) {
            return "模型名与 provider 路由明显不匹配";
        }
        return "";
    }

    /** 判断 provider 地址是否为无需云端 API 凭据的本机服务。 */
    private boolean isLocalHost(String host) {
        String value = StrUtil.nullToEmpty(host).trim().toLowerCase();
        return "localhost".equals(value)
                || "127.0.0.1".equals(value)
                || "::1".equals(value)
                || "0:0:0:0:0:0:0:1".equals(value);
    }

    /**
     * 判断是否存在提供方。
     *
     * @param providerKey 提供方键标识或键值。
     * @return 如果提供方满足条件则返回 true，否则返回 false。
     */
    public boolean hasProvider(String providerKey) {
        return appConfig.getProviders().containsKey(StrUtil.nullToEmpty(providerKey).trim());
    }

    /**
     * 执行providers相关逻辑。
     *
     * @return 返回providers结果。
     */
    public Map<String, AppConfig.ProviderConfig> providers() {
        return appConfig.getProviders();
    }

    /** 返回当前 workspace/config.yml 解析器，保证模型请求能读取 TUI 和配置命令的即时写入。 */
    private RuntimeConfigResolver configResolver() {
        return RuntimeConfigResolverSupport.fromAppConfig(appConfig);
    }

    /**
     * 合并启动配置与工作区配置中的 provider 字段。
     *
     * @param providerKey provider 键。
     * @param provider 启动时 provider 配置。
     * @return 返回模型请求最终使用的 provider 字段。
     */
    private RuntimeProvider runtimeProvider(String providerKey, AppConfig.ProviderConfig provider) {
        RuntimeConfigResolver resolver = configResolver();
        RuntimeProvider result = new RuntimeProvider();
        String prefix = "providers." + providerKey + ".";
        result.name = runtimeValue(resolver, prefix + "name", provider.getName());
        result.baseUrl = runtimeValue(resolver, prefix + "baseUrl", provider.getBaseUrl());
        result.apiKey = runtimeValue(resolver, prefix + "apiKey", provider.getApiKey());
        result.defaultModel =
                runtimeValue(resolver, prefix + "defaultModel", provider.getDefaultModel());
        result.dialect = runtimeValue(resolver, prefix + "dialect", provider.getDialect());
        return result;
    }

    /**
     * 读取 workspace/config.yml 覆盖值；空白值视为未覆盖，避免清空启动时的必填配置。
     *
     * @param resolver 工作区配置解析器。
     * @param key 配置键。
     * @param fallback 启动配置中的回退值。
     * @return 返回合并后的字符串值。
     */
    private String runtimeValue(RuntimeConfigResolver resolver, String key, String fallback) {
        String value = resolver.get(key);
        return StrUtil.isNotBlank(value) ? value.trim() : StrUtil.nullToEmpty(fallback).trim();
    }

    /** provider 字段运行时快照，避免把可变配置散落在请求链路中。 */
    private static class RuntimeProvider {
        /** provider 展示名称。 */
        private String name;

        /** provider 基础地址。 */
        private String baseUrl;

        /** provider API 密钥。 */
        private String apiKey;

        /** provider 默认模型。 */
        private String defaultModel;

        /** provider 协议方言。 */
        private String dialect;
    }

    /** 提供Resolved能力的扩展入口，屏蔽具体实现差异。 */
    public static class ResolvedProvider {
        /** 记录Resolved中的提供方键。 */
        private String providerKey;

        /** 记录Resolved中的label。 */
        private String label;

        /** 记录Resolved中的协议方言。 */
        private String dialect;

        /** 记录Resolved中的基础URL。 */
        private String baseUrl;

        /** 记录Resolved中的apiURL。 */
        private String apiUrl;

        /** 记录Resolved中的api键。 */
        private String apiKey;

        /** 记录Resolved中的模型。 */
        private String model;

        /** 当前候选模型解析后的上下文窗口，随候选快照传递且不修改共享配置。 */
        private int contextWindowTokens;

        /**
         * 读取提供方键。
         *
         * @return 返回读取到的提供方键。
         */
        public String getProviderKey() {
            return providerKey;
        }

        /**
         * 写入提供方键。
         *
         * @param providerKey 提供方键标识或键值。
         */
        public void setProviderKey(String providerKey) {
            this.providerKey = providerKey;
        }

        /**
         * 读取Label。
         *
         * @return 返回读取到的Label。
         */
        public String getLabel() {
            return label;
        }

        /**
         * 写入Label。
         *
         * @param label label 参数。
         */
        public void setLabel(String label) {
            this.label = label;
        }

        /**
         * 读取协议方言。
         *
         * @return 返回读取到的协议方言。
         */
        public String getDialect() {
            return dialect;
        }

        /**
         * 写入协议方言。
         *
         * @param dialect dialect 参数。
         */
        public void setDialect(String dialect) {
            this.dialect = dialect;
        }

        /**
         * 读取Base URL。
         *
         * @return 返回读取到的Base URL。
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * 写入Base URL。
         *
         * @param baseUrl 待校验或访问的地址参数。
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * 读取Api URL。
         *
         * @return 返回读取到的Api URL。
         */
        public String getApiUrl() {
            return apiUrl;
        }

        /**
         * 写入Api URL。
         *
         * @param apiUrl 待校验或访问的地址参数。
         */
        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        /**
         * 读取Api键。
         *
         * @return 返回读取到的Api键。
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * 写入Api键。
         *
         * @param apiKey api键标识或键值。
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * 读取模型。
         *
         * @return 返回读取到的模型。
         */
        public String getModel() {
            return model;
        }

        /**
         * 写入模型。
         *
         * @param model 模型名称。
         */
        public void setModel(String model) {
            this.model = model;
        }

        /**
         * 读取当前候选模型的上下文窗口。
         *
         * @return 返回上下文窗口 token 数。
         */
        public int getContextWindowTokens() {
            return contextWindowTokens;
        }

        /**
         * 写入当前候选模型的上下文窗口。
         *
         * @param contextWindowTokens 上下文窗口 token 数。
         */
        public void setContextWindowTokens(int contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
        }
    }
}
