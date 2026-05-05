package com.jimuqu.solon.claw;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliModeParser;
import com.jimuqu.solon.claw.cli.CliRunner;
import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

/** 应用启动入口。 */
@SolonMain
public class SolonClawApp {
    private static volatile String[] startupArgs = new String[0];

    /**
     * 启动 Solon 应用。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        startupArgs = args == null ? new String[0] : args.clone();
        final CliMode cliMode = CliModeParser.parse(startupArgs);
        Solon.start(
                SolonClawApp.class,
                args,
                app -> {
                    if (cliMode.isConsoleMode()) {
                        app.enableHttp(false);
                    }
                });
        if (cliMode.isConsoleMode()) {
            int exitCode = 0;
            try {
                exitCode = Solon.context().getBean(CliRunner.class).run(cliMode);
            } catch (Throwable e) {
                e.printStackTrace(System.err);
                exitCode = 1;
            } finally {
                Solon.stopBlock(false, 0);
            }
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        }
    }

    public static String[] startupArgs() {
        return startupArgs == null ? new String[0] : startupArgs.clone();
    }
}
