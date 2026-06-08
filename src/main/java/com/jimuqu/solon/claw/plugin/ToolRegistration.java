package com.jimuqu.solon.claw.plugin;

import java.util.Map;
import java.util.function.Function;

/** 工具注册参数。 */
public class ToolRegistration {
    /** 记录工具Registration中的名称。 */
    private final String name;

    /** 记录工具Registration中的工具集。 */
    private final String toolset;

    /** 保存结构映射，便于按键快速查询。 */
    private final Map<String, Object> schema;

    /** 保存handler映射，便于按键快速查询。 */
    private final Function<Map<String, Object>, String> handler;

    /** 记录工具Registration中的描述。 */
    private String description = "";

    /** 记录工具Registration中的emoji。 */
    private String emoji = "";

    /**
     * 创建工具Registration实例，并注入运行所需依赖。
     *
     * @param name 名称参数。
     * @param toolset 工具集参数。
     * @param schema schema 参数。
     * @param handler handler 参数。
     */
    public ToolRegistration(
            String name,
            String toolset,
            Map<String, Object> schema,
            Function<Map<String, Object>, String> handler) {
        this.name = name;
        this.toolset = toolset;
        this.schema = schema;
        this.handler = handler;
    }

    /**
     * 读取名称。
     *
     * @return 返回读取到的名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 读取Toolset。
     *
     * @return 返回读取到的Toolset。
     */
    public String getToolset() {
        return toolset;
    }

    /**
     * 读取结构。
     *
     * @return 返回读取到的结构。
     */
    public Map<String, Object> getSchema() {
        return schema;
    }

    /**
     * 读取Handler。
     *
     * @return 返回读取到的Handler。
     */
    public Function<Map<String, Object>, String> getHandler() {
        return handler;
    }

    /**
     * 读取Description。
     *
     * @return 返回读取到的Description。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 读取Emoji。
     *
     * @return 返回读取到的Emoji。
     */
    public String getEmoji() {
        return emoji;
    }

    /**
     * 执行description相关逻辑。
     *
     * @param description 描述参数。
     * @return 返回description结果。
     */
    public ToolRegistration description(String description) {
        this.description = description;
        return this;
    }

    /**
     * 执行emoji相关逻辑。
     *
     * @param emoji emoji 参数。
     * @return 返回emoji结果。
     */
    public ToolRegistration emoji(String emoji) {
        this.emoji = emoji;
        return this;
    }
}
