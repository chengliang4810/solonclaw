package com.jimuqu.solon.claw.plugin;

/** 插件入口接口。插件只需实现此接口并在 register 中完成注册。 */
public interface AgentPlugin {
    /**
     * 注册插件提供的工具、Provider、命令、平台适配器或钩子。
     *
     * @param ctx 插件注册上下文，限定插件只能通过该门面接入主应用。
     */
    void register(AgentPluginContext ctx);

    /** 插件卸载或应用关闭时释放外部连接、线程池等资源。 */
    default void destroy() {}
}
