package com.jimuqu.solon.claw.tool.runtime;

import java.util.Map;
import java.util.function.Function;

/** 描述可注册到模型工具表的运行时工具。 */
public class ToolRegistration {
    /** 工具唯一名称。 */
    private final String name;

    /** 工具所属能力分组。 */
    private final String toolset;

    /** 工具入参 schema。 */
    private final Map<String, Object> schema;

    /** 工具执行函数。 */
    private final Function<Map<String, Object>, String> handler;

    /** 模型和管理界面可见的工具说明。 */
    private String description = "";

    /** 可选展示图标。 */
    private String emoji = "";

    /** 创建运行时工具描述。 */
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

    /** 读取工具名称。 */
    public String getName() {
        return name;
    }

    /** 读取工具分组。 */
    public String getToolset() {
        return toolset;
    }

    /** 读取工具 schema。 */
    public Map<String, Object> getSchema() {
        return schema;
    }

    /** 读取工具执行函数。 */
    public Function<Map<String, Object>, String> getHandler() {
        return handler;
    }

    /** 读取工具说明。 */
    public String getDescription() {
        return description;
    }

    /** 读取展示图标。 */
    public String getEmoji() {
        return emoji;
    }

    /** 设置工具说明。 */
    public ToolRegistration description(String value) {
        description = value;
        return this;
    }

    /** 设置展示图标。 */
    public ToolRegistration emoji(String value) {
        emoji = value;
        return this;
    }
}
