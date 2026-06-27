package com.jimuqu.solon.claw.support;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 基础值判断辅助类，统一承接字符串、集合、Map 与字节数组的空值语义。 */
public final class BasicValueSupport {
    /** 禁止创建无状态工具实例。 */
    private BasicValueSupport() {}

    /**
     * 判断字节数组是否为空。
     *
     * @param bytes 待检查的字节数组。
     * @return 数组为 null 或长度为 0 时返回 true。
     */
    public static boolean isEmpty(byte[] bytes) {
        return bytes == null || bytes.length == 0;
    }

    /**
     * 判断集合是否为空，委托 Hutool 保持项目内基础判断口径一致。
     *
     * @param values 待检查的集合。
     * @return 集合为 null 或没有元素时返回 true。
     */
    public static boolean isEmpty(Collection<?> values) {
        return CollUtil.isEmpty(values);
    }

    /**
     * 判断 Map 是否为空，委托 Hutool 保持项目内基础判断口径一致。
     *
     * @param values 待检查的 Map。
     * @return Map 为 null 或没有键值时返回 true。
     */
    public static boolean isEmpty(Map<?, ?> values) {
        return MapUtil.isEmpty(values);
    }

    /**
     * 为可选列表提供不可变空列表兜底。
     *
     * @param values 原始列表。
     * @param <T> 列表元素类型。
     * @return 原始列表为 null 时返回空列表，否则返回原始列表。
     */
    public static <T> List<T> emptyListIfNull(List<T> values) {
        return values == null ? Collections.<T>emptyList() : values;
    }

    /**
     * 为可选 Map 提供不可变空 Map 兜底。
     *
     * @param values 原始 Map。
     * @param <K> 键类型。
     * @param <V> 值类型。
     * @return 原始 Map 为 null 时返回空 Map，否则返回原始 Map。
     */
    public static <K, V> Map<K, V> emptyMapIfNull(Map<K, V> values) {
        return values == null ? Collections.<K, V>emptyMap() : values;
    }

    /**
     * 将可选 Map 复制为可变有序 Map。
     *
     * @param values 原始 Map。
     * @param <K> 键类型。
     * @param <V> 值类型。
     * @return 原始 Map 为 null 时返回空 LinkedHashMap，否则按原顺序复制。
     */
    public static <K, V> LinkedHashMap<K, V> mutableLinkedMap(Map<K, V> values) {
        return values == null
                ? new LinkedHashMap<K, V>()
                : new LinkedHashMap<K, V>(values);
    }

    /**
     * 将字符串安全转换为去首尾空白后的文本。
     *
     * @param value 原始字符串。
     * @return null 转为空字符串，其余值返回 trim 后文本。
     */
    public static String trimToEmpty(String value) {
        return StrUtil.nullToEmpty(value).trim();
    }

    /**
     * 剥离首尾匹配的单引号或双引号。
     *
     * @param value 原始字符串。
     * @return 去掉包裹引号并 trim 后的文本。
     */
    public static String stripMatchingQuotes(String value) {
        String text = trimToEmpty(value);
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }

    /**
     * 按常见 shell 规则切分参数，支持单引号与双引号包裹的值，不处理转义字符。
     *
     * @param input 输入文本。
     * @return 参数列表。
     */
    public static List<String> shellTokens(String input) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        String safeInput = StrUtil.nullToEmpty(input);
        for (int i = 0; i < safeInput.length(); i++) {
            char ch = safeInput.charAt(i);
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

    /**
     * 判断文本是否包含 ISO 控制字符，用于路径、配置键等安全输入边界。
     *
     * @param value 待检查文本。
     * @return 包含控制字符时返回 true；null 返回 false。
     */
    public static boolean containsControlCharacter(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将任意键 Map 递归清理为字符串键、有序且可变的 Map，适配配置解析后的通用对象树。
     *
     * @param input 原始 Map。
     * @return 清理后的 Map。
     */
    public static Map<String, Object> sanitizeMap(Map<?, ?> input) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map) {
                value = sanitizeMap((Map<?, ?>) value);
            } else if (value instanceof List) {
                value = sanitizeList((List<?>) value);
            }
            result.put(String.valueOf(entry.getKey()), value);
        }
        return result;
    }

    /**
     * 递归清理列表中的 Map 和子列表，保持配置对象树的类型稳定。
     *
     * @param input 原始列表。
     * @return 清理后的列表。
     */
    public static List<Object> sanitizeList(List<?> input) {
        List<Object> result = new ArrayList<Object>();
        for (Object item : input) {
            if (item instanceof Map) {
                result.add(sanitizeMap((Map<?, ?>) item));
            } else if (item instanceof List) {
                result.add(sanitizeList((List<?>) item));
            } else {
                result.add(item);
            }
        }
        return result;
    }
}
