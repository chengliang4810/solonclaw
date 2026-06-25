package com.jimuqu.solon.claw;

import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

/** 应用启动入口。 */
@SolonMain
public class SolonClawApp {
    /** 记录Solon项目应用中的启动参数。 */
    private static volatile String[] startupArgs = new String[0];

    /**
     * 启动 Solon 应用。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        startupArgs = args == null ? new String[0] : args.clone();
        Solon.start(SolonClawApp.class, args);
    }

    /**
     * 读取应用启动参数快照。
     *
     * @return 返回启动参数参数结果。
     */
    public static String[] startupArgs() {
        return startupArgs == null ? new String[0] : startupArgs.clone();
    }
}
