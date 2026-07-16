package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.gateway.command.SlashConfirmService;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.proactive.ProactiveDiagnosticsService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.RuntimeMemoryMonitorService;
import com.jimuqu.solon.claw.support.ShutdownForensicsService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;

/**
 * DashboardDiagnosticsService 的构建器，用于替代伸缩构造器模式。 通过链式调用设置依赖，最后调用 build() 创建实例。
 *
 * @since 1.0.0
 */
public class DashboardDiagnosticsServiceBuilder {

    /** 应用配置。 */
    private AppConfig appConfig;

    /** 投递服务。 */
    private DeliveryService deliveryService;

    /** LLM提供方服务。 */
    private LlmProviderService llmProviderService;

    /** 工具注册表。 */
    private ToolRegistry toolRegistry;

    /** 会话仓储。 */
    private SessionRepository sessionRepository;

    /** 会话编排器。 */
    private ConversationOrchestrator conversationOrchestrator;

    /** 审批审计仓储。 */
    private ApprovalAuditRepository approvalAuditRepository;

    /** 斜杠命令确认服务。 */
    private SlashConfirmService slashConfirmService;

    /** 命令服务。 */
    private CommandService commandService;

    /** 审批服务。 */
    private DangerousCommandApprovalService approvalService;

    /** 安全策略服务。 */
    private SecurityPolicyService securityPolicyService;

    /** Tirith安全服务。 */
    private TirithSecurityService tirithSecurityService;

    /** 工具结果存储服务。 */
    private ToolResultStorageService toolResultStorageService;

    /** 关闭取证服务。 */
    private ShutdownForensicsService shutdownForensicsService;

    /** 运行时内存监控服务。 */
    private RuntimeMemoryMonitorService runtimeMemoryMonitorService;

    /** Agent运行仓储。 */
    private AgentRunRepository agentRunRepository;

    /** 进程注册表。 */
    private ProcessRegistry processRegistry;

    /** 网关运行时刷新服务。 */
    private GatewayRuntimeRefreshService gatewayRuntimeRefreshService;

    /** 主动诊断服务。 */
    private ProactiveDiagnosticsService proactiveDiagnosticsService;

    /**
     * 创建新的构建器实例。
     *
     * @return 返回新的构建器实例。
     */
    public static DashboardDiagnosticsServiceBuilder builder() {
        return new DashboardDiagnosticsServiceBuilder();
    }

    /** 设置应用配置。 */
    public DashboardDiagnosticsServiceBuilder appConfig(AppConfig v) {
        this.appConfig = v;
        return this;
    }

    /** 设置投递服务。 */
    public DashboardDiagnosticsServiceBuilder deliveryService(DeliveryService v) {
        this.deliveryService = v;
        return this;
    }

    /** 设置LLM提供方服务。 */
    public DashboardDiagnosticsServiceBuilder llmProviderService(LlmProviderService v) {
        this.llmProviderService = v;
        return this;
    }

    /** 设置工具注册表。 */
    public DashboardDiagnosticsServiceBuilder toolRegistry(ToolRegistry v) {
        this.toolRegistry = v;
        return this;
    }

    /** 设置会话仓储。 */
    public DashboardDiagnosticsServiceBuilder sessionRepository(SessionRepository v) {
        this.sessionRepository = v;
        return this;
    }

    /** 设置会话编排器。 */
    public DashboardDiagnosticsServiceBuilder conversationOrchestrator(ConversationOrchestrator v) {
        this.conversationOrchestrator = v;
        return this;
    }

    /** 设置审批审计仓储。 */
    public DashboardDiagnosticsServiceBuilder approvalAuditRepository(ApprovalAuditRepository v) {
        this.approvalAuditRepository = v;
        return this;
    }

    /** 设置斜杠命令确认服务。 */
    public DashboardDiagnosticsServiceBuilder slashConfirmService(SlashConfirmService v) {
        this.slashConfirmService = v;
        return this;
    }

    /** 设置命令服务。 */
    public DashboardDiagnosticsServiceBuilder commandService(CommandService v) {
        this.commandService = v;
        return this;
    }

    /** 设置审批服务。 */
    public DashboardDiagnosticsServiceBuilder approvalService(DangerousCommandApprovalService v) {
        this.approvalService = v;
        return this;
    }

    /** 设置安全策略服务。 */
    public DashboardDiagnosticsServiceBuilder securityPolicyService(SecurityPolicyService v) {
        this.securityPolicyService = v;
        return this;
    }

    /** 设置Tirith安全服务。 */
    public DashboardDiagnosticsServiceBuilder tirithSecurityService(TirithSecurityService v) {
        this.tirithSecurityService = v;
        return this;
    }

    /** 设置工具结果存储服务（可选）。 */
    public DashboardDiagnosticsServiceBuilder toolResultStorageService(ToolResultStorageService v) {
        this.toolResultStorageService = v;
        return this;
    }

    /** 设置关闭取证服务（可选）。 */
    public DashboardDiagnosticsServiceBuilder shutdownForensicsService(ShutdownForensicsService v) {
        this.shutdownForensicsService = v;
        return this;
    }

    /** 设置运行时内存监控服务（可选）。 */
    public DashboardDiagnosticsServiceBuilder runtimeMemoryMonitorService(
            RuntimeMemoryMonitorService v) {
        this.runtimeMemoryMonitorService = v;
        return this;
    }

    /** 设置Agent运行仓储（可选）。 */
    public DashboardDiagnosticsServiceBuilder agentRunRepository(AgentRunRepository v) {
        this.agentRunRepository = v;
        return this;
    }

    /** 设置进程注册表（可选）。 */
    public DashboardDiagnosticsServiceBuilder processRegistry(ProcessRegistry v) {
        this.processRegistry = v;
        return this;
    }

    /** 设置网关运行时刷新服务（可选）。 */
    public DashboardDiagnosticsServiceBuilder gatewayRuntimeRefreshService(
            GatewayRuntimeRefreshService v) {
        this.gatewayRuntimeRefreshService = v;
        return this;
    }

    /** 设置主动诊断服务（可选）。 */
    public DashboardDiagnosticsServiceBuilder proactiveDiagnosticsService(
            ProactiveDiagnosticsService v) {
        this.proactiveDiagnosticsService = v;
        return this;
    }

    /**
     * 构建 DashboardDiagnosticsService 实例。
     *
     * @return 返回构建的实例。
     */
    public DashboardDiagnosticsService build() {
        return new DashboardDiagnosticsService(
                appConfig,
                deliveryService,
                llmProviderService,
                toolRegistry,
                sessionRepository,
                conversationOrchestrator,
                approvalAuditRepository,
                slashConfirmService,
                commandService,
                approvalService,
                securityPolicyService,
                tirithSecurityService,
                toolResultStorageService,
                shutdownForensicsService,
                runtimeMemoryMonitorService,
                agentRunRepository,
                processRegistry,
                gatewayRuntimeRefreshService,
                proactiveDiagnosticsService);
    }
}
