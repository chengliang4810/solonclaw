package com.jimuqu.solon.claw.bootstrap;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.agent.AgentProfileRepository;
import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.agent.AgentRuntimeService;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.AsyncSkillLearningService;
import com.jimuqu.solon.claw.context.BuiltinMemoryProvider;
import com.jimuqu.solon.claw.context.DefaultMemoryManager;
import com.jimuqu.solon.claw.context.FileContextService;
import com.jimuqu.solon.claw.context.FileMemoryService;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.context.SkillCuratorService;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.ContextBudgetService;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.core.service.MemoryManager;
import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import com.jimuqu.solon.claw.core.service.SkillGuardService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.core.service.SkillImportService;
import com.jimuqu.solon.claw.core.service.SkillLearningService;
import com.jimuqu.solon.claw.engine.DefaultContextBudgetService;
import com.jimuqu.solon.claw.engine.DefaultContextCompressionService;
import com.jimuqu.solon.claw.engine.DefaultSessionSearchService;
import com.jimuqu.solon.claw.skillhub.service.DefaultSkillGuardService;
import com.jimuqu.solon.claw.skillhub.service.DefaultSkillHubService;
import com.jimuqu.solon.claw.skillhub.service.DefaultSkillImportService;
import com.jimuqu.solon.claw.skillhub.source.GitHubSkillSource;
import com.jimuqu.solon.claw.skillhub.support.DefaultSkillHubHttpClient;
import com.jimuqu.solon.claw.skillhub.support.GitHubAuth;
import com.jimuqu.solon.claw.skillhub.support.SkillHubHttpClient;
import com.jimuqu.solon.claw.skillhub.support.SkillHubStateStore;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;

/** 承载上下文配置并集中创建运行组件。 */
@Configuration
public class ContextConfiguration {
    /**
     * 执行本地技能服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param skillImportService 技能Import服务依赖。
     * @param skillHubStateStore 技能Hub状态Store参数。
     * @return 返回本地技能服务结果。
     */
    @Bean
    public LocalSkillService localSkillService(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SkillImportService skillImportService,
            SkillHubStateStore skillHubStateStore) {
        return new LocalSkillService(
                appConfig, preferenceStore, skillImportService, skillHubStateStore);
    }

    /**
     * 执行persona工作区服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回persona工作区服务结果。
     */
    @Bean
    public PersonaWorkspaceService personaWorkspaceService(AppConfig appConfig) {
        return new PersonaWorkspaceService(appConfig);
    }

    /**
     * 执行技能技能维护服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param localSkillService 本地技能服务依赖。
     * @return 返回技能技能维护服务结果。
     */
    @Bean
    public SkillCuratorService skillCuratorService(
            AppConfig appConfig, LocalSkillService localSkillService) {
        return new SkillCuratorService(appConfig, localSkillService);
    }

    /**
     * 执行Agent运行时服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param agentProfileRepository 文件或目录路径参数。
     * @return 返回Agent运行时服务结果。
     */
    @Bean
    public AgentRuntimeService agentRuntimeService(
            AppConfig appConfig, AgentProfileRepository agentProfileRepository) {
        return new AgentRuntimeService(appConfig, agentProfileRepository);
    }

    /**
     * 执行Agent角色配置服务相关逻辑。
     *
     * @param agentProfileRepository 文件或目录路径参数。
     * @param agentRuntimeService Agent运行时服务依赖。
     * @return 返回Agent角色配置服务结果。
     */
    @Bean
    public AgentProfileService agentProfileService(
            AgentProfileRepository agentProfileRepository,
            AgentRuntimeService agentRuntimeService) {
        return new AgentProfileService(agentProfileRepository, agentRuntimeService);
    }

    /**
     * 执行文件上下文服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param localSkillService 本地技能服务依赖。
     * @param memoryManager 记忆Manager参数。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param personaWorkspaceService persona工作区服务依赖。
     * @return 返回文件上下文服务结果。
     */
    @Bean
    public FileContextService fileContextService(
            AppConfig appConfig,
            LocalSkillService localSkillService,
            MemoryManager memoryManager,
            GlobalSettingRepository globalSettingRepository,
            PersonaWorkspaceService personaWorkspaceService) {
        return new FileContextService(
                appConfig,
                localSkillService,
                memoryManager,
                globalSettingRepository,
                personaWorkspaceService);
    }

    /**
     * 执行记忆服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回记忆服务结果。
     */
    @Bean
    public MemoryService memoryService(AppConfig appConfig) {
        return new FileMemoryService(appConfig);
    }

    /**
     * 执行技能中心状态Store相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回技能中心状态Store结果。
     */
    @Bean
    public SkillHubStateStore skillHubStateStore(AppConfig appConfig) {
        return new SkillHubStateStore(FileUtil.file(appConfig.getRuntime().getSkillsDir()));
    }

    /**
     * 执行技能中心HTTPClient相关逻辑。
     *
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回技能中心HTTP Client结果。
     */
    @Bean
    public SkillHubHttpClient skillHubHttpClient(SecurityPolicyService securityPolicyService) {
        return new DefaultSkillHubHttpClient(securityPolicyService);
    }

    /**
     * 执行git中心认证相关逻辑。
     *
     * @param skillHubHttpClient 技能HubHTTPClient参数。
     * @return 返回git中心认证结果。
     */
    @Bean
    public GitHubAuth gitHubAuth(SkillHubHttpClient skillHubHttpClient) {
        return new GitHubAuth(skillHubHttpClient);
    }

    /**
     * 执行技能保护服务相关逻辑。
     *
     * @return 返回技能保护服务结果。
     */
    @Bean
    public SkillGuardService skillGuardService() {
        return new DefaultSkillGuardService();
    }

    /**
     * 执行技能导入服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param skillGuardService 技能防护服务依赖。
     * @param skillHubStateStore 技能Hub状态Store参数。
     * @return 返回技能导入服务结果。
     */
    @Bean
    public SkillImportService skillImportService(
            AppConfig appConfig,
            SkillGuardService skillGuardService,
            SkillHubStateStore skillHubStateStore) {
        return new DefaultSkillImportService(
                FileUtil.file(appConfig.getRuntime().getSkillsDir()),
                skillGuardService,
                skillHubStateStore);
    }

    /**
     * 执行git中心技能来源相关逻辑。
     *
     * @param gitHubAuth gitHub鉴权参数。
     * @param skillHubHttpClient 技能HubHTTPClient参数。
     * @param skillHubStateStore 技能Hub状态Store参数。
     * @return 返回git中心技能来源结果。
     */
    @Bean
    public GitHubSkillSource gitHubSkillSource(
            GitHubAuth gitHubAuth,
            SkillHubHttpClient skillHubHttpClient,
            SkillHubStateStore skillHubStateStore) {
        return new GitHubSkillSource(gitHubAuth, skillHubHttpClient, skillHubStateStore);
    }

    /**
     * 执行技能中心服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param skillImportService 技能Import服务依赖。
     * @param skillGuardService 技能防护服务依赖。
     * @param skillHubStateStore 技能Hub状态Store参数。
     * @param skillHubHttpClient 技能HubHTTPClient参数。
     * @param gitHubAuth gitHub鉴权参数。
     * @param gitHubSkillSource gitHub技能来源参数。
     * @return 返回技能中心服务结果。
     */
    @Bean
    public SkillHubService skillHubService(
            AppConfig appConfig,
            SkillImportService skillImportService,
            SkillGuardService skillGuardService,
            SkillHubStateStore skillHubStateStore,
            SkillHubHttpClient skillHubHttpClient,
            GitHubAuth gitHubAuth,
            GitHubSkillSource gitHubSkillSource) {
        return new DefaultSkillHubService(
                new File(System.getProperty("user.dir")),
                FileUtil.file(appConfig.getRuntime().getSkillsDir()),
                skillImportService,
                skillGuardService,
                skillHubStateStore,
                skillHubHttpClient,
                gitHubAuth,
                gitHubSkillSource);
    }

    /**
     * 执行builtin记忆提供方相关逻辑。
     *
     * @param memoryService 记忆服务依赖。
     * @return 返回builtin记忆提供方结果。
     */
    @Bean
    public MemoryProvider builtinMemoryProvider(MemoryService memoryService) {
        return new BuiltinMemoryProvider(memoryService);
    }

    /**
     * 执行记忆管理器相关逻辑。
     *
     * @param builtinMemoryProvider builtin记忆提供方标识或键值。
     * @param pluginMemoryProviders 插件记忆Providers标识或键值。
     * @return 返回记忆管理器结果。
     */
    @Bean
    public MemoryManager memoryManager(
            MemoryProvider builtinMemoryProvider,
            java.util.List<MemoryProvider> pluginMemoryProviders) {
        java.util.List<MemoryProvider> providers = new java.util.ArrayList<MemoryProvider>();
        providers.add(builtinMemoryProvider);
        if (pluginMemoryProviders != null) {
            providers.addAll(pluginMemoryProviders);
        }
        return new DefaultMemoryManager(providers);
    }

    /**
     * 执行上下文压缩服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回上下文压缩服务结果。
     */
    @Bean
    public ContextCompressionService contextCompressionService(AppConfig appConfig) {
        return new DefaultContextCompressionService(appConfig);
    }

    /**
     * 执行上下文预算服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回上下文Budget服务结果。
     */
    @Bean
    public ContextBudgetService contextBudgetService(AppConfig appConfig) {
        return new DefaultContextBudgetService(appConfig);
    }

    /**
     * 执行会话搜索服务相关逻辑。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param llmGateway LLM网关参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param appConfig 当前逻辑运行时配置。
     * @return 返回会话搜索服务结果。
     */
    @Bean
    public SessionSearchService sessionSearchService(
            SessionRepository sessionRepository,
            LlmGateway llmGateway,
            AgentRunRepository agentRunRepository,
            AppConfig appConfig) {
        return new DefaultSessionSearchService(
                sessionRepository, llmGateway, agentRunRepository, appConfig);
    }

    /**
     * 执行技能Learning服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param memoryService 记忆服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param llmGateway LLM网关参数。
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回技能Learning服务结果。
     */
    @Bean(destroyMethod = "shutdown")
    public SkillLearningService skillLearningService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            MemoryService memoryService,
            LocalSkillService localSkillService,
            CheckpointService checkpointService,
            LlmGateway llmGateway,
            SqliteDatabase sqliteDatabase) {
        return new AsyncSkillLearningService(
                appConfig,
                sessionRepository,
                memoryService,
                localSkillService,
                checkpointService,
                llmGateway,
                sqliteDatabase);
    }
}
