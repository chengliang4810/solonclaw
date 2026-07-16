package com.jimuqu.solon.claw.gateway.service;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.solon.core.AppContext;

/** 持有一个命名 Profile 的独立 Bean 图、渠道、数据库和运行资源。 */
public final class ProfileRuntimeBundle implements AutoCloseable {
    /** Profile 名。 */
    private final String profile;

    /** Profile 工作区。 */
    private final Path home;

    /** Profile 局部环境快照。 */
    private final Map<String, String> environment;

    /** Profile 独立配置。 */
    private final AppConfig appConfig;

    /** Profile 独立 Bean 容器。 */
    private final AppContext appContext;

    /** Profile 独立网关服务。 */
    private final DefaultGatewayService gatewayService;

    /** 防止重复关闭。 */
    private volatile boolean closed;

    /** 创建已经完成装配的 Profile 运行时。 */
    ProfileRuntimeBundle(
            String profile,
            Path home,
            Map<String, String> environment,
            AppConfig appConfig,
            AppContext appContext,
            DefaultGatewayService gatewayService) {
        this.profile = profile;
        this.home = home.toAbsolutePath().normalize();
        this.environment = new LinkedHashMap<String, String>(environment);
        this.appConfig = appConfig;
        this.appContext = appContext;
        this.gatewayService = gatewayService;
    }

    /**
     * @return Profile 名。
     */
    public String profile() {
        return profile;
    }

    /**
     * @return Profile 工作区。
     */
    public Path home() {
        return home;
    }

    /**
     * @return Profile 独立配置。
     */
    public AppConfig appConfig() {
        return appConfig;
    }

    /**
     * @return Profile 独立 Bean 容器，供定向诊断和测试读取。
     */
    public AppContext appContext() {
        return appContext;
    }

    /** 停止当前 Profile 中指定来源的 Agent 运行。 */
    public AgentRunStopResult stopRun(String sourceKey) {
        AgentRunControlService controlService = appContext.getBean(AgentRunControlService.class);
        if (controlService == null) {
            return AgentRunStopResult.none();
        }
        GatewayMessage message = new GatewayMessage();
        message.setProfile(profile);
        message.setSourceKeyOverride(sourceKey);
        return controlService.stop(message.sourceKey());
    }

    /**
     * 在完整 Profile 作用域内处理消息。
     *
     * @param message 已由路由层选择到当前 Profile 的消息。
     * @return Agent 或命令处理结果。
     * @throws Exception 处理链失败。
     */
    public GatewayReply handle(GatewayMessage message) throws Exception {
        if (closed) {
            throw new IllegalStateException("Profile runtime is already closed: " + profile);
        }
        message.setProfile(profile);
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(profile, home, environment, appContext)) {
            return gatewayService.handle(message);
        }
    }

    /** 停止 Profile Bean 生命周期；不触碰主 SolonApp 或 JVM 全局执行器。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(profile, home, environment, appContext)) {
            try {
                appContext.preStop();
            } finally {
                appContext.stop();
            }
        }
    }
}
