package com.jimuqu.solon.claw.plugin;

/** 钩子名称常量。 */
public final class AgentHookName {
    /** 创建Agent钩子名称实例。 */
    private AgentHookName() {}

    /** PRE工具CALL的统一常量值。 */
    public static final String PRE_TOOL_CALL = "pre_tool_call";

    /** POST工具CALL的统一常量值。 */
    public static final String POST_TOOL_CALL = "post_tool_call";

    /** PRE大模型CALL的统一常量值。 */
    public static final String PRE_LLM_CALL = "pre_llm_call";

    /** POST大模型CALL的统一常量值。 */
    public static final String POST_LLM_CALL = "post_llm_call";

    /** PREAPI请求的统一常量值。 */
    public static final String PRE_API_REQUEST = "pre_api_request";

    /** POSTAPI请求的统一常量值。 */
    public static final String POST_API_REQUEST = "post_api_request";

    /** ON会话END的统一常量值。 */
    public static final String ON_SESSION_END = "on_session_end";
}
