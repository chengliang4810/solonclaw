package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.cli.CliMode;

/** 保存当前进程启动模式，供配置类区分服务端生命周期与终端命令生命周期。 */
public final class StartupModeContext {
    /** 当前进程启动模式，默认按服务端模式处理，避免测试或嵌入式启动遗漏初始化。 */
    private static volatile CliMode mode = new CliMode(CliMode.Kind.SERVER, null, null);

    /** 禁止实例化工具类。 */
    private StartupModeContext() {}

    /**
     * 写入当前进程启动模式。
     *
     * @param cliMode 已解析的启动模式；为空时回退为服务端模式。
     */
    public static void set(CliMode cliMode) {
        mode = cliMode == null ? new CliMode(CliMode.Kind.SERVER, null, null) : cliMode;
    }

    /**
     * 读取当前进程启动模式。
     *
     * @return 当前启动模式。
     */
    public static CliMode get() {
        return mode;
    }

    /**
     * 判断是否需要启动服务端后台生命周期。
     *
     * @return 仅 HTTP/WebSocket 服务端模式返回 true，CLI/TUI/completion 返回 false。
     */
    public static boolean shouldStartServerLifecycle() {
        return mode == null || mode.shouldStartNetworkListeners();
    }
}
