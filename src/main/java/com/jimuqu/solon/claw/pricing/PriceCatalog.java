package com.jimuqu.solon.claw.pricing;

import cn.hutool.core.util.StrUtil;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
            price.setInputMicrosPerToken(number(node, "input_micros_per_token"));
            price.setOutputMicrosPerToken(number(node, "output_micros_per_token"));
            price.setCacheReadMicrosPerToken(number(node, "cache_read_micros_per_token"));
            price.setCacheWriteMicrosPerToken(number(node, "cache_write_micros_per_token"));
            price.setReasoningMicrosPerToken(number(node, "reasoning_micros_per_token"));
            price.setSource(text(node, "source"));
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
        return prices.get(ModelPrice.normalize(provider) + "/" + ModelPrice.normalize(model));
    }

    public boolean isEmpty() {
        return prices.isEmpty();
    }

    private static String text(ONode node, String key) {
        ONode value = node.get(key);
        return value == null ? null : value.getString();
    }

    private static long number(ONode node, String key) {
        ONode value = node.get(key);
        return value == null ? 0L : Math.max(0L, value.getLong());
    }
}
