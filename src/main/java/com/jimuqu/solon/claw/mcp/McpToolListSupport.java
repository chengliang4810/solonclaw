package com.jimuqu.solon.claw.mcp;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** 复用 MCP 工具清单 JSON 的数量、名称和差异计算逻辑。 */
public final class McpToolListSupport {
    /** 工具类不允许创建实例。 */
    private McpToolListSupport() {}

    /**
     * 返回映射中第一个存在的键值。
     *
     * @param map 待读取映射。
     * @param keys 候选键。
     * @return 第一个存在的键值，未命中时返回 null。
     */
    public static Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    /**
     * 返回映射中第一个存在的文本值。
     *
     * @param map 待读取映射。
     * @param keys 候选键。
     * @return 去除首尾空白的文本值。
     */
    public static String firstText(Map<String, Object> map, String... keys) {
        Object value = firstPresent(map, keys);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 统计 MCP 工具 JSON 中的工具数量。
     *
     * @param toolsJson 工具清单 JSON。
     * @return 工具数量；非空非数组文本按一个工具处理。
     */
    public static int countTools(String toolsJson) {
        Object parsed = parse(toolsJson);
        if (parsed instanceof List) {
            return ((List<?>) parsed).size();
        }
        return StrUtil.isBlank(toolsJson) ? 0 : 1;
    }

    /**
     * 提取 MCP 工具 JSON 中的工具名称。
     *
     * @param toolsJson 工具清单 JSON。
     * @return 排序后的去重工具名称。
     */
    @SuppressWarnings("unchecked")
    public static List<String> toolNames(String toolsJson) {
        Object parsed = parse(toolsJson);
        List<String> result = new ArrayList<String>();
        if (!(parsed instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) item;
            String name = firstText(map, "prefixed_name", "name");
            if (StrUtil.isNotBlank(name) && !result.contains(name)) {
                result.add(name);
            }
        }
        Collections.sort(result);
        return result;
    }

    /**
     * 返回左侧列表中存在、右侧列表中不存在的非空元素。
     *
     * @param left 左侧列表。
     * @param right 右侧列表。
     * @return 去重后的差异列表。
     */
    public static List<String> difference(List<String> left, List<String> right) {
        List<String> result = new ArrayList<String>();
        List<String> safeRight = right == null ? Collections.<String>emptyList() : right;
        if (left == null) {
            return result;
        }
        for (String item : left) {
            if (StrUtil.isNotBlank(item) && !safeRight.contains(item) && !result.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 宽松解析工具 JSON，解析失败时返回 null。
     *
     * @param json 原始 JSON 文本。
     * @return 解析后的对象。
     */
    private static Object parse(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return ONode.deserialize(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
