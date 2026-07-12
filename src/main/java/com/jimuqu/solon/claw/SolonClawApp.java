package com.jimuqu.solon.claw;

import com.jimuqu.solon.claw.bootstrap.StartupModeContext;
import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliModeParser;
import com.jimuqu.solon.claw.cli.CliRunner;
import com.jimuqu.solon.claw.cli.TerminalModelPicker;
import com.jimuqu.solon.claw.cli.TerminalSetupCommands;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeStatusService;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.profile.ProfileBootstrap;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import com.jimuqu.solon.claw.support.LlmProviderService;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;
import org.noear.solon.core.Props;

/** 应用启动入口。 */
@SolonMain
public class SolonClawApp {
    /** 记录Solon项目应用中的启动参数。 */
    private static volatile String[] startupArgs = new String[0];

    /** 当前服务端进程的 Profile 网关状态写入器。 */
    private static volatile GatewayRuntimeStatusService gatewayRuntimeStatusService;

    /** 防止嵌入测试重复注册 JVM 关闭钩子。 */
    private static volatile boolean gatewayShutdownHookRegistered;

    /**
     * 启动 Solon 应用。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        ProfileBootstrap.Result profileResult =
                ProfileBootstrap.prepare(args, System.in, System.out, System.err);
        if (profileResult.isHandled()) {
            if (profileResult.getExitCode() != 0) {
                System.exit(profileResult.getExitCode());
            }
            return;
        }
        final String[] effectiveArgs = profileResult.getArguments();
        startupArgs = effectiveArgs.clone();
        final CliMode cliMode = CliModeParser.parse(startupArgs);
        StartupModeContext.set(cliMode);
        configureConsoleLogging(cliMode);
        if (cliMode.isConsoleMode()) {
            runConsoleMode(effectiveArgs, cliMode);
            return;
        }
        Solon.start(
                SolonClawApp.class,
                effectiveArgs,
                app -> {
                    app.enableWebSocket(cliMode.shouldStartNetworkListeners());
                    if (cliMode.isConsoleMode()) {
                        app.enableHttp(false);
                    }
                });
        registerGatewayRuntimeStatus();
    }

    /** 在统一异常边界内完成本地命令与一次性对话，避免向终端用户暴露 Java 堆栈。 */
    private static void runConsoleMode(String[] effectiveArgs, CliMode cliMode) {
        int exitCode = 0;
        try {
            if (runLocalSetupCommand(cliMode)) {
                return;
            }
            Solon.start(
                    SolonClawApp.class,
                    effectiveArgs,
                    app -> {
                        app.enableWebSocket(false);
                        app.enableHttp(false);
                    });
            exitCode = Solon.context().getBean(CliRunner.class).run(cliMode);
        } catch (Throwable e) {
            System.err.println("运行失败：" + ErrorTextSupport.safeError(e));
            exitCode = 1;
        } finally {
            Solon.stopBlock(false, 0);
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** 在服务端完成监听后写入 PID/状态，并在 JVM 退出时只清理本进程拥有的记录。 */
    private static synchronized void registerGatewayRuntimeStatus() {
        AppConfig appConfig = Solon.context().getBean(AppConfig.class);
        GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(appConfig);
        if (appConfig.getGateway().isMultiplexProfiles()
                && "default".equals(System.getProperty("solonclaw.profile.name", "default"))) {
            ProfileMultiplexRuntimeManager manager =
                    Solon.context().getBean(ProfileMultiplexRuntimeManager.class);
            if (manager == null) {
                throw new IllegalStateException(
                        "Profile multiplex runtime manager was not initialized.");
            }
            manager.bindRuntimeStatusService(service);
        }
        service.writePidFile();
        service.writeState("running", "Profile gateway is ready.");
        gatewayRuntimeStatusService = service;
        if (gatewayShutdownHookRegistered) {
            return;
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                new Runnable() {
                                    /** 在 JVM 关闭阶段更新状态并释放 PID 文件。 */
                                    @Override
                                    public void run() {
                                        GatewayRuntimeStatusService current =
                                                gatewayRuntimeStatusService;
                                        if (current != null) {
                                            current.writeState(
                                                    "stopped", "Profile gateway process exited.");
                                            current.removePidFile();
                                        }
                                    }
                                },
                                "solonclaw-gateway-status-shutdown"));
        gatewayShutdownHookRegistered = true;
    }

    /**
     * 在 Solon 容器启动前处理一次性 setup/config/model 命令，避免为打印本地配置说明初始化 Agent/Gateway 全量组件。
     *
     * @param cliMode 已解析的启动模式。
     * @return 已处理并可直接退出时返回 true。
     */
    static boolean runLocalSetupCommand(CliMode cliMode) {
        if (cliMode == null
                || !cliMode.isConsoleMode()
                || cliMode.getKind() == CliMode.Kind.COMPLETION) {
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
                new TerminalSetupCommands(
                        appConfig, new TerminalModelPicker(appConfig, providerService));
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
        configureUtf8ConsoleStreams();
        if (System.getProperty("solon.logging.appender.console.level") == null) {
            System.setProperty("solon.logging.appender.console.level", "OFF");
        }
    }

    /** 终端模式固定使用 UTF-8 输出，避免直接 java -jar 在非 UTF-8 默认编码下把中文写成问号。 */
    private static void configureUtf8ConsoleStreams() {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
    }
}
