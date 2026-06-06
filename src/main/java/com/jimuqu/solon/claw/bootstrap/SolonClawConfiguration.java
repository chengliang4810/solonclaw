package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.RuntimeMemoryMonitorService;
import com.jimuqu.solon.claw.support.ShutdownForensicsService;
import org.noear.solon.Solon;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;

/** 根配置类，负责装配应用级配置对象。 */
@Configuration
public class SolonClawConfiguration {
    /**
     * 执行应用配置相关逻辑。
     *
     * @return 返回app配置。
     */
    @Bean
    public AppConfig appConfig() {
        return AppConfig.load(Solon.cfg());
    }

    /**
     * 关闭Forensics服务。
     *
     * @param appConfig 应用运行配置。
     * @return 返回shutdown Forensics服务结果。
     */
    @Bean(destroyMethod = "persistLifecycleShutdownRecord")
    public ShutdownForensicsService shutdownForensicsService(AppConfig appConfig) {
        return new ShutdownForensicsService(appConfig);
    }

    /**
     * 执行运行时记忆Monitor服务相关逻辑。
     *
     * @return 返回运行时记忆Monitor服务结果。
     */
    @Bean(destroyMethod = "shutdown")
    public RuntimeMemoryMonitorService runtimeMemoryMonitorService() {
        RuntimeMemoryMonitorService service = new RuntimeMemoryMonitorService();
        if (StartupModeContext.shouldStartServerLifecycle()) {
            service.start();
        }
        return service;
    }
}
