package com.jimuqu.solon.claw.plugin;

/** 钩子名称常量。 */
public final class AgentHookName {
    private AgentHookName() {}

    public static final String PRE_TOOL_CALL = "pre_tool_call";
    public static final String POST_TOOL_CALL = "post_tool_call";
    public static final String PRE_LLM_CALL = "pre_llm_call";
    public static final String POST_LLM_CALL = "post_llm_call";
    public static final String PRE_API_REQUEST = "pre_api_request";
    public static final String POST_API_REQUEST = "post_api_request";
    public static final String ON_SESSION_END = "on_session_end";
}
