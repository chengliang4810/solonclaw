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
    @Bean
    public AppConfig appConfig() {
        return AppConfig.load(Solon.cfg());
    }

    @Bean(destroyMethod = "persistLifecycleShutdownRecord")
    public ShutdownForensicsService shutdownForensicsService(AppConfig appConfig) {
        return new ShutdownForensicsService(appConfig);
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public RuntimeMemoryMonitorService runtimeMemoryMonitorService() {
        return new RuntimeMemoryMonitorService();
    }
}
