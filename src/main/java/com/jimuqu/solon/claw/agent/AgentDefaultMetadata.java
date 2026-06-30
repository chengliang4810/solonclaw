package com.jimuqu.solon.claw.agent;

/** 集中提供内置默认 Agent 的展示元数据，避免 Dashboard、slash 与运行时提示词文案漂移。 */
public final class AgentDefaultMetadata {
    /** 默认 Agent 面向用户展示的名称。 */
    private static final String DISPLAY_NAME = "默认 Agent";

    /** 默认 Agent 面向用户展示的说明。 */
    private static final String DESCRIPTION = "映射默认工作区的默认行为";

    /** 禁止实例化工具类。 */
    private AgentDefaultMetadata() {}

    /**
     * 读取默认 Agent 展示名。
     *
     * @return 默认 Agent 展示名。
     */
    public static String displayName() {
        return DISPLAY_NAME;
    }

    /**
     * 读取默认 Agent 展示说明。
     *
     * @return 默认 Agent 展示说明。
     */
    public static String description() {
        return DESCRIPTION;
    }
}
