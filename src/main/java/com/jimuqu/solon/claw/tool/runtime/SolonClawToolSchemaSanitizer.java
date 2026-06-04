package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** Tool JSON-schema sanitizer for MCP and dynamic tool schemas. */
public final class SolonClawToolSchemaSanitizer {
    private static final String[] TOP_LEVEL_FORBIDDEN_COMBINATORS =
            new String[] {"allOf", "anyOf", "oneOf", "enum", "not"};
    private static final String[] UNSUPPORTED_SCHEMA_KEYWORDS =
            new String[] {
                "$schema",
                "$id",
                "$anchor",
                "$dynamicAnchor",
                "$dynamicRef",
                "$ref",
                "$defs",
                "definitions",
                "allOf",
                "anyOf",
                "oneOf",
                "not",
                "if",
                "then",
                "else",
                "dependentSchemas",
                "dependentRequired",
                "propertyNames",
                "unevaluatedProperties",
                "unevaluatedItems",
                "contains",
                "minContains",
                "maxContains",
                "prefixItems"
            };

    private SolonClawToolSchemaSanitizer() {}

    public static Map<String, Object> policySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("enabled", Boolean.TRUE);
        summary.put(
                "appliesTo",
                Arrays.asList(
                        "localFunctionTools",
                        "toolProviders",
                        "mcpLocalToolListing",
                        "mcpRemoteToolImport"));
        summary.put("inputSchemaSanitized", Boolean.TRUE);
        summary.put("outputFunctionToolSchemaSanitized", Boolean.TRUE);
        summary.put("mcpInputSchemaSanitized", Boolean.TRUE);
        summary.put("invalidSchemaDefaultsToObject", Boolean.TRUE);
        summary.put("topLevelObjectRequired", Boolean.TRUE);
        summary.put("propertiesInjectedForObject", Boolean.TRUE);
        summary.put("requiredPrunedToKnownProperties", Boolean.TRUE);
        summary.put("nullableUnionCollapsed", Boolean.TRUE);
        summary.put("patternAndFormatStripped", Boolean.TRUE);
        summary.put("patternAndFormatKeywords", Arrays.asList("pattern", "format"));
        summary.put("unsupportedKeywordsStripped", Arrays.asList(UNSUPPORTED_SCHEMA_KEYWORDS));
        summary.put(
                "topLevelForbiddenCombinatorsStripped",
                Arrays.asList(TOP_LEVEL_FORBIDDEN_COMBINATORS));
        summary.put("schemaObjectSanitizationNonMutating", Boolean.TRUE);
        summary.put("jsonLibrary", "snack4");
        return summary;
    }

    public static String sanitizeSchemaJson(String schemaJson) {
        Object data = parseJsonObject(schemaJson);
        if (!(data instanceof Map)) {
            data = defaultObjectSchema();
        }
        Object sanitized = sanitizeNode(data);
        if (!(sanitized instanceof Map)) {
            sanitized = defaultObjectSchema();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> top = (Map<String, Object>) sanitized;
        if (!"object".equals(top.get("type"))) {
            top.put("type", "object");
        }
        if (!(top.get("properties") instanceof Map)) {
            top.put("properties", new LinkedHashMap<String, Object>());
        }
        Object stripped = stripNullableUnions(top, true);
        Object patternSafe = stripPatternAndFormat(stripped).getSchema();
        Object compatible = stripUnsupportedSchemaKeywords(patternSafe);
        compatible = stripTopLevelForbiddenCombinators(compatible);
        return ONode.serialize(compatible instanceof Map ? compatible : defaultObjectSchema());
    }

    public static Object sanitizeSchemaObject(Object schema) {
        Object sanitized = sanitizeNode(schema);
        if (!(sanitized instanceof Map)) {
            return defaultObjectSchema();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> top = (Map<String, Object>) sanitized;
        if (!"object".equals(top.get("type"))) {
            top.put("type", "object");
        }
        if (!(top.get("properties") instanceof Map)) {
            top.put("properties", new LinkedHashMap<String, Object>());
        }
        Object stripped = stripNullableUnions(top, true);
        Object patternSafe = stripPatternAndFormat(stripped).getSchema();
        Object compatible = stripUnsupportedSchemaKeywords(patternSafe);
        compatible = stripTopLevelForbiddenCombinators(compatible);
        return compatible instanceof Map ? compatible : defaultObjectSchema();
    }

    public static StripResult stripPatternAndFormat(Object schema) {
        Counter counter = new Counter();
        Object copy = deepCopy(schema);
        stripPatternAndFormatInPlace(copy, counter);
        return new StripResult(copy, counter.count);
    }

    private static Object parseJsonObject(String schemaJson) {
        String raw = StrUtil.nullToEmpty(schemaJson).trim();
        if (raw.length() == 0) {
            return null;
        }
        try {
            return ONode.ofJson(raw).toData();
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeNode(Object node) {
        if (node instanceof String) {
            String value = ((String) node).trim();
            if ("object".equals(value)) {
                return defaultObjectSchema();
            }
            if ("string".equals(value)
                    || "number".equals(value)
                    || "integer".equals(value)
                    || "boolean".equals(value)
                    || "array".equals(value)
                    || "null".equals(value)) {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("type", value);
                return result;
            }
            return defaultObjectSchema();
        }
        if (node instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<Object>) node) {
                result.add(sanitizeNode(item));
            }
            return result;
        }
        if (!(node instanceof Map)) {
            return node;
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) node).entrySet()) {
            String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if ("type".equals(key) && value instanceof List) {
                String type = firstNonNullType((List<?>) value);
                result.put("type", StrUtil.blankToDefault(type, "object"));
                if (containsType((List<?>) value, "null")) {
                    result.put("nullable", Boolean.TRUE);
                }
            } else if (isSchemaMapKey(key) && value instanceof Map) {
                Map<String, Object> nested = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> nestedEntry : ((Map<?, ?>) value).entrySet()) {
                    nested.put(
                            nestedEntry.getKey() == null
                                    ? ""
                                    : String.valueOf(nestedEntry.getKey()),
                            sanitizeNode(nestedEntry.getValue()));
                }
                result.put(key, nested);
            } else if ("items".equals(key) || "additionalProperties".equals(key)) {
                result.put(key, value instanceof Boolean ? value : sanitizeNode(value));
            } else if (isUnionKey(key) && value instanceof List) {
                List<Object> nested = new ArrayList<Object>();
                for (Object item : (List<Object>) value) {
                    nested.add(sanitizeNode(item));
                }
                result.put(key, nested);
            } else if ("required".equals(key) || "enum".equals(key) || "examples".equals(key)) {
                result.put(key, deepCopy(value));
            } else if (value instanceof Map || value instanceof List) {
                result.put(key, sanitizeNode(value));
            } else {
                result.put(key, value);
            }
        }
        if ("object".equals(result.get("type")) && !(result.get("properties") instanceof Map)) {
            result.put("properties", new LinkedHashMap<String, Object>());
        }
        pruneRequired(result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object stripNullableUnions(Object schema, boolean keepNullableHint) {
        if (schema instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<Object>) schema) {
                result.add(stripNullableUnions(item, keepNullableHint));
            }
            return result;
        }
        if (!(schema instanceof Map)) {
            return schema;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) schema).entrySet()) {
            result.put(
                    entry.getKey() == null ? "" : String.valueOf(entry.getKey()),
                    stripNullableUnions(entry.getValue(), keepNullableHint));
        }
        for (String key : new String[] {"anyOf", "oneOf"}) {
            Object variantsRaw = result.get(key);
            if (!(variantsRaw instanceof List)) {
                continue;
            }
            List<Object> variants = (List<Object>) variantsRaw;
            List<Object> nonNull = new ArrayList<Object>();
            for (Object variant : variants) {
                if (!isNullSchema(variant)) {
                    nonNull.add(variant);
                }
            }
            if (nonNull.size() == 1 && nonNull.size() != variants.size()) {
                Map<String, Object> replacement =
                        nonNull.get(0) instanceof Map
                                ? castStringMap(deepCopy(nonNull.get(0)))
                                : defaultObjectSchema();
                if (keepNullableHint) {
                    replacement.put("nullable", Boolean.TRUE);
                }
                for (String meta : new String[] {"title", "description", "default", "examples"}) {
                    if (result.containsKey(meta) && !replacement.containsKey(meta)) {
                        replacement.put(meta, deepCopy(result.get(meta)));
                    }
                }
                return stripNullableUnions(replacement, keepNullableHint);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object stripTopLevelForbiddenCombinators(Object schema) {
        if (!(schema instanceof Map)) {
            return schema;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.putAll((Map<String, Object>) schema);
        for (String key : TOP_LEVEL_FORBIDDEN_COMBINATORS) {
            result.remove(key);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object stripUnsupportedSchemaKeywords(Object schema) {
        Object copy = deepCopy(schema);
        stripUnsupportedSchemaKeywordsInPlace(copy);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void stripUnsupportedSchemaKeywordsInPlace(Object node) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            boolean schemaNode =
                    map.containsKey("type")
                            || map.containsKey("properties")
                            || map.containsKey("items")
                            || map.containsKey("additionalProperties")
                            || map.containsKey("anyOf")
                            || map.containsKey("oneOf")
                            || map.containsKey("allOf")
                            || map.containsKey("$ref");
            for (String key : new ArrayList<String>(map.keySet())) {
                if (schemaNode && isUnsupportedSchemaKeyword(key)) {
                    map.remove(key);
                } else {
                    stripUnsupportedSchemaKeywordsInPlace(map.get(key));
                }
            }
            pruneRequired(map);
        } else if (node instanceof List) {
            for (Object item : (List<Object>) node) {
                stripUnsupportedSchemaKeywordsInPlace(item);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void stripPatternAndFormatInPlace(Object node, Counter counter) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            boolean schemaNode =
                    map.containsKey("type")
                            || map.containsKey("anyOf")
                            || map.containsKey("oneOf")
                            || map.containsKey("allOf");
            for (String key : new ArrayList<String>(map.keySet())) {
                if (schemaNode && ("pattern".equals(key) || "format".equals(key))) {
                    map.remove(key);
                    counter.count++;
                } else {
                    stripPatternAndFormatInPlace(map.get(key), counter);
                }
            }
        } else if (node instanceof List) {
            for (Object item : (List<Object>) node) {
                stripPatternAndFormatInPlace(item, counter);
            }
        }
    }

    private static boolean isSchemaMapKey(String key) {
        return "properties".equals(key)
                || "patternProperties".equals(key)
                || "$defs".equals(key)
                || "definitions".equals(key);
    }

    private static boolean isUnionKey(String key) {
        return "anyOf".equals(key) || "oneOf".equals(key) || "allOf".equals(key);
    }

    private static boolean isUnsupportedSchemaKeyword(String key) {
        for (String unsupported : UNSUPPORTED_SCHEMA_KEYWORDS) {
            if (unsupported.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonNullType(List<?> values) {
        for (Object item : values) {
            if (item instanceof String && !"null".equals(item)) {
                return (String) item;
            }
        }
        return null;
    }

    private static boolean containsType(List<?> values, String type) {
        for (Object item : values) {
            if (type.equals(item)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean isNullSchema(Object value) {
        return value instanceof Map && "null".equals(((Map<String, Object>) value).get("type"));
    }

    @SuppressWarnings("unchecked")
    private static void pruneRequired(Map<String, Object> schema) {
        if (!"object".equals(schema.get("type"))
                || !(schema.get("required") instanceof List)
                || !(schema.get("properties") instanceof Map)) {
            return;
        }
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        List<Object> required = (List<Object>) schema.get("required");
        List<String> valid = new ArrayList<String>();
        for (Object item : required) {
            if (item instanceof String && properties.containsKey(item)) {
                valid.add((String) item);
            }
        }
        if (valid.isEmpty()) {
            schema.remove("required");
        } else if (valid.size() != required.size()) {
            schema.put("required", valid);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(
                        entry.getKey() == null ? "" : String.valueOf(entry.getKey()),
                        entry.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(
                        entry.getKey() == null ? "" : String.valueOf(entry.getKey()),
                        deepCopy(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<Object>) value) {
                result.add(deepCopy(item));
            }
            return result;
        }
        return value;
    }

    private static Map<String, Object> defaultObjectSchema() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", "object");
        result.put("properties", new LinkedHashMap<String, Object>());
        return result;
    }

    private static class Counter {
        private int count;
    }

    public static class StripResult {
        private final Object schema;
        private final int strippedCount;

        private StripResult(Object schema, int strippedCount) {
            this.schema = schema;
            this.strippedCount = strippedCount;
        }

        public Object getSchema() {
            return schema;
        }

        public int getStrippedCount() {
            return strippedCount;
        }
    }
}
