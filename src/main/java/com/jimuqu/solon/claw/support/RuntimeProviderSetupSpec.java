package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 定义本地模型 provider 初始化模板，供 CLI/TUI/slash 共享同一套展示与写入规则。 */
public final class RuntimeProviderSetupSpec {
    /** 按用户配置时最常见的顺序展示受支持 provider 模板。 */
    private static final List<ProviderTemplate> PROVIDERS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            new ProviderTemplate(
                                    "openai",
                                    "OpenAI Compatible",
                                    LlmConstants.PROVIDER_OPENAI,
                                    "https://api.openai.com/v1",
                                    "gpt-4.1",
                                    "OPENAI_API_KEY",
                                    Arrays.asList("gpt-4.1", "gpt-4o", "gpt-4o-mini")),
                            new ProviderTemplate(
                                    "openai-responses",
                                    "OpenAI Responses",
                                    LlmConstants.PROVIDER_OPENAI_RESPONSES,
                                    "https://api.openai.com/v1",
                                    "gpt-5",
                                    "OPENAI_API_KEY",
                                    Arrays.asList("gpt-5", "gpt-5-mini", "gpt-5.3-codex")),
                            new ProviderTemplate(
                                    "ollama",
                                    "Ollama",
                                    LlmConstants.PROVIDER_OLLAMA,
                                    "http://127.0.0.1:11434",
                                    "qwen3:8b",
                                    "OLLAMA_API_KEY",
                                    Arrays.asList("qwen3:8b", "llama3.1:8b", "mistral:7b")),
                            new ProviderTemplate(
                                    "gemini",
                                    "Gemini",
                                    LlmConstants.PROVIDER_GEMINI,
                                    "https://generativelanguage.googleapis.com",
                                    "gemini-2.5-pro",
                                    "GEMINI_API_KEY",
                                    Arrays.asList(
                                            "gemini-2.5-pro",
                                            "gemini-2.5-flash",
                                            "gemini-1.5-pro")),
                            new ProviderTemplate(
                                    "anthropic",
                                    "Anthropic",
                                    LlmConstants.PROVIDER_ANTHROPIC,
                                    "https://api.anthropic.com",
                                    "claude-sonnet-4-5",
                                    "ANTHROPIC_API_KEY",
                                    Arrays.asList(
                                            "claude-sonnet-4-5",
                                            "claude-opus-4-1",
                                            "claude-3-5-haiku-latest"))));

    /** provider slug 到模板的快速索引。 */
    private static final Map<String, ProviderTemplate> PROVIDER_INDEX = providerIndex();

    /** 禁止实例化工具类。 */
    private RuntimeProviderSetupSpec() {}

    /** 返回支持的 provider 初始化模板。 */
    public static List<ProviderTemplate> providers() {
        return PROVIDERS;
    }

    /**
     * 按 slug 查询 provider 初始化模板。
     *
     * @param slug provider 标识。
     * @return 命中的模板；未知 provider 返回 null。
     */
    public static ProviderTemplate provider(String slug) {
        return PROVIDER_INDEX.get(normalizeSlug(slug));
    }

    /**
     * 判断 provider 是否属于本项目确认支持的模板范围。
     *
     * @param slug provider 标识。
     * @return 已内置模板返回 true。
     */
    public static boolean isSupportedTemplate(String slug) {
        return provider(slug) != null;
    }

    /** 构建 provider slug 到模板的索引。 */
    private static Map<String, ProviderTemplate> providerIndex() {
        Map<String, ProviderTemplate> result = new LinkedHashMap<String, ProviderTemplate>();
        for (ProviderTemplate provider : PROVIDERS) {
            result.put(provider.getSlug(), provider);
        }
        return Collections.unmodifiableMap(result);
    }

    /** 归一化 provider slug。 */
    private static String normalizeSlug(String value) {
        return StrUtil.nullToEmpty(value).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** provider 初始化模板，描述首次配置时需要的最小字段。 */
    public static final class ProviderTemplate {
        /** provider 配置键。 */
        private final String slug;

        /** provider 展示名。 */
        private final String name;

        /** 协议方言。 */
        private final String dialect;

        /** 默认 Base URL。 */
        private final String baseUrl;

        /** 默认模型。 */
        private final String defaultModel;

        /** 推荐 API Key 环境变量名，仅用于提示。 */
        private final String keyEnv;

        /** 首次选择器展示的模型候选。 */
        private final List<String> models;

        private ProviderTemplate(
                String slug,
                String name,
                String dialect,
                String baseUrl,
                String defaultModel,
                String keyEnv,
                List<String> models) {
            this.slug = normalizeSlug(slug);
            this.name = StrUtil.nullToEmpty(name).trim();
            this.dialect = StrUtil.nullToEmpty(dialect).trim();
            this.baseUrl = StrUtil.nullToEmpty(baseUrl).trim();
            this.defaultModel = StrUtil.nullToEmpty(defaultModel).trim();
            this.keyEnv = StrUtil.nullToEmpty(keyEnv).trim();
            this.models =
                    Collections.unmodifiableList(
                            models == null
                                    ? Collections.<String>emptyList()
                                    : Arrays.asList(models.toArray(new String[0])));
        }

        public String getSlug() {
            return slug;
        }

        public String getName() {
            return name;
        }

        public String getDialect() {
            return dialect;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public String getKeyEnv() {
            return keyEnv;
        }

        public List<String> getModels() {
            return models;
        }
    }
}
