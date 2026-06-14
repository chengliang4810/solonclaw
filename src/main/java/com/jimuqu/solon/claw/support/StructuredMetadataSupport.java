package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** 运行事件结构化元数据的脱敏与序列化工具。 */
public final class StructuredMetadataSupport {
    /** 结构化元数据中单个字符串字段的默认最大长度。 */
    private static final int DEFAULT_STRING_LIMIT = 2000;

    /** 非法 JSON 降级保存时的最大原文长度。 */
    private static final int DEFAULT_FALLBACK_LIMIT = 4000;

    /** 禁止创建工具类实例。 */
    private StructuredMetadataSupport() {}

    /**
     * 将结构化元数据脱敏后序列化为合法 JSON，避免长预览截断破坏整体 JSON 结构。
     *
     * @param metadata 事件元数据。
     * @return 返回可持久化的 JSON 文本。
     */
    public static String serializeRedacted(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        return ONode.serialize(redactValue(metadata, DEFAULT_STRING_LIMIT));
    }

    /**
     * 对已经序列化的元数据 JSON 做结构化脱敏；解析失败时才按普通文本降级保存。
     *
     * @param metadataJson 元数据 JSON。
     * @return 返回脱敏后的合法 JSON，或降级后的普通文本。
     */
    public static String redactJson(String metadataJson) {
        if (StrUtil.isBlank(metadataJson)) {
            return metadataJson;
        }
        try {
            Object parsed = ONode.deserialize(metadataJson, Object.class);
            return ONode.serialize(redactValue(parsed, DEFAULT_STRING_LIMIT));
        } catch (Exception ignored) {
            return SecretRedactor.redact(metadataJson, DEFAULT_FALLBACK_LIMIT);
        }
    }

    /**
     * 递归脱敏元数据值，保留 Map/List/数字/布尔类型，避免把结构化数据拍平成字符串。
     *
     * @param value 元数据值。
     * @param stringLimit 单个字符串字段最大长度。
     * @return 返回脱敏后的值。
     */
    @SuppressWarnings("unchecked")
    private static Object redactValue(Object value, int stringLimit) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                result.put(key, redactValue(entry.getValue(), stringLimit));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                result.add(redactValue(item, stringLimit));
            }
            return result;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> result = new ArrayList<Object>(length);
            for (int i = 0; i < length; i++) {
                result.add(redactValue(Array.get(value, i), stringLimit));
            }
            return result;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return SecretRedactor.redact(String.valueOf(value), stringLimit);
    }
}
