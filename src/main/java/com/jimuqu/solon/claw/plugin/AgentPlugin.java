package com.jimuqu.solon.claw.plugin;

/** 插件入口接口。插件只需实现此接口并在 register 中完成注册。 */
public interface AgentPlugin {
    /**
     * 执行register相关逻辑。
     *
     * @param ctx ctx 参数。
     */
    void register(AgentPluginContext ctx);

    /** 执行destroy相关逻辑。 */
    default void destroy() {}
}
