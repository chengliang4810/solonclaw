package com.jimuqu.solon.claw.media;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 媒体选项读取工具类，封装从 Map&lt;String, Object&gt; 中安全读取文本、数值等通用逻辑。
 *
 * <p>供 OpenAiSttProvider、OpenAiTtsProvider 等媒体提供方共享使用，消除跨文件重复代码。
 */
final class MediaOptionHelper {

    private MediaOptionHelper() {
        // 工具类不允许实例化
    }

    /**
     * 从工具选项读取首个非空字符串，支持多个键名回退。
     *
     * @param options 选项映射。
     * @param keys 键名列表，按顺序尝试。
     * @return 非空字符串；所有键均无有效值时返回空字符串。
     */
    static String optionText(Map<String, Object> options, String... keys) {
        if (options == null) {
            return "";
        }
        for (String key : keys) {
            Object value = options.get(key);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    /**
     * 从工具选项读取有限数值，无法解析时忽略该选项。
     *
     * @param options 选项映射。
     * @param key 键名。
     * @return 解析后的 Double；选项缺失或不可解析时返回 null。
     */
    static Double optionNumber(Map<String, Object> options, String key) {
        if (options == null || options.get(key) == null) {
            return null;
        }
        Object value = options.get(key);
        if (value instanceof Number) {
            return Double.valueOf(((Number) value).doubleValue());
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 把秒级配置转换为 Hutool 使用的毫秒超时。
     *
     * @param seconds 秒数。
     * @return 毫秒超时，最小值为 1000ms。
     */
    static int timeoutMillis(int seconds) {
        long millis = Math.max(1L, seconds) * 1000L;
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    /** 按主图、参考图顺序汇总非空图片来源。 */
    static List<String> imageSources(String imageUrl, List<String> references) {
        List<String> result = new ArrayList<String>();
        if (StrUtil.isNotBlank(imageUrl)) {
            result.add(imageUrl.trim());
        }
        if (references != null) {
            for (String reference : references) {
                if (StrUtil.isNotBlank(reference)) {
                    result.add(reference.trim());
                }
            }
        }
        return result;
    }
}
