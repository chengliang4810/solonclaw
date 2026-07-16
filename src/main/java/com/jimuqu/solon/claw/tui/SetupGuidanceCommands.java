package com.jimuqu.solon.claw.tui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 承载本地终端只输出说明、不启动服务或外部向导的顶层管理命令常量。 */
public final class SetupGuidanceCommands {
    /** 只提供本地配置说明、不启动服务或外部向导的当前管理命令。 */
    public static final List<String> COMMANDS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "postinstall",
                            "login",
                            "auth",
                            "fallback",
                            "secrets",
                            "proxy",
                            "mcp",
                            "send",
                            "hooks",
                            "dump",
                            "backup",
                            "checkpoints",
                            "import",
                            "bundles",
                            "memory",
                            "dashboard",
                            "logs",
                            "prompt-size"));

    /** 创建本地管理命令常量实例。 */
    private SetupGuidanceCommands() {}
}
