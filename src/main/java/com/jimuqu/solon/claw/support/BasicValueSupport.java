package com.jimuqu.solon.claw.support;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
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
}
