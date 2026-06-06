package com.jimuqu.solon.claw.cli;

/** 承载CLI模式相关状态和辅助逻辑。 */
public class CliMode {
    /** 枚举Kind的可选值，保证状态表达在各模块间一致。 */
    public enum Kind {
        /** 表示SERVER枚举值。 */
        SERVER,
        /** 表示CLI枚举值。 */
        CLI,
        /** 表示TUI枚举值。 */
        TUI,
        /** 表示COMPLETION枚举值。 */
        COMPLETION
    }

    /** 记录CLI模式中的kind。 */
    private final Kind kind;

    /** 记录CLI模式中的输入。 */
    private final String input;

    /** 记录CLI模式中的会话标识。 */
    private final String sessionId;

    /**
     * 创建Cli模式实例，并注入运行所需依赖。
     *
     * @param kind kind 参数。
     * @param input 输入参数。
     * @param sessionId 当前会话标识。
     */
    public CliMode(Kind kind, String input, String sessionId) {
        this.kind = kind == null ? Kind.SERVER : kind;
        this.input = input;
        this.sessionId = sessionId;
    }

    /**
     * 读取Kind。
     *
     * @return 返回读取到的Kind。
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * 读取输入。
     *
     * @return 返回读取到的输入。
     */
    public String getInput() {
        return input;
    }

    /**
     * 读取会话标识。
     *
     * @return 返回读取到的会话标识。
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 判断是否Console模式。
     *
     * @return 如果Console模式满足条件则返回 true，否则返回 false。
     */
    public boolean isConsoleMode() {
        return kind == Kind.CLI || kind == Kind.TUI || kind == Kind.COMPLETION;
    }

    /**
     * 判断当前启动模式是否需要开启 HTTP/WebSocket 网络监听。
     *
     * @return 仅服务端模式返回 true，终端命令模式返回 false。
     */
    public boolean shouldStartNetworkListeners() {
        return !isConsoleMode();
    }
}
