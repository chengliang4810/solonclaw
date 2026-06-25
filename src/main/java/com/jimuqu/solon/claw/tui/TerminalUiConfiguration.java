package com.jimuqu.solon.claw.tui;

import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.support.AttachmentPathResolver;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.tool.runtime.BrowserRuntimeService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardSkillsService;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.net.websocket.WebSocketRouter;

/** 终端 UI WebSocket 协议注册配置。 */
@Configuration
public class TerminalUiConfiguration {
    /** 注册终端 UI WebSocket 监听器到固定协议路径。 */
    @Bean
    public TerminalUiWebSocketListener terminalUiWebSocketListener(
            CliRuntime runtime,
            AppConfig appConfig,
            SessionRepository sessionRepository,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DashboardSkillsService dashboardSkillsService,
            SqlitePreferenceStore preferenceStore,
            BrowserRuntimeService browserRuntimeService,
            ContextCompressionService contextCompressionService,
            AttachmentCacheService attachmentCacheService,
            McpRuntimeService mcpRuntimeService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            DelegationService delegationService,
            AgentRunControlService agentRunControlService,
            AgentRunRepository agentRunRepository,
            RuntimeSettingsService runtimeSettingsService,
            GlobalSettingRepository globalSettingRepository,
            DangerousCommandApprovalService approvalService) {
        TerminalUiWebSocketListener listener =
                new TerminalUiWebSocketListener(
                        runtime,
                        appConfig,
                        sessionRepository,
                        securityPolicyService,
                        processRegistry,
                        approvalService,
                        localSkillService,
                        skillHubService,
                        checkpointService,
                        dashboardSkillsService,
                        preferenceStore,
                        browserRuntimeService,
                        contextCompressionService,
                        new AttachmentPathResolver(attachmentCacheService, securityPolicyService),
                        mcpRuntimeService,
                        gatewayRuntimeRefreshService,
                        delegationService,
                        agentRunControlService,
                        agentRunRepository,
                        runtimeSettingsService,
                        globalSettingRepository);
        WebSocketRouter.getInstance().of(TerminalUiHandshakeService.WS_PATH, listener);
        return listener;
    }
}
