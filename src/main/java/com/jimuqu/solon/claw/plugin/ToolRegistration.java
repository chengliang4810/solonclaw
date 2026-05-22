package com.jimuqu.solon.claw.plugin;

import java.util.Map;
import java.util.function.Function;

/** 工具注册参数。 */
public class ToolRegistration {
    private final String name;
    private final String toolset;
    private final Map<String, Object> schema;
    private final Function<Map<String, Object>, String> handler;
    private String description = "";
    private String emoji = "";

    public ToolRegistration(String name, String toolset, Map<String, Object> schema,
                            Function<Map<String, Object>, String> handler) {
        this.name = name;
        this.toolset = toolset;
        this.schema = schema;
        this.handler = handler;
    }

    public String getName() { return name; }
    public String getToolset() { return toolset; }
    public Map<String, Object> getSchema() { return schema; }
    public Function<Map<String, Object>, String> getHandler() { return handler; }
    public String getDescription() { return description; }
    public String getEmoji() { return emoji; }

    public ToolRegistration description(String description) {
        this.description = description;
        return this;
    }

    public ToolRegistration emoji(String emoji) {
        this.emoji = emoji;
        return this;
    }
}
