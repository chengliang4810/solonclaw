package com.jimuqu.solon.claw;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliModeParser;
import com.jimuqu.solon.claw.cli.CliRunner;
import com.jimuqu.solon.claw.cli.TerminalModelPicker;
import com.jimuqu.solon.claw.cli.TerminalSetupCommands;
import com.jimuqu.solon.claw.bootstrap.StartupModeContext;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.LlmProviderService;
import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;
import org.noear.solon.core.Props;

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
        final CliMode cliMode = CliModeParser.parse(startupArgs);
        StartupModeContext.set(cliMode);
        configureConsoleLogging(cliMode);
        if (runLocalSetupCommand(cliMode)) {
            return;
        }
        Solon.start(
                SolonClawApp.class,
                args,
                app -> {
                    app.enableWebSocket(cliMode.shouldStartNetworkListeners());
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

    /**
     * 在 Solon 容器启动前处理一次性 setup/config/model 命令，避免为打印本地配置说明初始化 Agent/Gateway 全量组件。
     *
     * @param cliMode 已解析的启动模式。
     * @return 已处理并可直接退出时返回 true。
     */
    static boolean runLocalSetupCommand(CliMode cliMode) {
        if (cliMode == null || !cliMode.isConsoleMode() || cliMode.getKind() == CliMode.Kind.COMPLETION) {
            return false;
        }
        Props props = new Props();
        props.loadAddIfAbsent("app.yml");
        String workspace = System.getProperty("solonclaw.workspace");
        if (workspace != null && !workspace.trim().isEmpty()) {
            props.put("solonclaw.workspace", workspace.trim());
        }
        AppConfig appConfig = AppConfig.load(props);
        LlmProviderService providerService = new LlmProviderService(appConfig);
        TerminalSetupCommands setupCommands =
                new TerminalSetupCommands(appConfig, new TerminalModelPicker(appConfig, providerService));
        if (!setupCommands.isSetupCommand(cliMode.getInput())) {
            return false;
        }
        System.out.println(setupCommands.render(cliMode.getInput()));
        return true;
    }

    /**
     * 读取应用启动参数快照。
     *
     * @return 返回启动参数参数结果。
     */
    public static String[] startupArgs() {
        return startupArgs == null ? new String[0] : startupArgs.clone();
    }

    /**
     * 为一次性终端命令关闭框架控制台日志，避免 setup/config/model 输出被启动日志打断。
     *
     * @param cliMode 已解析的启动模式。
     */
    static void configureConsoleLogging(CliMode cliMode) {
        if (cliMode == null || !cliMode.isConsoleMode()) {
            return;
        }
        if (System.getProperty("solon.logging.appender.console.level") == null) {
            System.setProperty("solon.logging.appender.console.level", "OFF");
        }
    }
}
