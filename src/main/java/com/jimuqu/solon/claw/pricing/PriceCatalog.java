package com.jimuqu.solon.claw.pricing;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;

/** Model price catalog keyed by provider/model. */
public class PriceCatalog {
    private final Map<String, ModelPrice> prices;

    public PriceCatalog(Map<String, ModelPrice> prices) {
        this.prices =
                prices == null
                        ? Collections.<String, ModelPrice>emptyMap()
                        : new LinkedHashMap<String, ModelPrice>(prices);
    }

    public static PriceCatalog empty() {
        return new PriceCatalog(Collections.<String, ModelPrice>emptyMap());
    }

    public static PriceCatalog builtinDefaults() {
        Map<String, ModelPrice> defaults = new LinkedHashMap<String, ModelPrice>();
        add(defaults, price(LlmConstants.PROVIDER_OPENAI, "gpt-4o", "2.50", "10.00", "1.25", "0", "openai-pricing-2026-03-16", "https://openai.com/api/pricing/"));
        add(defaults, price(LlmConstants.PROVIDER_OPENAI, "gpt-4o-mini", "0.15", "0.60", "0.075", "0", "openai-pricing-2026-03-16", "https://openai.com/api/pricing/"));
        add(defaults, price(LlmConstants.PROVIDER_OPENAI, "gpt-4.1", "2.00", "8.00", "0.50", "0", "openai-pricing-2026-03-16", "https://openai.com/api/pricing/"));
        add(defaults, price(LlmConstants.PROVIDER_OPENAI, "gpt-4.1-mini", "0.40", "1.60", "0.10", "0", "openai-pricing-2026-03-16", "https://openai.com/api/pricing/"));
        add(defaults, price(LlmConstants.PROVIDER_OPENAI, "gpt-4.1-nano", "0.10", "0.40", "0.025", "0", "openai-pricing-2026-03-16", "https://openai.com/api/pricing/"));
        add(defaults, price(LlmConstants.PROVIDER_OPENAI, "o3", "10.00", "40.00", "2.50", "0", "openai-pricing-2026-03-16", "https://openai.com/api/pricing/"));
        add(defaults, price(LlmConstants.PROVIDER_OPENAI, "o3-mini", "1.10", "4.40", "0.55", "0", "openai-pricing-2026-03-16", "https://openai.com/api/pricing/"));
        add(defaults, price(LlmConstants.PROVIDER_ANTHROPIC, "claude-opus-4-8", "5.00", "25.00", "0.50", "6.25", "anthropic-pricing-2026-05", "https://platform.claude.com/docs/en/about-claude/pricing"));
        add(defaults, price(LlmConstants.PROVIDER_ANTHROPIC, "claude-opus-4-8-fast", "10.00", "50.00", "1.00", "12.50", "anthropic-pricing-2026-05", "https://openrouter.ai/anthropic/claude-opus-4.8-fast"));
        add(defaults, price(LlmConstants.PROVIDER_ANTHROPIC, "claude-opus-4-7", "5.00", "25.00", "0.50", "6.25", "anthropic-pricing-2026-05", "https://platform.claude.com/docs/en/about-claude/pricing"));
        add(defaults, price(LlmConstants.PROVIDER_ANTHROPIC, "claude-opus-4-6", "5.00", "25.00", "0.50", "6.25", "anthropic-pricing-2026-05", "https://platform.claude.com/docs/en/about-claude/pricing"));
        add(defaults, price(LlmConstants.PROVIDER_ANTHROPIC, "claude-sonnet-4-6", "3.00", "15.00", "0.30", "3.75", "anthropic-pricing-2026-05", "https://platform.claude.com/docs/en/about-claude/pricing"));
        add(defaults, price(LlmConstants.PROVIDER_ANTHROPIC, "claude-opus-4-5", "5.00", "25.00", "0.50", "6.25", "anthropic-pricing-2026-05", "https://platform.claude.com/docs/en/about-claude/pricing"));
        add(defaults, price(LlmConstants.PROVIDER_ANTHROPIC, "claude-sonnet-4-5", "3.00", "15.00", "0.30", "3.75", "anthropic-pricing-2026-05", "https://platform.claude.com/docs/en/about-claude/pricing"));
        add(defaults, price(LlmConstants.PROVIDER_ANTHROPIC, "claude-haiku-4-5", "1.00", "5.00", "0.10", "1.25", "anthropic-pricing-2026-05", "https://platform.claude.com/docs/en/about-claude/pricing"));
        add(defaults, price(LlmConstants.PROVIDER_ANTHROPIC, "claude-3-5-sonnet-20241022", "3.00", "15.00", "0.30", "3.75", "anthropic-pricing-2026-05", "https://platform.claude.com/docs/en/about-claude/pricing"));
        add(defaults, price(LlmConstants.PROVIDER_ANTHROPIC, "claude-3-5-haiku-20241022", "0.80", "4.00", "0.08", "1.00", "anthropic-pricing-2026-05", "https://platform.claude.com/docs/en/about-claude/pricing"));
        add(defaults, price(LlmConstants.PROVIDER_GEMINI, "gemini-2.5-pro", "1.25", "10.00", "0", "0", "google-pricing-2026-03-16", "https://ai.google.dev/pricing"));
        add(defaults, price(LlmConstants.PROVIDER_GEMINI, "gemini-2.5-flash", "0.15", "0.60", "0", "0", "google-pricing-2026-03-16", "https://ai.google.dev/pricing"));
        add(defaults, price(LlmConstants.PROVIDER_GEMINI, "gemini-2.0-flash", "0.10", "0.40", "0", "0", "google-pricing-2026-03-16", "https://ai.google.dev/pricing"));
        add(defaults, free(LlmConstants.PROVIDER_OLLAMA, "llama3"));
        add(defaults, free(LlmConstants.PROVIDER_OLLAMA, "llama3.1"));
        add(defaults, free(LlmConstants.PROVIDER_OLLAMA, "llama3.2"));
        add(defaults, free(LlmConstants.PROVIDER_OLLAMA, "qwen2.5"));
        add(defaults, free(LlmConstants.PROVIDER_OLLAMA, "qwen3"));
        return new PriceCatalog(defaults);
    }

    public static PriceCatalog configuredWithDefaults(List<ModelPrice> modelPrices) {
        return mergeWithConfigured(builtinDefaults().prices, modelPrices);
    }

    public static PriceCatalog forConfig(AppConfig appConfig) {
        Map<String, ModelPrice> defaults = new LinkedHashMap<String, ModelPrice>(builtinDefaults().prices);
        addProviderAliases(defaults, appConfig);
        List<ModelPrice> configured =
                appConfig == null || appConfig.getPricing() == null
                        ? null
                        : appConfig.getPricing().getPrices();
        return mergeWithConfigured(defaults, configured);
    }

    private static PriceCatalog mergeWithConfigured(
            Map<String, ModelPrice> defaultPrices, List<ModelPrice> modelPrices) {
        Map<String, ModelPrice> merged = new LinkedHashMap<String, ModelPrice>(defaultPrices);
        PriceCatalog configured = fromPrices(modelPrices);
        for (Map.Entry<String, ModelPrice> entry : configured.prices.entrySet()) {
            ModelPrice base = merged.get(entry.getKey());
            merged.put(entry.getKey(), base == null ? entry.getValue() : base.mergeOverride(entry.getValue()));
        }
        return new PriceCatalog(merged);
    }

    private static void addProviderAliases(Map<String, ModelPrice> target, AppConfig appConfig) {
        if (target == null || appConfig == null || appConfig.getProviders() == null) {
            return;
        }
        PriceCatalog defaults = builtinDefaults();
        for (Map.Entry<String, AppConfig.ProviderConfig> entry : appConfig.getProviders().entrySet()) {
            String providerKey = ModelPrice.normalize(entry.getKey());
            AppConfig.ProviderConfig provider = entry.getValue();
            String dialect = provider == null ? "" : LlmProviderSupport.normalizeDialect(provider.getDialect());
            if (StrUtil.isBlank(providerKey)
                    || StrUtil.isBlank(dialect)
                    || providerKey.equals(dialect)
                    || !LlmProviderSupport.isSupportedDialect(dialect)) {
                continue;
            }
            aliasProvider(target, defaults, providerKey, dialect);
            if (LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect)) {
                aliasProvider(target, defaults, providerKey, LlmConstants.PROVIDER_OPENAI);
            }
        }
    }

    private static void aliasProvider(
            Map<String, ModelPrice> target,
            PriceCatalog defaults,
            String providerKey,
            String sourceProvider) {
        for (Map.Entry<String, ModelPrice> entry : defaults.prices.entrySet()) {
            ModelPrice price = entry.getValue();
            if (!sourceProvider.equals(ModelPrice.normalize(price.getProvider()))) {
                continue;
            }
            ModelPrice alias = price.copy();
            alias.setProvider(providerKey);
            add(target, alias);
        }
    }

    public int size() {
        return prices.size();
    }

    public static PriceCatalog fromJson(String json) {
        if (StrUtil.isBlank(json)) {
            return empty();
        }
        ONode root = ONode.ofJson(json);
        ONode array = root.get("prices");
        Map<String, ModelPrice> parsed = new LinkedHashMap<String, ModelPrice>();
        if (array == null || !array.isArray()) {
            return empty();
        }
        for (ONode node : arrayNodes(array)) {
            ModelPrice price = new ModelPrice();
            price.setProvider(text(node, "provider"));
            price.setModel(text(node, "model"));
            price.setCurrency(StrUtil.blankToDefault(text(node, "currency"), "USD"));
            applyTokenPrice(
                    price,
                    "input",
                    firstText(node, "input_cost_per_million", "prompt_cost_per_million"),
                    firstPresentNumber(node, "input_micros_per_token", "prompt_micros_per_token"));
            applyTokenPrice(
                    price,
                    "output",
                    firstText(node, "output_cost_per_million", "completion_cost_per_million"),
                    firstPresentNumber(node, "output_micros_per_token", "completion_micros_per_token"));
            applyTokenPrice(
                    price,
                    "cache_read",
                    text(node, "cache_read_cost_per_million"),
                    presentNumber(node, "cache_read_micros_per_token"));
            applyTokenPrice(
                    price,
                    "cache_write",
                    text(node, "cache_write_cost_per_million"),
                    presentNumber(node, "cache_write_micros_per_token"));
            applyTokenPrice(
                    price,
                    "reasoning",
                    text(node, "reasoning_cost_per_million"),
                    presentNumber(node, "reasoning_micros_per_token"));
            Long requestMicros = presentNumber(node, "request_micros_per_request");
            if (requestMicros != null) {
                price.setRequestMicrosPerRequest(requestMicros.longValue());
            }
            price.setSource(text(node, "source"));
            price.setSourceUrl(firstText(node, "source_url", "sourceUrl"));
            price.setPricingVersion(firstText(node, "pricing_version", "pricingVersion", "version"));
            price.setFetchedAt(firstNumber(node, "fetched_at", "fetchedAt"));
            if (StrUtil.isNotBlank(price.getProvider()) && StrUtil.isNotBlank(price.getModel())) {
                parsed.put(price.key(), price);
            }
        }
        return new PriceCatalog(parsed);
    }

    private static List<ONode> arrayNodes(ONode array) {
        List<ONode> nodes = array.getArray();
        if (nodes == null) {
            return Collections.emptyList();
        }
        return nodes;
    }

    private static void add(Map<String, ModelPrice> target, ModelPrice price) {
        if (target != null
                && price != null
                && StrUtil.isNotBlank(price.getProvider())
                && StrUtil.isNotBlank(price.getModel())) {
            target.put(price.key(), price);
        }
    }

    private static ModelPrice price(
            String provider,
            String model,
            String inputCostPerMillion,
            String outputCostPerMillion,
            String cacheReadCostPerMillion,
            String cacheWriteCostPerMillion,
            String pricingVersion,
            String sourceUrl) {
        ModelPrice price = new ModelPrice();
        price.setProvider(provider);
        price.setModel(model);
        price.setCurrency("USD");
        price.setInputCostPerMillion(inputCostPerMillion);
        price.setOutputCostPerMillion(outputCostPerMillion);
        price.setCacheReadCostPerMillion(cacheReadCostPerMillion);
        price.setCacheWriteCostPerMillion(cacheWriteCostPerMillion);
        price.setSource("official_docs_snapshot");
        price.setSourceUrl(sourceUrl);
        price.setPricingVersion(pricingVersion);
        return price;
    }

    private static ModelPrice free(String provider, String model) {
        ModelPrice price = new ModelPrice();
        price.setProvider(provider);
        price.setModel(model);
        price.setCurrency("USD");
        price.setSource("local_runtime");
        price.setPricingVersion("ollama-local-free");
        return price;
    }

    public static PriceCatalog fromPrices(List<ModelPrice> modelPrices) {
        Map<String, ModelPrice> parsed = new LinkedHashMap<String, ModelPrice>();
        if (modelPrices != null) {
            for (ModelPrice price : modelPrices) {
                if (price != null
                        && StrUtil.isNotBlank(price.getProvider())
                        && StrUtil.isNotBlank(price.getModel())) {
                    parsed.put(price.key(), price);
                }
            }
        }
        return new PriceCatalog(parsed);
    }

    public ModelPrice find(String provider, String model) {
        String normalizedProvider = ModelPrice.normalize(provider);
        String normalizedModel = ModelPrice.normalize(model);
        for (String candidateProvider : providerCandidates(normalizedProvider)) {
            ModelPrice price = findByProvider(candidateProvider, normalizedProvider, normalizedModel);
            if (price != null) {
                return price;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return prices.isEmpty();
    }

    private static String text(ONode node, String key) {
        ONode value = node.get(key);
        String text = value == null ? null : value.getString();
        return StrUtil.isBlank(text) ? null : text;
    }

    private static String firstText(ONode node, String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            String value = text(node, key);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static long number(ONode node, String key) {
        Long value = presentNumber(node, key);
        return value == null ? 0L : value.longValue();
    }

    private static Long presentNumber(ONode node, String key) {
        ONode value = node.get(key);
        if (value == null || StrUtil.isBlank(value.getString())) {
            return null;
        }
        return Long.valueOf(Math.max(0L, value.getLong()));
    }

    private static long firstNumber(ONode node, String... keys) {
        Long value = firstPresentNumber(node, keys);
        return value == null ? 0L : value.longValue();
    }

    private static Long firstPresentNumber(ONode node, String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            Long value = presentNumber(node, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static void applyTokenPrice(
            ModelPrice price, String field, String costPerMillion, Long microsPerToken) {
        if (StrUtil.isNotBlank(costPerMillion)) {
            if ("input".equals(field)) {
                price.setInputCostPerMillion(costPerMillion);
            } else if ("output".equals(field)) {
                price.setOutputCostPerMillion(costPerMillion);
            } else if ("cache_read".equals(field)) {
                price.setCacheReadCostPerMillion(costPerMillion);
            } else if ("cache_write".equals(field)) {
                price.setCacheWriteCostPerMillion(costPerMillion);
            } else if ("reasoning".equals(field)) {
                price.setReasoningCostPerMillion(costPerMillion);
            }
            return;
        }
        if (microsPerToken == null) {
            return;
        }
        if ("input".equals(field)) {
            price.setInputMicrosPerToken(microsPerToken.longValue());
        } else if ("output".equals(field)) {
            price.setOutputMicrosPerToken(microsPerToken.longValue());
        } else if ("cache_read".equals(field)) {
            price.setCacheReadMicrosPerToken(microsPerToken.longValue());
        } else if ("cache_write".equals(field)) {
            price.setCacheWriteMicrosPerToken(microsPerToken.longValue());
        } else if ("reasoning".equals(field)) {
            price.setReasoningMicrosPerToken(microsPerToken.longValue());
        }
    }

    private static String stripMatchingProviderPrefix(String provider, String model) {
        if (StrUtil.isBlank(provider) || StrUtil.isBlank(model)) {
            return model;
        }
        String slashPrefix = provider + "/";
        if (model.startsWith(slashPrefix)) {
            return model.substring(slashPrefix.length());
        }
        String colonPrefix = provider + ":";
        if (model.startsWith(colonPrefix)) {
            return model.substring(colonPrefix.length());
        }
        return model;
    }

    private static String normalizeModelAlias(String provider, String model) {
        if (!"anthropic".equals(provider) || StrUtil.isBlank(model)) {
            return model;
        }
        StringBuilder normalized = new StringBuilder(model.length());
        for (int i = 0; i < model.length(); i++) {
            char ch = model.charAt(i);
            if (ch == '.'
                    && i > 0
                    && i + 1 < model.length()
                    && Character.isDigit(model.charAt(i - 1))
                    && Character.isDigit(model.charAt(i + 1))) {
                normalized.append('-');
            } else {
                normalized.append(Character.toLowerCase(ch));
            }
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }

    private ModelPrice findByProvider(
            String candidateProvider, String originalProvider, String normalizedModel) {
        ModelPrice exact = prices.get(candidateProvider + "/" + normalizedModel);
        if (exact != null) {
            return exact;
        }
        String routeModel = stripMatchingProviderPrefix(originalProvider, normalizedModel);
        routeModel = stripMatchingProviderPrefix(candidateProvider, routeModel);
        ModelPrice routed = prices.get(candidateProvider + "/" + routeModel);
        if (routed != null) {
            return routed;
        }
        String normalizedAlias = normalizeModelAlias(candidateProvider, routeModel);
        if (!StrUtil.equals(normalizedAlias, routeModel)) {
            return prices.get(candidateProvider + "/" + normalizedAlias);
        }
        return null;
    }

    private static List<String> providerCandidates(String provider) {
        if (StrUtil.isBlank(provider)) {
            return Collections.emptyList();
        }
        if ("default".equals(provider) || "openai-responses".equals(provider)) {
            return Arrays.asList(provider, "openai");
        }
        return Collections.singletonList(provider);
    }
}
