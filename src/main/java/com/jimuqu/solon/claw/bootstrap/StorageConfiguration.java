package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.agent.AgentProfileRepository;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteAgentProfileRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteAgentRunRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteApprovalAuditRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteChannelStateRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteCronJobRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteGatewayPolicyRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteGlobalSettingRepository;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.storage.repository.SqliteProactiveRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteUsageEventRepository;
import com.jimuqu.solon.claw.support.DefaultCheckpointService;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;

/** 承载Storage配置并集中创建运行组件。 */
@Configuration
public class StorageConfiguration {
    /**
     * 执行SQLite数据库相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回SQLite数据库结果。
     */
    @Bean(destroyMethod = "shutdown")
    public SqliteDatabase sqliteDatabase(AppConfig appConfig) throws Exception {
        return new SqliteDatabase(appConfig);
    }

    /**
     * 执行SQLitePreferenceStore相关逻辑。
     *
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回SQLite Preference Store结果。
     */
    @Bean
    public SqlitePreferenceStore sqlitePreferenceStore(SqliteDatabase sqliteDatabase) {
        return new SqlitePreferenceStore(sqliteDatabase);
    }

    /**
     * 执行会话仓储相关逻辑。
     *
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回会话仓储结果。
     */
    @Bean
    public SessionRepository sessionRepository(SqliteDatabase sqliteDatabase) {
        return new SqliteSessionRepository(sqliteDatabase);
    }

    /**
     * 执行定时任务任务仓储相关逻辑。
     *
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回定时任务任务仓储结果。
     */
    @Bean
    public CronJobRepository cronJobRepository(SqliteDatabase sqliteDatabase) {
        return new SqliteCronJobRepository(sqliteDatabase);
    }

    /**
     * 执行Agent运行仓储相关逻辑。
     *
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回Agent运行仓储结果。
     */
    @Bean
    public AgentRunRepository agentRunRepository(SqliteDatabase sqliteDatabase) {
        return new SqliteAgentRunRepository(sqliteDatabase);
    }

    /**
     * 执行用量事件仓储相关逻辑。
     *
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回用量事件仓储结果。
     */
    @Bean
    public UsageEventRepository usageEventRepository(SqliteDatabase sqliteDatabase) {
        return new SqliteUsageEventRepository(sqliteDatabase);
    }

    /**
     * 执行审批审计仓储相关逻辑。
     *
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回审批审计仓储结果。
     */
    @Bean
    public ApprovalAuditRepository approvalAuditRepository(SqliteDatabase sqliteDatabase) {
        return new SqliteApprovalAuditRepository(sqliteDatabase);
    }

    /**
     * 执行渠道状态仓储相关逻辑。
     *
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回渠道状态仓储结果。
     */
    @Bean
    public ChannelStateRepository channelStateRepository(SqliteDatabase sqliteDatabase) {
        return new SqliteChannelStateRepository(sqliteDatabase);
    }

    /**
     * 执行global设置仓储相关逻辑。
     *
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回global设置仓储结果。
     */
    @Bean
    public GlobalSettingRepository globalSettingRepository(SqliteDatabase sqliteDatabase) {
        return new SqliteGlobalSettingRepository(sqliteDatabase);
    }

    /**
     * 执行Agent角色配置仓储相关逻辑。
     *
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回Agent角色配置仓储结果。
     */
    @Bean
    public AgentProfileRepository agentProfileRepository(SqliteDatabase sqliteDatabase) {
        return new SqliteAgentProfileRepository(sqliteDatabase);
    }

    /**
     * 执行消息网关策略仓储相关逻辑。
     *
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回消息网关策略仓储结果。
     */
    @Bean
    public GatewayPolicyRepository gatewayPolicyRepository(SqliteDatabase sqliteDatabase) {
        return new SqliteGatewayPolicyRepository(sqliteDatabase);
    }

    /**
     * 执行主动协作仓储相关逻辑。
     *
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回主动协作仓储结果。
     */
    @Bean
    public ProactiveRepository proactiveRepository(SqliteDatabase sqliteDatabase) {
        return new SqliteProactiveRepository(sqliteDatabase);
    }

    /**
     * 执行检查点服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回检查点服务结果。
     */
    @Bean
    public CheckpointService checkpointService(AppConfig appConfig, SqliteDatabase sqliteDatabase) {
        return new DefaultCheckpointService(appConfig, sqliteDatabase);
    }
}
