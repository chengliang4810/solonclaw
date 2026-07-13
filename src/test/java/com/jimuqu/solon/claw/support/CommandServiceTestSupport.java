package com.jimuqu.solon.claw.support;

import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.service.ContextService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.gateway.command.DefaultCommandService;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;

/** 为 slash command 单测创建可覆盖少量依赖的命令服务。 */
public final class CommandServiceTestSupport {
    private CommandServiceTestSupport() {}

    /** 创建覆盖技能中心服务的命令服务，用于验证 /skills 路由。 */
    public static DefaultCommandService commandServiceWithSkillHub(
            TestEnvironment env, SkillHubService skillHubService) {
        return commandService(
                env,
                skillHubService,
                new AppUpdateService(env.appConfig, new AppVersionService(env.appConfig)));
    }

    /** 创建覆盖应用更新服务的命令服务，用于验证 /version 与 /update 路由。 */
    public static DefaultCommandService commandServiceWithUpdate(
            TestEnvironment env, AppUpdateService appUpdateService) {
        return commandService(env, env.skillHubService, appUpdateService);
    }

    /** 使用测试环境的默认依赖组装命令服务，仅替换当前测试关注的依赖。 */
    private static DefaultCommandService commandService(
            TestEnvironment env,
            SkillHubService skillHubService,
            AppUpdateService appUpdateService) {
        return new DefaultCommandService(
                env.sessionRepository,
                env.toolRegistry,
                env.localSkillService,
                env.cronJobRepository,
                noopOrchestrator(),
                emptyContextService(),
                env.contextCompressionService,
                env.deliveryService,
                env.gatewayAuthorizationService,
                env.checkpointService,
                skillHubService,
                env.appConfig,
                env.globalSettingRepository,
                env.processRegistry,
                env.runtimeSettingsService,
                new DisplaySettingsService(env.appConfig, env.globalSettingRepository),
                appUpdateService,
                env.dangerousCommandApprovalService,
                env.agentRunControlService,
                env.agentProfileService,
                env.agentRunRepository,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                env.memoryService);
    }

    /** 构造不会触发真实 Agent 主循环的对话编排器。 */
    private static ConversationOrchestrator noopOrchestrator() {
        return new ConversationOrchestrator() {
            @Override
            public GatewayReply handleIncoming(GatewayMessage message) {
                return GatewayReply.ok("noop");
            }

            @Override
            public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
                return GatewayReply.ok("noop");
            }

            @Override
            public GatewayReply resumePending(String sourceKey) {
                return GatewayReply.ok("noop");
            }
        };
    }

    /** 构造空系统提示词上下文，避免命令测试读取工作区文件。 */
    private static ContextService emptyContextService() {
        return new ContextService() {
            @Override
            public String buildSystemPrompt(String sourceKey) {
                return "";
            }
        };
    }
}
