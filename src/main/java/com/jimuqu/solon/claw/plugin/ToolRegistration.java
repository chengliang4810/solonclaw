package com.jimuqu.solon.claw.plugin;

import java.util.Map;
import java.util.function.Function;

/** 工具注册参数。 */
public class ToolRegistration {
    /** 工具唯一名称，会暴露给 Agent 工具调用协议。 */
    private final String name;

    /** 工具所属分组，用于 dashboard 或审计日志按能力域展示。 */
    private final String toolset;

    /** 工具入参 schema，保持 Map 结构以兼容 Solon AI 工具描述。 */
    private final Map<String, Object> schema;

    /** 工具执行函数，入参为模型传入的参数 Map，返回 JSON 或文本结果。 */
    private final Function<Map<String, Object>, String> handler;

    /** 工具说明文本，给模型和管理界面理解工具用途。 */
    private String description = "";

    /** 可选展示图标，不参与工具协议逻辑。 */
    private String emoji = "";

    /**
     * 创建工具注册信息。
     *
     * @param name 工具唯一名称。
     * @param toolset 工具所属分组。
     * @param schema 工具入参 schema。
     * @param handler 工具执行函数。
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
     * 读取工具名称。
     *
     * @return 工具唯一名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 读取工具所属分组。
     *
     * @return 工具集名称。
     */
    public String getToolset() {
        return toolset;
    }

    /**
     * 读取工具 schema。
     *
     * @return 工具入参 schema。
     */
    public Map<String, Object> getSchema() {
        return schema;
    }

    /**
     * 读取工具执行函数。
     *
     * @return 工具执行函数。
     */
    public Function<Map<String, Object>, String> getHandler() {
        return handler;
    }

    /**
     * 读取工具说明。
     *
     * @return 模型和管理界面可见的工具说明。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 读取展示图标。
     *
     * @return 可选展示图标。
     */
    public String getEmoji() {
        return emoji;
    }

    /**
     * 设置工具说明文本。
     *
     * @return 当前注册对象，便于链式设置。
     */
    public ToolRegistration description(String description) {
        this.description = description;
        return this;
    }

    /**
     * 设置展示图标。
     *
     * @return 当前注册对象，便于链式设置。
     */
    public ToolRegistration emoji(String emoji) {
        this.emoji = emoji;
        return this;
    }
}
