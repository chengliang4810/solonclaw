package com.jimuqu.solon.claw.plugin;

/** 钩子名称常量。 */
public final class AgentHookName {
    /** 禁止实例化钩子名称常量类。 */
    private AgentHookName() {}

    /** 工具调用前触发，可用于安全审批或参数改写。 */
    public static final String PRE_TOOL_CALL = "pre_tool_call";

    /** 工具调用完成后触发，可用于审计、结果转换或指标记录。 */
    public static final String POST_TOOL_CALL = "post_tool_call";

    /** 模型调用前触发，预留给上下文注入和请求审计。 */
    public static final String PRE_LLM_CALL = "pre_llm_call";

    /** 模型调用后触发，预留给响应审计和统计汇总。 */
    public static final String POST_LLM_CALL = "post_llm_call";

    /** 外部 API 请求前触发，当前用于 Solon AI ReAct 模型请求桥接。 */
    public static final String PRE_API_REQUEST = "pre_api_request";

    /** 外部 API 请求后触发，当前用于 Solon AI ReAct 模型响应桥接。 */
    public static final String POST_API_REQUEST = "post_api_request";

    /** 会话结束时触发，供插件释放会话级资源或沉淀记忆。 */
    public static final String ON_SESSION_END = "on_session_end";
}
