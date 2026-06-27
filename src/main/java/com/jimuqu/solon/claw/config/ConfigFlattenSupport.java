package com.jimuqu.solon.claw.config;

import java.util.Map;

/** 配置 Map 扁平化辅助逻辑，统一生成点号分隔的配置键。 */
public final class ConfigFlattenSupport {
    /** 工具类不允许创建实例。 */
    private ConfigFlattenSupport() {}

    /**
     * 将嵌套 Map 扁平化为点号分隔键。
     *
     * @param prefix 当前键前缀。
     * @param input 嵌套配置 Map。
     * @param output 扁平化输出 Map。
     */
    public static void flatten(String prefix, Map<?, ?> input, Map<String, Object> output) {
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key =
                    prefix.length() == 0
                            ? String.valueOf(entry.getKey())
                            : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<?, ?>) value, output);
            } else {
                output.put(key, value);
            }
        }
    }
}
