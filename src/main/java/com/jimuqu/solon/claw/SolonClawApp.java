package com.jimuqu.solon.claw;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeStatusService;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

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
        final String[] effectiveArgs = args == null ? new String[0] : args.clone();
        startupArgs = effectiveArgs.clone();
        Solon.start(SolonClawApp.class, effectiveArgs, app -> app.enableWebSocket(true));
        registerGatewayRuntimeStatus();
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
     * 读取应用启动参数快照。
     *
     * @return 返回启动参数参数结果。
     */
    public static String[] startupArgs() {
        return startupArgs == null ? new String[0] : startupArgs.clone();
    }
}
