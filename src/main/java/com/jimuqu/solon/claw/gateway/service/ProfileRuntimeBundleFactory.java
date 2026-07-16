package com.jimuqu.solon.claw.gateway.service;

import com.jimuqu.solon.claw.bootstrap.ContextConfiguration;
import com.jimuqu.solon.claw.bootstrap.GatewayConfiguration;
import com.jimuqu.solon.claw.bootstrap.PricingConfiguration;
import com.jimuqu.solon.claw.bootstrap.ProfileRuntimeSupportConfiguration;
import com.jimuqu.solon.claw.bootstrap.ProviderConfiguration;
import com.jimuqu.solon.claw.bootstrap.SchedulerConfiguration;
import com.jimuqu.solon.claw.bootstrap.StorageConfiguration;
import com.jimuqu.solon.claw.bootstrap.ToolConfiguration;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.profile.ProfileChildRuntimeMarker;
import com.jimuqu.solon.claw.support.RuntimeMemoryMonitorService;
import com.jimuqu.solon.claw.support.ShutdownForensicsService;
import java.nio.file.Path;
import java.util.Map;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Props;

/** 用显式配置类创建 Profile 子运行时，不扫描 Controller、HTTP 端点或主启动类。 */
public class ProfileRuntimeBundleFactory {
    /** 主应用 Bean 容器，仅复制 Solon 已注册的注解构建器和注入器。 */
    private final AppContext rootContext;

    /** 创建显式 Profile 运行时工厂。 */
    public ProfileRuntimeBundleFactory(AppContext rootContext) {
        if (rootContext == null) {
            throw new IllegalArgumentException("Root AppContext is required.");
        }
        this.rootContext = rootContext;
    }

    /**
     * 创建一个完全独立的命名 Profile 运行时。
     *
     * <p>子容器只装配存储、上下文、内置提供方、工具、调度和网关运行图；不扫描 Controller，不启动 HTTP，不注册 JVM shutdown hook，也不替换
     * Solon.context()。
     */
    public ProfileRuntimeBundle create(
            String profile, Path home, Map<String, String> environment, AppConfig appConfig) {
        appConfig.getGateway().setMultiplexProfiles(false);
        Props props = new Props();
        props.loadAddIfAbsent("app.yml");
        props.put("solonclaw.workspace", home.toAbsolutePath().normalize().toString());
        props.put("solonclaw.profile.name", profile);
        props.put("solon.staticfiles.enable", "false");

        AppContext child = new AppContext(null, rootContext.getClassLoader(), props);
        try (com.jimuqu.solon.claw.profile.ProfileRuntimeScope.Scope ignored =
                com.jimuqu.solon.claw.profile.ProfileRuntimeScope.open(
                        profile, home, environment, child)) {
            rootContext.copyTo(child);
            child.wrapAndPut(AppContext.class, child);
            child.wrapAndPut(AppConfig.class, appConfig);
            child.wrapAndPut(ProfileChildRuntimeMarker.class, new ProfileChildRuntimeMarker());
            child.wrapAndPut(
                    ShutdownForensicsService.class, new ShutdownForensicsService(appConfig));
            child.wrapAndPut(RuntimeMemoryMonitorService.class, new RuntimeMemoryMonitorService());
            try {
                registerRuntimeConfigurations(child);
                child.start();
                DefaultGatewayService gatewayService = child.getBean(DefaultGatewayService.class);
                if (gatewayService == null) {
                    throw new IllegalStateException(
                            "Profile runtime did not create a gateway service: " + profile);
                }
                return new ProfileRuntimeBundle(
                        profile, home, environment, appConfig, child, gatewayService);
            } catch (Throwable e) {
                try {
                    child.preStop();
                } catch (Throwable cleanupFailure) {
                    // 失败路径继续尝试释放已经创建的 Bean。
                }
                try {
                    child.stop();
                } catch (Throwable cleanupFailure) {
                    // 保留原始启动异常。
                }
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new IllegalStateException("Could not start Profile runtime: " + profile, e);
            }
        }
    }

    /** 按依赖边界显式登记运行配置，不触发全包扫描。 */
    private void registerRuntimeConfigurations(AppContext child) {
        child.beanMake(StorageConfiguration.class);
        child.beanMake(ContextConfiguration.class);
        child.beanMake(PricingConfiguration.class);
        child.beanMake(ProviderConfiguration.class);
        child.beanMake(ToolConfiguration.class);
        child.beanMake(ProfileRuntimeSupportConfiguration.class);
        child.beanMake(GatewayConfiguration.class);
        child.beanMake(SchedulerConfiguration.class);
    }
}
