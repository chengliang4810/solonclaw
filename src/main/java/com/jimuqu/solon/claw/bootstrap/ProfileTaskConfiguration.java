package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.repository.ProfileTaskRepository;
import com.jimuqu.solon.claw.gateway.service.DefaultGatewayService;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.profile.ProfileChildRuntimeMarker;
import com.jimuqu.solon.claw.profile.task.ProfileTaskCoordinator;
import com.jimuqu.solon.claw.profile.task.ProfileTaskSubmissionBridge;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteProfileTaskRepository;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Condition;
import org.noear.solon.annotation.Configuration;

/** 只在 root/default 容器装配跨 Profile 协作任务控制面。 */
@Configuration
@Condition(onMissingBean = ProfileChildRuntimeMarker.class)
public class ProfileTaskConfiguration {
    /** 创建共享任务仓储；命名 Profile 子容器不得持有自己的任务控制面。 */
    @Bean
    public ProfileTaskRepository profileTaskRepository(SqliteDatabase sqliteDatabase) {
        ProfileTaskRepository repository = new SqliteProfileTaskRepository(sqliteDatabase);
        ProfileTaskSubmissionBridge.install(repository);
        return repository;
    }

    /** 创建并启动 root 唯一调度器。 */
    @Bean(destroyMethod = "close")
    public ProfileTaskCoordinator profileTaskCoordinator(
            ProfileTaskRepository repository,
            ProfileMultiplexRuntimeManager runtimeManager,
            DefaultGatewayService defaultGatewayService,
            AppConfig appConfig)
            throws Exception {
        ProfileTaskCoordinator coordinator =
                new ProfileTaskCoordinator(
                        repository, runtimeManager, defaultGatewayService, appConfig);
        ProfileTaskSubmissionBridge.installCoordinator(coordinator);
        coordinator.start();
        return coordinator;
    }
}
