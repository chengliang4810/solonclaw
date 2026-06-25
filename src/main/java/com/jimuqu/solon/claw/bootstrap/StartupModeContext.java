package com.jimuqu.solon.claw.bootstrap;

/** 保存当前进程启动模式，供配置类判断是否启动服务端生命周期。 */
public final class StartupModeContext {
    /** 当前进程始终以服务端模式运行。 */
    private static volatile boolean serverMode = true;

    /** 禁止实例化工具类。 */
    private StartupModeContext() {}

    /**
     * 设置服务端模式标志。
     *
     * @param enabled 是否启用服务端模式。
     */
    public static void setServerMode(boolean enabled) {
        serverMode = enabled;
    }

    /**
     * 判断是否需要启动服务端后台生命周期。
     *
     * @return 服务端模式返回 true。
     */
    public static boolean shouldStartServerLifecycle() {
        return serverMode;
    }
}
