package com.jimuqu.solon.claw.plugin;

/** 插件入口接口。插件只需实现此接口并在 register 中完成注册。 */
public interface AgentPlugin {
    void register(AgentPluginContext ctx);

    default void destroy() {}
}
