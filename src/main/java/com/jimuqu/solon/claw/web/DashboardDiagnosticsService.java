package com.jimuqu.solon.claw.web;

import static com.jimuqu.solon.claw.web.DashboardDiagnosticTextFormatter.diagnosticFailureSummary;
import static com.jimuqu.solon.claw.web.DashboardDiagnosticTextFormatter.redactedApprovalKey;
import static com.jimuqu.solon.claw.web.DashboardDiagnosticTextFormatter.redactedCommandPathTarget;
import static com.jimuqu.solon.claw.web.DashboardDiagnosticTextFormatter.redactedIdentifier;
import static com.jimuqu.solon.claw.web.DashboardDiagnosticTextFormatter.redactedJsonList;
import static com.jimuqu.solon.claw.web.DashboardDiagnosticTextFormatter.redactedTextList;
import static com.jimuqu.solon.claw.web.DashboardDiagnosticTextFormatter.safeAuditPreview;
import static com.jimuqu.solon.claw.web.DashboardDiagnosticTextFormatter.safeObjectText;
import static com.jimuqu.solon.claw.web.DashboardDiagnosticTextFormatter.safePathProbeTarget;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.jimuqu.solon.claw.cli.CliAttachmentResolver;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.SkillCredentialFileService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ApprovalAuditEvent;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.ModelMetadata;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.gateway.command.SlashConfirmService;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.proactive.ProactiveDiagnosticsService;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.ModelMetadataService;
import com.jimuqu.solon.claw.support.RuntimeMemoryMonitorService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.ShutdownForensicsService;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.ProcessTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityAuditTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SolonClawCodeExecutionSkills;
import com.jimuqu.solon.claw.tool.runtime.SolonClawPatchTools;
import com.jimuqu.solon.claw.tool.runtime.SolonClawShellSkill;
import com.jimuqu.solon.claw.tool.runtime.SolonClawToolSchemaSanitizer;
import com.jimuqu.solon.claw.tool.runtime.SubprocessEnvironmentSanitizer;
import com.jimuqu.solon.claw.tool.runtime.TerminalAnsiSanitizer;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dashboard 统一诊断服务。 */
public class DashboardDiagnosticsService {
    /** 记录诊断 fallback 中的非敏感异常摘要，避免吞没问题影响排查。 */
    private static final Logger log = LoggerFactory.getLogger(DashboardDiagnosticsService.class);

    /** 注入应用配置，用于控制台诊断。 */
    private final AppConfig appConfig;

    /** 注入投递服务，用于调用对应业务能力。 */
    private final DeliveryService deliveryService;

    /** 注入大模型提供方服务，用于调用对应业务能力。 */
    private final LlmProviderService llmProviderService;

    /** 记录控制台诊断中的工具注册表。 */
    private final ToolRegistry toolRegistry;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 记录控制台诊断中的对话编排器。 */
    private final ConversationOrchestrator conversationOrchestrator;

    /** 保存审批审计仓储依赖，用于访问持久化数据。 */
    private final ApprovalAuditRepository approvalAuditRepository;

    /** 注入斜杠命令Confirm服务，用于调用对应业务能力。 */
    private final SlashConfirmService slashConfirmService;

    /** 注入命令服务，用于调用对应业务能力。 */
    private final CommandService commandService;

    /** 注入审批服务，用于调用对应业务能力。 */
    private final DangerousCommandApprovalService approvalService;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 注入tirith安全服务，用于调用对应业务能力。 */
    private final TirithSecurityService tirithSecurityService;

    /** 注入工具结果Storage服务，用于调用对应业务能力。 */
    private final ToolResultStorageService toolResultStorageService;

    /** 组装运行时基础诊断，隔离 runtime、进程和可恢复任务输出契约。 */
    private final DashboardRuntimeDiagnosticsAssembler runtimeDiagnosticsAssembler;

    /** 注入主动协作诊断服务，用于回答主动联系为什么没有触发。 */
    private final ProactiveDiagnosticsService proactiveDiagnosticsService;

    /**
     * 创建控制台诊断服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolRegistry 工具注册表依赖组件。
     * @param sessionRepository 会话仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param approvalAuditRepository 审批Audit仓储依赖。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param commandService 命令服务依赖。
     * @param approvalService 审批服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     */
    public DashboardDiagnosticsService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            ToolRegistry toolRegistry,
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator,
            ApprovalAuditRepository approvalAuditRepository,
            SlashConfirmService slashConfirmService,
            CommandService commandService,
            DangerousCommandApprovalService approvalService,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService) {
        this(
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
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * 创建控制台诊断服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolRegistry 工具注册表依赖组件。
     * @param sessionRepository 会话仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param approvalAuditRepository 审批Audit仓储依赖。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param commandService 命令服务依赖。
     * @param approvalService 审批服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     * @param toolResultStorageService 工具结果StorageService响应或执行结果。
     */
    public DashboardDiagnosticsService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            ToolRegistry toolRegistry,
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator,
            ApprovalAuditRepository approvalAuditRepository,
            SlashConfirmService slashConfirmService,
            CommandService commandService,
            DangerousCommandApprovalService approvalService,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService,
            ToolResultStorageService toolResultStorageService) {
        this(
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
                null,
                null,
                null);
    }

    /**
     * 创建控制台诊断服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolRegistry 工具注册表依赖组件。
     * @param sessionRepository 会话仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param approvalAuditRepository 审批Audit仓储依赖。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param commandService 命令服务依赖。
     * @param approvalService 审批服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     * @param toolResultStorageService 工具结果StorageService响应或执行结果。
     * @param shutdownForensicsService 关闭Forensics服务依赖。
     */
    public DashboardDiagnosticsService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            ToolRegistry toolRegistry,
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator,
            ApprovalAuditRepository approvalAuditRepository,
            SlashConfirmService slashConfirmService,
            CommandService commandService,
            DangerousCommandApprovalService approvalService,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService,
            ToolResultStorageService toolResultStorageService,
            ShutdownForensicsService shutdownForensicsService) {
        this(
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
                null,
                null);
    }

    /**
     * 创建控制台诊断服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolRegistry 工具注册表依赖组件。
     * @param sessionRepository 会话仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param approvalAuditRepository 审批Audit仓储依赖。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param commandService 命令服务依赖。
     * @param approvalService 审批服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     * @param toolResultStorageService 工具结果StorageService响应或执行结果。
     * @param shutdownForensicsService 关闭Forensics服务依赖。
     * @param runtimeMemoryMonitorService 运行时记忆Monitor服务依赖。
     */
    public DashboardDiagnosticsService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            ToolRegistry toolRegistry,
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator,
            ApprovalAuditRepository approvalAuditRepository,
            SlashConfirmService slashConfirmService,
            CommandService commandService,
            DangerousCommandApprovalService approvalService,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService,
            ToolResultStorageService toolResultStorageService,
            ShutdownForensicsService shutdownForensicsService,
            RuntimeMemoryMonitorService runtimeMemoryMonitorService) {
        this(
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
                null);
    }

    /**
     * 创建控制台诊断服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolRegistry 工具注册表依赖组件。
     * @param sessionRepository 会话仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param approvalAuditRepository 审批Audit仓储依赖。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param commandService 命令服务依赖。
     * @param approvalService 审批服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     * @param toolResultStorageService 工具结果StorageService响应或执行结果。
     * @param shutdownForensicsService 关闭Forensics服务依赖。
     * @param runtimeMemoryMonitorService 运行时记忆Monitor服务依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     */
    public DashboardDiagnosticsService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            ToolRegistry toolRegistry,
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator,
            ApprovalAuditRepository approvalAuditRepository,
            SlashConfirmService slashConfirmService,
            CommandService commandService,
            DangerousCommandApprovalService approvalService,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService,
            ToolResultStorageService toolResultStorageService,
            ShutdownForensicsService shutdownForensicsService,
            RuntimeMemoryMonitorService runtimeMemoryMonitorService,
            AgentRunRepository agentRunRepository) {
        this(
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
                null);
    }

    /**
     * 创建控制台诊断服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolRegistry 工具注册表依赖组件。
     * @param sessionRepository 会话仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param approvalAuditRepository 审批Audit仓储依赖。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param commandService 命令服务依赖。
     * @param approvalService 审批服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     * @param toolResultStorageService 工具结果StorageService响应或执行结果。
     * @param shutdownForensicsService 关闭Forensics服务依赖。
     * @param runtimeMemoryMonitorService 运行时记忆Monitor服务依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     */
    public DashboardDiagnosticsService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            ToolRegistry toolRegistry,
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator,
            ApprovalAuditRepository approvalAuditRepository,
            SlashConfirmService slashConfirmService,
            CommandService commandService,
            DangerousCommandApprovalService approvalService,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService,
            ToolResultStorageService toolResultStorageService,
            ShutdownForensicsService shutdownForensicsService,
            RuntimeMemoryMonitorService runtimeMemoryMonitorService,
            AgentRunRepository agentRunRepository,
            ProcessRegistry processRegistry) {
        this(
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
                null);
    }

    /**
     * 创建控制台诊断服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolRegistry 工具注册表依赖组件。
     * @param sessionRepository 会话仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param approvalAuditRepository 审批Audit仓储依赖。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param commandService 命令服务依赖。
     * @param approvalService 审批服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     * @param toolResultStorageService 工具结果StorageService响应或执行结果。
     * @param shutdownForensicsService 关闭Forensics服务依赖。
     * @param runtimeMemoryMonitorService 运行时记忆Monitor服务依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     */
    public DashboardDiagnosticsService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            ToolRegistry toolRegistry,
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator,
            ApprovalAuditRepository approvalAuditRepository,
            SlashConfirmService slashConfirmService,
            CommandService commandService,
            DangerousCommandApprovalService approvalService,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService,
            ToolResultStorageService toolResultStorageService,
            ShutdownForensicsService shutdownForensicsService,
            RuntimeMemoryMonitorService runtimeMemoryMonitorService,
            AgentRunRepository agentRunRepository,
            ProcessRegistry processRegistry,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService) {
        this(
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
                null);
    }

    /**
     * 创建控制台诊断服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolRegistry 工具注册表依赖组件。
     * @param sessionRepository 会话仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param approvalAuditRepository 审批Audit仓储依赖。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param commandService 命令服务依赖。
     * @param approvalService 审批服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     * @param toolResultStorageService 工具结果StorageService响应或执行结果。
     * @param shutdownForensicsService 关闭Forensics服务依赖。
     * @param runtimeMemoryMonitorService 运行时记忆Monitor服务依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param proactiveDiagnosticsService 主动协作诊断服务依赖。
     */
    public DashboardDiagnosticsService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            ToolRegistry toolRegistry,
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator,
            ApprovalAuditRepository approvalAuditRepository,
            SlashConfirmService slashConfirmService,
            CommandService commandService,
            DangerousCommandApprovalService approvalService,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService,
            ToolResultStorageService toolResultStorageService,
            ShutdownForensicsService shutdownForensicsService,
            RuntimeMemoryMonitorService runtimeMemoryMonitorService,
            AgentRunRepository agentRunRepository,
            ProcessRegistry processRegistry,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            ProactiveDiagnosticsService proactiveDiagnosticsService) {
        this.appConfig = appConfig;
        this.deliveryService = deliveryService;
        this.llmProviderService = llmProviderService;
        this.toolRegistry = toolRegistry;
        this.sessionRepository = sessionRepository;
        this.conversationOrchestrator = conversationOrchestrator;
        this.approvalAuditRepository = approvalAuditRepository;
        this.slashConfirmService = slashConfirmService;
        this.commandService = commandService;
        this.approvalService = approvalService;
        this.securityPolicyService = securityPolicyService;
        this.tirithSecurityService = tirithSecurityService;
        this.toolResultStorageService = toolResultStorageService;
        this.proactiveDiagnosticsService = proactiveDiagnosticsService;
        this.runtimeDiagnosticsAssembler =
                new DashboardRuntimeDiagnosticsAssembler(
                        appConfig,
                        shutdownForensicsService,
                        runtimeMemoryMonitorService,
                        agentRunRepository,
                        processRegistry,
                        gatewayRuntimeRefreshService);
    }

    /**
     * 执行诊断相关逻辑。
     *
     * @return 返回诊断结果。
     */
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("runtime", runtime());
        result.put("providers", providers());
        result.put("channels", channels());
        result.put("stream_health", streamHealth());
        result.put("tools", tools());
        result.put("mcp", mcp());
        result.put("security", security());
        result.put("runs", runs());
        if (proactiveDiagnosticsService != null) {
            result.put("proactive", proactiveDiagnosticsService.diagnostics());
        }
        return result;
    }

    /**
     * 执行安全审计相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回安全审计结果。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> securityAudit(Map<String, Object> body) {
        Map<String, Object> input = body == null ? Collections.<String, Object>emptyMap() : body;
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        securityPolicyService,
                        approvalService,
                        tirithSecurityService,
                        toolResultStorageService,
                        appConfig);
        String result =
                tools.audit(
                        text(input, "action"),
                        text(input, "toolName"),
                        text(input, "command"),
                        text(input, "url"),
                        text(input, "path"),
                        bool(input, "writeLike"),
                        text(input, "argsJson"));
        Object data = ONode.ofJson(result).toData();
        if (data instanceof Map) {
            return safeSecurityAuditResult((Map<String, Object>) data);
        }
        Map<String, Object> fallback = new LinkedHashMap<String, Object>();
        fallback.put("success", Boolean.FALSE);
        fallback.put("decision", "error");
        fallback.put("summary", "security audit result was not a JSON object");
        return fallback;
    }

    /**
     * 执行子进程EnvironmentProbe相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回子进程Environment Probe结果。
     */
    public Map<String, Object> subprocessEnvironmentProbe(Map<String, Object> body) {
        Map<String, Object> input = body == null ? Collections.<String, Object>emptyMap() : body;
        List<String> requestedNames = envProbeNames(input.get("names"));
        List<Map<String, Object>> decisions =
                SubprocessEnvironmentSanitizer.probeDecisions(
                        envProbeInput(requestedNames), appConfig, true);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", Boolean.TRUE);
        result.put("surface", "subprocess_environment");
        result.put("summary", subprocessEnvironmentProbeSummary(requestedNames, decisions));
        result.put("requested_count", Integer.valueOf(requestedNames.size()));
        result.put("requested_names", redactedTextList(requestedNames, 120));
        result.put("decision_categories", SubprocessEnvironmentSanitizer.decisionCategories());
        result.put("decisions", decisions);
        result.put("policy", safeSubprocessEnvironmentPolicySummary());
        return result;
    }

    /**
     * 执行子进程EnvironmentProbe摘要相关逻辑。
     *
     * @param requestedNames requestedNames请求载荷。
     * @param decisions decisions 参数。
     * @return 返回子进程Environment Probe Summary结果。
     */
    private String subprocessEnvironmentProbeSummary(
            List<String> requestedNames, List<Map<String, Object>> decisions) {
        if (requestedNames == null || requestedNames.isEmpty()) {
            return "未提供环境变量名。";
        }
        int allowCount = 0;
        int blockedCount = 0;
        int redactedCount = 0;
        int hiddenCount = 0;
        int forceCount = 0;
        for (Map<String, Object> decision : decisions) {
            String decisionValue = text(decision, "decision");
            String visibilityValue = text(decision, "visibility");
            if ("force".equals(decisionValue)) {
                forceCount++;
            }
            if ("allow".equals(decisionValue) || "force".equals(decisionValue)) {
                allowCount++;
            } else {
                blockedCount++;
            }
            if ("redacted".equals(visibilityValue)) {
                redactedCount++;
            } else if ("hidden".equals(visibilityValue)) {
                hiddenCount++;
            }
        }
        return "已返回子进程环境变量 probe 决策："
                + "allow="
                + allowCount
                + " blocked="
                + blockedCount
                + " force="
                + forceCount
                + " redacted="
                + redactedCount
                + " hidden="
                + hiddenCount
                + "。";
    }

    /**
     * 执行待恢复Approvals相关逻辑。
     *
     * @param limit 最大返回数量。
     * @return 返回pending Approvals结果。
     */
    public Map<String, Object> pendingApprovals(int limit) throws Exception {
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 300));
        int sessionScanLimit = Math.max(effectiveLimit, Math.min(effectiveLimit * 5, 300));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (sessionRepository == null || approvalService == null) {
            Map<String, Object> disabled = new LinkedHashMap<String, Object>();
            disabled.put("count", Integer.valueOf(0));
            disabled.put("items", items);
            disabled.put("session_scan_limit", Integer.valueOf(sessionScanLimit));
            disabled.put("scanned_sessions", Integer.valueOf(0));
            disabled.put("truncated", Boolean.FALSE);
            disabled.put("session_scan_truncated", Boolean.FALSE);
            disabled.put("available", Boolean.FALSE);
            disabled.put("code", "approval_unavailable");
            disabled.put("message", "审批服务尚未启用。");
            return disabled;
        }

        int scannedSessions = 0;
        boolean truncated = false;
        for (SessionRecord session : sessionRepository.listRecent(sessionScanLimit)) {
            scannedSessions++;
            List<DangerousCommandApprovalService.PendingApproval> pending =
                    approvalService.listPendingApprovals(session);
            for (DangerousCommandApprovalService.PendingApproval approval : pending) {
                if (items.size() >= effectiveLimit) {
                    truncated = true;
                    break;
                }
                items.add(pendingApprovalItem(session, approval));
            }
            if (truncated) {
                break;
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(items.size()));
        result.put("items", items);
        result.put("session_scan_limit", Integer.valueOf(sessionScanLimit));
        result.put("scanned_sessions", Integer.valueOf(scannedSessions));
        result.put("truncated", Boolean.valueOf(truncated));
        result.put(
                "session_scan_truncated",
                Boolean.valueOf(
                        !truncated
                                && scannedSessions >= sessionScanLimit
                                && sessionRepository.countAll() > scannedSessions));
        return result;
    }

    /**
     * 解析审批。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回解析后的审批。
     */
    public Map<String, Object> resolveApproval(Map<String, Object> body) throws Exception {
        Map<String, Object> input = body == null ? Collections.<String, Object>emptyMap() : body;
        String sessionId = text(input, "sessionId");
        String selector =
                StrUtil.blankToDefault(text(input, "approvalId"), text(input, "selector"));
        String action = StrUtil.nullToEmpty(text(input, "action")).trim().toLowerCase();
        boolean resume = !Boolean.FALSE.equals(bool(input, "resume"));
        String approver = StrUtil.blankToDefault(text(input, "approver"), "dashboard");
        DangerousCommandApprovalService.ApprovalScope scope =
                parseApprovalScope(text(input, "scope"));

        if (sessionRepository == null || approvalService == null) {
            return resolveResult(false, "approval_unavailable", "审批服务尚未启用。", null);
        }
        if (StrUtil.isBlank(sessionId)) {
            return resolveResult(false, "missing_session", "缺少会话 ID。", null);
        }
        if (!"approve".equals(action) && !"deny".equals(action)) {
            return resolveResult(false, "invalid_action", "审批动作必须是 approve 或 deny。", null);
        }

        SessionRecord session = sessionRepository.findById(sessionId);
        if (session == null) {
            return resolveResult(false, "session_not_found", "会话不存在或已删除。", null);
        }

        SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
        boolean changed;
        if ("approve".equals(action)) {
            changed = approvalService.approve(agentSession, selector, scope, approver);
        } else {
            changed = approvalService.reject(agentSession, selector, approver);
        }
        if (!changed) {
            return resolveResult(false, "approval_not_found", "待审批项不存在或已过期。", null);
        }

        GatewayReply reply = null;
        if (resume
                && StrUtil.isNotBlank(session.getSourceKey())
                && conversationOrchestrator != null) {
            reply = conversationOrchestrator.resumePending(session.getSourceKey());
        }

        Map<String, Object> result =
                resolveResult(true, "ok", "审批状态已更新。", reply == null ? null : replyMap(reply));
        result.put("action", action);
        result.put("session_id", safeAuditPreview(session.getSessionId(), 240));
        result.put("resumed", Boolean.valueOf(reply != null));
        return result;
    }

    /**
     * 执行审批历史相关逻辑。
     *
     * @param limit 最大返回数量。
     * @return 返回审批历史结果。
     */
    public Map<String, Object> approvalHistory(int limit) throws Exception {
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 300));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (approvalAuditRepository == null) {
            return disabledList(items, "approval_history_unavailable", "审批历史服务尚未启用。");
        }
        boolean truncated = false;
        for (ApprovalAuditEvent event : approvalAuditRepository.listRecent(effectiveLimit + 1)) {
            if (items.size() >= effectiveLimit) {
                truncated = true;
                break;
            }
            items.add(approvalAuditItem(event));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(items.size()));
        result.put("items", items);
        result.put("truncated", Boolean.valueOf(truncated));
        return result;
    }

    /**
     * 执行alwaysApprovals相关逻辑。
     *
     * @param limit 最大返回数量。
     * @return 返回always Approvals结果。
     */
    public Map<String, Object> alwaysApprovals(int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 300));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (approvalService == null) {
            return disabledList(items, "approval_unavailable", "审批服务尚未启用。");
        }
        boolean truncated = false;
        for (String approval : approvalService.listAlwaysApprovals()) {
            if (items.size() >= effectiveLimit) {
                truncated = true;
                break;
            }
            items.add(alwaysApprovalItem(approval));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(items.size()));
        result.put("items", items);
        result.put("truncated", Boolean.valueOf(truncated));
        return result;
    }

    /**
     * 执行revokeAlways审批相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回revoke Always审批结果。
     */
    public Map<String, Object> revokeAlwaysApproval(Map<String, Object> body) throws Exception {
        Map<String, Object> input = body == null ? Collections.<String, Object>emptyMap() : body;
        String approval =
                resolveAlwaysApproval(
                        StrUtil.blankToDefault(
                                text(input, "approvalId"), text(input, "approval_id")));
        String approver = StrUtil.blankToDefault(text(input, "approver"), "dashboard");
        if (approvalService == null) {
            return resolveResult(false, "approval_unavailable", "审批服务尚未启用。", null);
        }
        if (StrUtil.isBlank(approval)) {
            return resolveResult(false, "missing_approval", "缺少长期授权项。", null);
        }
        boolean changed = approvalService != null && approvalService.revokeAlwaysApproval(approval);
        if (!changed) {
            return resolveResult(false, "approval_not_found", "长期授权项不存在或已撤销。", null);
        }
        appendAlwaysApprovalRevokedAudit(approval, approver);
        return resolveResult(true, "ok", "长期授权已撤销。", null);
    }

    /**
     * 执行待恢复斜杠命令Confirms相关逻辑。
     *
     * @param limit 最大返回数量。
     * @return 返回pending Slash Confirms结果。
     */
    public Map<String, Object> pendingSlashConfirms(int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 300));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (slashConfirmService == null) {
            return disabledList(items, "slash_confirm_unavailable", "Slash 确认服务尚未启用。");
        }
        boolean truncated = false;
        for (SlashConfirmService.PendingConfirm pending : slashConfirmService.listPending()) {
            if (items.size() >= effectiveLimit) {
                truncated = true;
                break;
            }
            items.add(slashConfirmItem(pending));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(items.size()));
        result.put("items", items);
        result.put("truncated", Boolean.valueOf(truncated));
        return result;
    }

    /**
     * 解析Slash Confirm。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回解析后的Slash Confirm。
     */
    public Map<String, Object> resolveSlashConfirm(Map<String, Object> body) throws Exception {
        Map<String, Object> input = body == null ? Collections.<String, Object>emptyMap() : body;
        String sourceKey = text(input, "sourceKey");
        String confirmId = text(input, "confirmId");
        String action = StrUtil.nullToEmpty(text(input, "action")).trim().toLowerCase();
        if (StrUtil.isBlank(confirmId)) {
            return resolveResult(false, "missing_confirm_id", "缺少确认编号。", null);
        }
        if (!"approve".equals(action) && !"deny".equals(action) && !"always".equals(action)) {
            return resolveResult(false, "invalid_action", "确认动作必须是 approve、always 或 deny。", null);
        }
        if (slashConfirmService == null || commandService == null) {
            return resolveResult(false, "slash_confirm_unavailable", "Slash 确认服务尚未启用。", null);
        }
        SlashConfirmService.PendingConfirm pending = findPendingSlashConfirm(confirmId, sourceKey);
        if (pending == null) {
            return resolveResult(false, "confirm_not_found", "待确认 slash 命令不存在或已过期。", null);
        }
        if ("always".equals(action) && !pending.isAllowAlways()) {
            return resolveResult(false, "always_not_allowed", "该 Slash 命令不允许永久确认。", null);
        }

        sourceKey = pending.getSourceKey();
        String commandLine = slashConfirmCommandLine(action, pending.getConfirmId());
        GatewayReply reply =
                commandService.handle(dashboardMessage(sourceKey, commandLine), commandLine);
        Map<String, Object> result =
                resolveResult(
                        !reply.isError(),
                        reply.isError() ? "error" : "ok",
                        reply.getContent(),
                        replyMap(reply));
        result.put("action", action);
        result.put("confirm_id", safeAuditPreview(pending.getConfirmId(), 160));
        result.put("confirm_ref", shortId(pending.getConfirmId()));
        return result;
    }

    /**
     * 执行运行时相关逻辑。
     *
     * @return 返回运行时结果。
     */
    private Map<String, Object> runtime() {
        return runtimeDiagnosticsAssembler.runtime();
    }

    /**
     * 执行异步任务主体。
     *
     * @return 返回运行结果。
     */
    private Map<String, Object> runs() {
        return runtimeDiagnosticsAssembler.runs();
    }

    /**
     * 执行providers相关逻辑。
     *
     * @return 返回providers结果。
     */
    private List<Map<String, Object>> providers() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, AppConfig.ProviderConfig> entry :
                llmProviderService.providers().entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("provider", safeAuditPreview(entry.getKey(), 160));
            item.put("name", safeAuditPreview(entry.getValue().getName(), 200));
            item.put("dialect", safeAuditPreview(entry.getValue().getDialect(), 80));
            item.put("base_url", SecretRedactor.maskUrl(entry.getValue().getBaseUrl()));
            item.put("default_model", safeAuditPreview(entry.getValue().getDefaultModel(), 200));
            item.put("has_api_key", StrUtil.isNotBlank(entry.getValue().getApiKey()));
            items.add(item);
        }
        return items;
    }

    /**
     * 执行channels相关逻辑。
     *
     * @return 返回channels结果。
     */
    private List<Map<String, Object>> channels() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ChannelStatus status : deliveryService.statuses()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put(
                    "platform",
                    status.getPlatform() == null
                            ? null
                            : status.getPlatform().name().toLowerCase());
            item.put("enabled", status.isEnabled());
            item.put("connected", status.isConnected());
            item.put("setup_state", status.getSetupState());
            item.put("connection_mode", status.getConnectionMode());
            item.put(
                    "last_error_message",
                    SecretRedactor.redact(status.getLastErrorMessage(), 1000));
            items.add(item);
        }
        return items;
    }

    /**
     * 执行流健康检查相关逻辑。
     *
     * @return 返回stream健康检查结果。
     */
    private Map<String, Object> streamHealth() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        AppConfig.ProviderConfig provider =
                appConfig.getProviders().get(appConfig.getModel().getProviderKey());
        ModelMetadata metadata =
                new ModelMetadataService(appConfig)
                        .resolve(appConfig.getModel().getProviderKey(), provider);
        List<ChannelStatus> statuses =
                deliveryService == null
                        ? Collections.<ChannelStatus>emptyList()
                        : deliveryService.statuses();
        int enabledChannels = 0;
        int connectedChannels = 0;
        int reconnectingChannels = 0;
        boolean hasStreamTransport = false;
        boolean hasStreamFailure = false;
        for (ChannelStatus status : statuses) {
            if (status == null || !status.isEnabled()) {
                continue;
            }
            enabledChannels++;
            if (status.isConnected()) {
                connectedChannels++;
            }
            if (status.isReconnecting()) {
                reconnectingChannels++;
            }
            if ("stream".equalsIgnoreCase(status.getConnectionMode())) {
                hasStreamTransport = true;
                if (!status.isConnected()) {
                    hasStreamFailure = true;
                }
            }
        }
        boolean configured = appConfig.getLlm().isStream();
        boolean providerSupportsStreaming = metadata.isSupportsStreaming();
        String state = "disabled";
        if (configured) {
            if (!providerSupportsStreaming) {
                state = "unsupported";
            } else if (hasStreamFailure) {
                state = reconnectingChannels > 0 ? "degraded" : "disconnected";
            } else if (!hasStreamTransport) {
                state = "healthy";
            } else if (connectedChannels > 0) {
                state = "healthy";
            } else {
                state = "starting";
            }
        }
        summary.put("configured", Boolean.valueOf(configured));
        summary.put("provider_supports_streaming", Boolean.valueOf(providerSupportsStreaming));
        summary.put("provider", safeAuditPreview(metadata.getProvider(), 160));
        summary.put("model", safeAuditPreview(metadata.getModel(), 200));
        summary.put("dialect", safeAuditPreview(metadata.getDialect(), 80));
        summary.put("gateway_stream_transport", Boolean.valueOf(hasStreamTransport));
        summary.put("enabled_channels", Integer.valueOf(enabledChannels));
        summary.put("connected_channels", Integer.valueOf(connectedChannels));
        summary.put("reconnecting_channels", Integer.valueOf(reconnectingChannels));
        summary.put("state", state);
        return summary;
    }

    /**
     * 执行工具相关逻辑。
     *
     * @return 返回工具结果。
     */
    private Map<String, Object> tools() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("count", toolRegistry.listToolNames().size());
        map.put("names", toolRegistry.listToolNames());
        map.put("policies", toolPolicies());
        map.put("attachment_policies", attachmentPolicies());
        return map;
    }

    /**
     * 执行工具Policies相关逻辑。
     *
     * @return 返回工具Policies结果。
     */
    private Map<String, Object> toolPolicies() {
        Map<String, Object> policies = new LinkedHashMap<String, Object>();
        policies.put("schema_sanitizer", safeSchemaSanitizerPolicySummary());
        policies.put("patch_parser", safePatchParserPolicySummary());
        policies.put("code_execution", safeCodeExecutionPolicySummary());
        policies.put("subprocess_environment", safeSubprocessEnvironmentPolicySummary());
        return policies;
    }

    /**
     * 执行附件Policies相关逻辑。
     *
     * @return 返回附件Policies结果。
     */
    private Map<String, Object> attachmentPolicies() {
        Map<String, Object> policies = new LinkedHashMap<String, Object>();
        policies.put("download_io", safeAttachmentDownloadPolicySummary());
        policies.put("media_cache", safeAttachmentMediaCachePolicySummary());
        policies.put("terminal_paste", safeAttachmentTerminalPastePolicySummary());
        return policies;
    }

    /**
     * 执行MCP相关逻辑。
     *
     * @return 返回MCP结果。
     */
    private Map<String, Object> mcp() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("enabled", appConfig.getMcp().isEnabled());
        map.put("status", appConfig.getMcp().isEnabled() ? "enabled" : "disabled");
        map.put("runtime_policy", safeMcpRuntimePolicySummary());
        map.put("oauth_policy", safeMcpOAuthPolicySummary());
        map.put("package_security_policy", safeMcpPackageSecurityPolicySummary());
        return map;
    }

    /**
     * 执行安全相关逻辑。
     *
     * @return 返回安全结果。
     */
    private Map<String, Object> security() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        Map<String, Object> approvals = new LinkedHashMap<String, Object>();
        approvals.put("guardrail_mode", StrUtil.nullToEmpty(appConfig.getSecurity().getGuardrailMode()));
        approvals.put(
                "guardrail_cron_mode",
                StrUtil.nullToEmpty(appConfig.getSecurity().getGuardrailCronMode()));
        approvals.put(
                "subagent_auto_approve",
                Boolean.valueOf(appConfig.getApprovals().isSubagentAutoApprove()));
        approvals.put(
                "timeout_seconds", Integer.valueOf(appConfig.getApprovals().getTimeoutSeconds()));
        approvals.put(
                "gateway_timeout_seconds",
                Integer.valueOf(appConfig.getApprovals().getGatewayTimeoutSeconds()));
        approvals.put(
                "mcp_reload_confirm",
                Boolean.valueOf(appConfig.getApprovals().isMcpReloadConfirm()));
        approvals.put(
                "always_approval_count",
                Integer.valueOf(
                        approvalService == null
                                ? 0
                                : approvalService.listAlwaysApprovals().size()));
        approvals.put("approval_policy", safeApprovalPolicySummary());
        approvals.put("hardline_policy", safeHardlinePolicySummary());
        approvals.put("cron_approval_policy", safeCronApprovalPolicySummary());
        approvals.put("subagent_approval_policy", safeSubagentApprovalPolicySummary());
        approvals.put("smart_approval_policy", safeSmartApprovalPolicySummary());
        approvals.put("tirith_approval_policy", safeTirithApprovalPolicySummary());
        approvals.put("approval_lifecycle_policy", safeApprovalLifecyclePolicySummary());
        approvals.put("slash_confirm_policy", safeSlashConfirmPolicySummary());
        approvals.put("approval_card_policy", safeApprovalCardPolicySummary());
        approvals.put("approval_audit_policy", safeApprovalAuditPolicySummary());
        approvals.put("mcp_reload_policy", safeMcpReloadPolicySummary());
        map.put("approvals", approvals);

        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put(
                "allow_private_urls",
                Boolean.valueOf(appConfig.getSecurity().isAllowPrivateUrls()));
        policy.put("tirith_enabled", Boolean.valueOf(appConfig.getSecurity().isTirithEnabled()));
        policy.put(
                "tirith_configured",
                Boolean.valueOf(StrUtil.isNotBlank(appConfig.getSecurity().getTirithPath())));
        policy.put(
                "tirith_timeout_seconds",
                Integer.valueOf(appConfig.getSecurity().getTirithTimeoutSeconds()));
        policy.put("tirith_fail_open", Boolean.valueOf(appConfig.getSecurity().isTirithFailOpen()));
        policy.put(
                "website_blocklist_enabled",
                Boolean.valueOf(appConfig.getSecurity().getWebsiteBlocklist().isEnabled()));
        policy.put(
                "website_blocklist_domain_count",
                Integer.valueOf(size(appConfig.getSecurity().getWebsiteBlocklist().getDomains())));
        policy.put(
                "website_blocklist_shared_file_count",
                Integer.valueOf(
                        size(appConfig.getSecurity().getWebsiteBlocklist().getSharedFiles())));
        policy.put("url_policy", safeUrlPolicySummary());
        policy.put("private_url_policy", safePrivateUrlPolicySummary());
        policy.put("website_policy", safeWebsitePolicySummary());
        policy.put("path_policy", safePathPolicySummary());
        policy.put("credential_policy", safeCredentialPolicySummary());
        policy.put("tool_args_policy", safeToolArgsPolicySummary());
        policy.put("tirith_policy", safeTirithPolicySummary());
        map.put("policy", policy);

        Map<String, Object> terminal = new LinkedHashMap<String, Object>();
        terminal.put(
                "credential_file_count",
                Integer.valueOf(size(appConfig.getTerminal().getCredentialFiles())));
        terminal.put(
                "env_passthrough_count",
                Integer.valueOf(size(appConfig.getTerminal().getEnvPassthrough())));
        terminal.put(
                "sudo_password_configured",
                Boolean.valueOf(StrUtil.isNotBlank(appConfig.getTerminal().getSudoPassword())));
        terminal.put("credential_file_policy", safeCredentialFilePolicySummary());
        terminal.put("terminal_output_policy", safeTerminalOutputPolicySummary());
        terminal.put("tool_result_storage_policy", safeToolResultStoragePolicySummary());
        terminal.put("sudo_rewrite_policy", safeSudoRewritePolicySummary());
        terminal.put("background_process_policy", safeBackgroundProcessPolicySummary());
        terminal.put("terminal_guardrail_policy", safeTerminalGuardrailPolicySummary());
        terminal.put(
                "max_foreground_timeout_seconds",
                Integer.valueOf(appConfig.getTerminal().getMaxForegroundTimeoutSeconds()));
        terminal.put(
                "foreground_max_retries",
                Integer.valueOf(appConfig.getTerminal().getForegroundMaxRetries()));
        terminal.put(
                "foreground_retry_base_delay_seconds",
                Integer.valueOf(appConfig.getTerminal().getForegroundRetryBaseDelaySeconds()));
        map.put("terminal", terminal);
        map.put("probes", securityPolicyProbes());
        map.put("audit_policy", securityAuditPolicy());
        return map;
    }

    /**
     * 生成安全展示用的审批策略摘要。
     *
     * @return 返回safe审批策略Summary结果。
     */
    private Map<String, Object> safeApprovalPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.approvalPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "guardrailMode");
            copyPolicyValue(summary, safe, "guardrailCronMode");
            copyPolicyValue(summary, safe, "subagentAutoApprove");
            copyPolicyValue(summary, safe, "smartJudgeConfigured");
            copyPolicyValue(summary, safe, "dangerousRuleCount");
            copyPolicyValue(summary, safe, "hardlineRuleCount");
            copyPolicyValue(summary, safe, "dangerousRuleSamples");
            copyPolicyValue(summary, safe, "domesticCloudRuleSamples");
            copyPolicyValue(summary, safe, "cloudStorageRuleSamples");
            copyPolicyValue(summary, safe, "credentialHandlingRuleSamples");
            copyPolicyValue(summary, safe, "secretStoreRuleSamples");
            copyPolicyValue(summary, safe, "hardlineRuleSamples");
            copyPolicyValue(summary, safe, "terminalGuardrailCount");
            copyPolicyValue(summary, safe, "terminalGuardrails");
            copyPolicyValue(summary, safe, "sudoRewriteConfigured");
            copyPolicyValue(summary, safe, "backgroundProcessGuard");
            copyPolicyValue(summary, safe, "urlPolicyPrechecked");
            copyPolicyValue(summary, safe, "privateUrlPolicyPrechecked");
            copyPolicyValue(summary, safe, "credentialUrlPolicyPrechecked");
            copyPolicyValue(summary, safe, "websitePolicyPrechecked");
            copyPolicyValue(summary, safe, "unsafeUrlBlockedBeforeApproval");
            copyPolicyValue(summary, safe, "unsafeUrlApprovalBypassAllowed");
            copyPolicyValue(summary, safe, "configuredCredentialCommandPathDetection");
            copyPolicyValue(summary, safe, "recursiveStructuredToolArgsDetection");
            copyPolicyValue(summary, safe, "nestedArrayCommandArgumentDetection");
            copyPolicyValue(summary, safe, "networkCredentialFieldAliasDetection");
            copyPolicyValue(summary, safe, "sensitiveHttpHeaderAliasDetection");
            copyPolicyValue(summary, safe, "rawCredentialFileUploadDetection");
            copyPolicyValue(summary, safe, "sensitiveClipboardExportDetection");
            copyPolicyValue(summary, safe, "credentialFileClipboardExportDetection");
            copyPolicyValue(summary, safe, "pythonCredentialFileClipboardExportDetection");
            copyPolicyValue(summary, safe, "javascriptCredentialFileClipboardExportDetection");
            copyPolicyValue(summary, safe, "codeCredentialFileStdoutDetection");
            copyPolicyValue(summary, safe, "pythonCredentialFileStdoutDetection");
            copyPolicyValue(summary, safe, "pythonCredentialFileVariableStdoutDetection");
            copyPolicyValue(summary, safe, "pythonCredentialFileLogWriteDetection");
            copyPolicyValue(summary, safe, "javascriptCredentialFileStdoutDetection");
            copyPolicyValue(summary, safe, "javascriptCredentialFileVariableStdoutDetection");
            copyPolicyValue(summary, safe, "javascriptCredentialFileLogWriteDetection");
            copyPolicyValue(summary, safe, "codeCredentialFileVariableStdoutDetection");
            copyPolicyValue(summary, safe, "codeHttpCredentialDisclosureDetection");
            copyPolicyValue(summary, safe, "codeHttpCredentialFileDisclosureDetection");
            copyPolicyValue(summary, safe, "codeHttpCredentialFileVariableDisclosureDetection");
            copyPolicyValue(summary, safe, "powershellCredentialFileHttpDisclosureDetection");
            copyPolicyValue(summary, safe, "approvalTimeoutSeconds");
            copyPolicyValue(summary, safe, "gatewayTimeoutSeconds");
            copyPolicyValue(summary, safe, "alwaysApprovalCount");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的Hardline策略摘要。
     *
     * @return 返回safe Hardline策略Summary结果。
     */
    private Map<String, Object> safeHardlinePolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.hardlinePolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "ruleCount");
            copyPolicyValue(summary, safe, "ruleSamples");
            copyPolicyValue(summary, safe, "coveredTools");
            copyPolicyValue(summary, safe, "blockedCategories");
            copyPolicyValue(summary, safe, "metadataUrlBlocked");
            copyPolicyValue(summary, safe, "codeToolShellExtractionCovered");
            copyPolicyValue(summary, safe, "pythonShellExtractionCovered");
            copyPolicyValue(summary, safe, "javascriptChildProcessExtractionCovered");
            copyPolicyValue(summary, safe, "approvalBypassAllowed");
            copyPolicyValue(summary, safe, "slashApproveBypassAllowed");
            copyPolicyValue(summary, safe, "sessionApprovalBypassAllowed");
            copyPolicyValue(summary, safe, "alwaysApprovalBypassAllowed");
            copyPolicyValue(summary, safe, "sessionAutoApprovalBypassAllowed");
            copyPolicyValue(summary, safe, "smartApprovalBypassAllowed");
            copyPolicyValue(summary, safe, "blockingDecision");
            copyPolicyValue(summary, safe, "approvalRequired");
            copyPolicyValue(summary, safe, "commandPreviewRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的定时任务审批策略摘要。
     *
     * @return 返回safe定时任务审批策略Summary结果。
     */
    private Map<String, Object> safeCronApprovalPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.cronApprovalPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "guardrailCronMode");
            copyPolicyValue(summary, safe, "autoApproveDangerousCommands");
            copyPolicyValue(summary, safe, "defaultDecision");
            copyPolicyValue(summary, safe, "configKeys");
            copyPolicyValue(summary, safe, "approveAliases");
            copyPolicyValue(summary, safe, "approvalAliases");
            copyPolicyValue(summary, safe, "strictAliases");
            copyPolicyValue(summary, safe, "bypassAliases");
            copyPolicyValue(summary, safe, "approvalScope");
            copyPolicyValue(summary, safe, "guardrailApprovalCanPauseCron");
            copyPolicyValue(summary, safe, "jobScopeIncludesScriptFingerprint");
            copyPolicyValue(summary, safe, "hardlineAlwaysBlocked");
            copyPolicyValue(summary, safe, "hardlineAllowlist");
            copyPolicyValue(summary, safe, "hardlineAllowlistConfigKey");
            copyPolicyValue(summary, safe, "allowlistedHardlineCategoriesCanRun");
            copyPolicyValue(summary, safe, "dangerousPatternCheckedBeforeRun");
            copyPolicyValue(summary, safe, "scriptContentChecked");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的子Agent审批策略摘要。
     *
     * @return 返回safe Subagent审批策略Summary结果。
     */
    private Map<String, Object> safeSubagentApprovalPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.subagentApprovalPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "autoApproveDangerousCommands");
            copyPolicyValue(summary, safe, "defaultDecision");
            copyPolicyValue(summary, safe, "configKey");
            copyPolicyValue(summary, safe, "runKind");
            copyPolicyValue(summary, safe, "hardlinePrechecked");
            copyPolicyValue(summary, safe, "filePolicyPrechecked");
            copyPolicyValue(summary, safe, "urlPolicyPrechecked");
            copyPolicyValue(summary, safe, "terminalGuardrailPrechecked");
            copyPolicyValue(summary, safe, "smartApprovalRunsBeforeSubagentPolicy");
            copyPolicyValue(summary, safe, "humanApprovalPromptSuppressed");
            copyPolicyValue(summary, safe, "currentThreadApprovalWhenAutoApproved");
            copyPolicyValue(summary, safe, "pendingApprovalCreatedWhenDenied");
            copyPolicyValue(summary, safe, "denyMessageIncludesConfigHint");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的Smart审批策略摘要。
     *
     * @return 返回safe Smart审批策略Summary结果。
     */
    private Map<String, Object> safeSmartApprovalPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.smartApprovalPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "guardrailMode");
            copyPolicyValue(summary, safe, "smartMode");
            copyPolicyValue(summary, safe, "judgeConfigured");
            copyPolicyValue(summary, safe, "active");
            copyPolicyValue(summary, safe, "decisionTypes");
            copyPolicyValue(summary, safe, "approveWritesSessionApproval");
            copyPolicyValue(summary, safe, "approveMarksCurrentThread");
            copyPolicyValue(summary, safe, "escalateFallsBackToHumanApproval");
            copyPolicyValue(summary, safe, "denyBlocksExecution");
            copyPolicyValue(summary, safe, "judgeFailureFallsBackToHumanApproval");
            copyPolicyValue(summary, safe, "hardlinePrechecked");
            copyPolicyValue(summary, safe, "filePolicyPrechecked");
            copyPolicyValue(summary, safe, "urlPolicyPrechecked");
            copyPolicyValue(summary, safe, "terminalGuardrailPrechecked");
            copyPolicyValue(summary, safe, "tirithFindingsIncluded");
            copyPolicyValue(summary, safe, "subagentPolicyRunsAfterSmartApproval");
            copyPolicyValue(summary, safe, "approvalCardFallback");
            copyPolicyValue(summary, safe, "reasonStoredInBlockMessage");
            copyPolicyValue(summary, safe, "commandPreviewRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的Tirith审批策略摘要。
     *
     * @return 返回safe Tirith审批策略Summary结果。
     */
    private Map<String, Object> safeTirithApprovalPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.tirithApprovalPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "scannerConfigured");
            copyPolicyValue(summary, safe, "scanRunsInApprovalMode");
            copyPolicyValue(summary, safe, "patternKeyPrefix");
            copyPolicyValue(summary, safe, "emptyFindingsPatternKey");
            copyPolicyValue(summary, safe, "findingsBecomePatternKeys");
            copyPolicyValue(summary, safe, "combinedWithLocalDangerRules");
            copyPolicyValue(summary, safe, "permanentApprovalAllowed");
            copyPolicyValue(summary, safe, "alwaysScopeDowngradedToSession");
            copyPolicyValue(summary, safe, "approvalCardAlwaysHidden");
            copyPolicyValue(summary, safe, "smartApprovalCanApproveSessionOnly");
            copyPolicyValue(summary, safe, "smartApprovalCanDeny");
            copyPolicyValue(summary, safe, "pendingMessageBlocksAlwaysScope");
            copyPolicyValue(summary, safe, "descriptionRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的审批生命周期策略摘要。
     *
     * @return 返回safe审批生命周期策略Summary结果。
     */
    private Map<String, Object> safeApprovalLifecyclePolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.approvalLifecyclePolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "pendingListPrunedBeforeRead");
            copyPolicyValue(summary, safe, "selectorSupported");
            copyPolicyValue(summary, safe, "selectorTokenPattern");
            copyPolicyValue(summary, safe, "unsafeSelectorRejected");
            copyPolicyValue(summary, safe, "listSupported");
            copyPolicyValue(summary, safe, "approveAllSupported");
            copyPolicyValue(summary, safe, "rejectAllSupported");
            copyPolicyValue(summary, safe, "bulkRejectUsesSafeSelector");
            copyPolicyValue(summary, safe, "clearSessionSupported");
            copyPolicyValue(summary, safe, "clearAlwaysSupported");
            copyPolicyValue(summary, safe, "clearAllSupported");
            copyPolicyValue(summary, safe, "scopes");
            copyPolicyValue(summary, safe, "alwaysScopeUsesGlobalSettings");
            copyPolicyValue(summary, safe, "tirithAlwaysScopeDowngradedToSession");
            copyPolicyValue(summary, safe, "currentThreadApprovalTtlMillis");
            copyPolicyValue(summary, safe, "currentThreadApprovalEnabled");
            copyPolicyValue(summary, safe, "approveRemovesPendingApproval");
            copyPolicyValue(summary, safe, "rejectRemovesPendingApproval");
            copyPolicyValue(summary, safe, "sessionSnapshotUpdated");
            copyPolicyValue(summary, safe, "approvalRequestObserved");
            copyPolicyValue(summary, safe, "approvalResponseObserved");
            copyPolicyValue(summary, safe, "approverRedacted");
            copyPolicyValue(summary, safe, "approvalKeyRedacted");
            copyPolicyValue(summary, safe, "commandPreviewRedacted");
            copyPolicyValue(summary, safe, "encodedUrlParameterRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的斜杠命令Confirm策略摘要。
     *
     * @return 返回safe Slash Confirm策略Summary结果。
     */
    private Map<String, Object> safeSlashConfirmPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.slashConfirmPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "commands");
            copyPolicyValue(summary, safe, "selectorSupported");
            copyPolicyValue(summary, safe, "selectorTokenPattern");
            copyPolicyValue(summary, safe, "unsafeSelectorRejected");
            copyPolicyValue(summary, safe, "listSupported");
            copyPolicyValue(summary, safe, "approveAllSupported");
            copyPolicyValue(summary, safe, "denyAllSupported");
            copyPolicyValue(summary, safe, "clearSessionSupported");
            copyPolicyValue(summary, safe, "clearAlwaysSupported");
            copyPolicyValue(summary, safe, "clearAllSupported");
            copyPolicyValue(summary, safe, "scopes");
            copyPolicyValue(summary, safe, "defaultScope");
            copyPolicyValue(summary, safe, "managementCommands");
            copyPolicyValue(summary, safe, "pendingQueueSupported");
            copyPolicyValue(summary, safe, "pendingListHidesApprovalKey");
            copyPolicyValue(summary, safe, "pendingListUsesSafeSelector");
            copyPolicyValue(summary, safe, "pendingListShowsPatternKey");
            copyPolicyValue(summary, safe, "sessionApprovalListShowsCountOnly");
            copyPolicyValue(summary, safe, "alwaysApprovalListShowsCountOnly");
            copyPolicyValue(summary, safe, "approvalCardDeliveryMode");
            copyPolicyValue(summary, safe, "approvalCardPlatforms");
            copyPolicyValue(summary, safe, "permanentApprovalAllowedExceptTirith");
            copyPolicyValue(summary, safe, "tirithAlwaysDowngradedToSession");
            copyPolicyValue(summary, safe, "approverRedacted");
            copyPolicyValue(summary, safe, "commandPreviewRedacted");
            copyPolicyValue(summary, safe, "encodedUrlParameterRedacted");
            copyPolicyValue(summary, safe, "approvalMetadataRedacted");
            copyPolicyValue(summary, safe, "observerEventsRedacted");
            copyPolicyValue(summary, safe, "approvalTimeoutSeconds");
            copyPolicyValue(summary, safe, "gatewayTimeoutSeconds");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的审批卡片策略摘要。
     *
     * @return 返回safe审批Card策略Summary结果。
     */
    private Map<String, Object> safeApprovalCardPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.approvalCardPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "deliveryMode");
            copyPolicyValue(summary, safe, "supportedPlatforms");
            copyPolicyValue(summary, safe, "unsupportedPlatformsReturnEmptyExtras");
            copyPolicyValue(summary, safe, "actionKey");
            copyPolicyValue(summary, safe, "approveAction");
            copyPolicyValue(summary, safe, "denyAction");
            copyPolicyValue(summary, safe, "scopeKey");
            copyPolicyValue(summary, safe, "approvalIdKey");
            copyPolicyValue(summary, safe, "scopeOptions");
            copyPolicyValue(summary, safe, "defaultScope");
            copyPolicyValue(summary, safe, "approvalIdSelectorSupported");
            copyPolicyValue(summary, safe, "selectorTokenPattern");
            copyPolicyValue(summary, safe, "unsafeSelectorRejected");
            copyPolicyValue(summary, safe, "outboundApprovalIdSanitized");
            copyPolicyValue(summary, safe, "unsafeApprovalIdFallsBackToKeySelector");
            copyPolicyValue(summary, safe, "approveCommandGenerated");
            copyPolicyValue(summary, safe, "denyCommandGenerated");
            copyPolicyValue(summary, safe, "alwaysScopeCommandGenerated");
            copyPolicyValue(summary, safe, "sessionScopeCommandGenerated");
            copyPolicyValue(summary, safe, "domesticCardLabelsLocalized");
            copyPolicyValue(summary, safe, "feishuChineseCardLabels");
            copyPolicyValue(summary, safe, "qqbotSessionActionSupported");
            copyPolicyValue(summary, safe, "tirithPermanentApprovalHidden");
            copyPolicyValue(summary, safe, "commandPreviewRedacted");
            copyPolicyValue(summary, safe, "descriptionPreviewRedacted");
            copyPolicyValue(summary, safe, "toolNameRedacted");
            copyPolicyValue(summary, safe, "commandPreviewRedactedInExtras");
            copyPolicyValue(summary, safe, "rawCommandRedactedInExtras");
            copyPolicyValue(summary, safe, "encodedUrlParameterRedacted");
            copyPolicyValue(summary, safe, "encodedUrlParameterRedactedInExtras");
            copyPolicyValue(summary, safe, "semicolonUrlParameterRedacted");
            copyPolicyValue(summary, safe, "fragmentUrlParameterRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的审批审计策略摘要。
     *
     * @return 返回safe审批审计策略Summary结果。
     */
    private Map<String, Object> safeApprovalAuditPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.approvalAuditPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "observerCount");
            copyPolicyValue(summary, safe, "requestEvents");
            copyPolicyValue(summary, safe, "responseEvents");
            copyPolicyValue(summary, safe, "eventTypes");
            copyPolicyValue(summary, safe, "repositoryBackedWhenConfigured");
            copyPolicyValue(summary, safe, "observerFailureIsolated");
            copyPolicyValue(summary, safe, "approverRedacted");
            copyPolicyValue(summary, safe, "commandPreviewRedacted");
            copyPolicyValue(summary, safe, "descriptionRedacted");
            copyPolicyValue(summary, safe, "approvalKeyRedacted");
            copyPolicyValue(summary, safe, "encodedUrlParameterRedacted");
            copyPolicyValue(summary, safe, "commandHashStored");
            copyPolicyValue(summary, safe, "patternKeysStored");
            copyPolicyValue(summary, safe, "timestampsStored");
            copyPolicyValue(summary, safe, "recentDashboardViewSupported");
            copyPolicyValue(summary, safe, "manualRevocationAudited");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的MCPReload策略摘要。
     *
     * @return 返回safe MCP Reload策略Summary结果。
     */
    private Map<String, Object> safeMcpReloadPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.mcpReloadPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "command");
            copyPolicyValue(summary, safe, "confirmRequired");
            copyPolicyValue(summary, safe, "configKey");
            copyPolicyValue(summary, safe, "slashConfirmBacked");
            copyPolicyValue(summary, safe, "directRunArgument");
            copyPolicyValue(summary, safe, "alwaysConfirmArgument");
            copyPolicyValue(summary, safe, "persistentDisableSupported");
            copyPolicyValue(summary, safe, "runtimeConfigPersisted");
            copyPolicyValue(summary, safe, "toolChangeNoticeInjected");
            copyPolicyValue(summary, safe, "changedServerSummary");
            copyPolicyValue(summary, safe, "toolCountSummary");
            copyPolicyValue(summary, safe, "oauthUrlSafetyCovered");
            copyPolicyValue(summary, safe, "encodedUrlParameterRedacted");
            copyPolicyValue(summary, safe, "reloadHistoryNoticeRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的MCP运行时策略摘要。
     *
     * @return 返回safe MCP运行时策略Summary结果。
     */
    private Map<String, Object> safeMcpRuntimePolicySummary() {
        try {
            Map<String, Object> summary = McpRuntimeService.policySummary(appConfig);
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "supportedTransports");
            copyPolicyValue(summary, safe, "remoteEndpointUrlSafety");
            copyPolicyValue(summary, safe, "remoteEndpointAllowsPrivateByPolicy");
            copyPolicyValue(summary, safe, "stdioEndpointSkipped");
            copyPolicyValue(summary, safe, "remoteToolArgumentUrlSafety");
            copyPolicyValue(summary, safe, "remoteToolArgumentPathSafety");
            copyPolicyValue(summary, safe, "resourceUriUrlSafety");
            copyPolicyValue(summary, safe, "resourceUriPathSafety");
            copyPolicyValue(summary, safe, "nestedUrlExtraction");
            copyPolicyValue(summary, safe, "blockedUrlsMasked");
            copyPolicyValue(summary, safe, "blockedPathsRedacted");
            copyPolicyValue(summary, safe, "inputSchemaSanitized");
            copyPolicyValue(summary, safe, "toolNamesPrefixed");
            copyPolicyValue(summary, safe, "toolIncludeExcludeFilter");
            copyPolicyValue(summary, safe, "resourceUtilityToolsCapabilityGated");
            copyPolicyValue(summary, safe, "promptUtilityToolsCapabilityGated");
            copyPolicyValue(summary, safe, "blockedServersSuppressed");
            copyPolicyValue(summary, safe, "toolsChangeNotificationPersisted");
            copyPolicyValue(summary, safe, "toolChangeHashTracked");
            copyPolicyValue(summary, safe, "toolsChangeClearsProviderCache");
            copyPolicyValue(summary, safe, "oauthFailureStructuredReauth");
            copyPolicyValue(summary, safe, "oauthSecretsRedacted");
            copyPolicyValue(summary, safe, "recoverableTransportRetry");
            copyPolicyValue(summary, safe, "remoteToolTimeoutMillisDefault");
            copyPolicyValue(summary, safe, "connectTimeoutMillisDefault");
            copyPolicyValue(summary, safe, "toolCallExecutorBounded");
            copyPolicyValue(summary, safe, "toolCallExecutorMaxThreads");
            copyPolicyValue(summary, safe, "toolCallExecutorQueueCapacity");
            copyPolicyValue(summary, safe, "accessTokenHeaderOnlyForRemote");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的MCPOAuth 认证策略摘要。
     *
     * @return 返回safe MCP OAuth 认证策略Summary结果。
     */
    private Map<String, Object> safeMcpOAuthPolicySummary() {
        try {
            Map<String, Object> summary = DashboardMcpService.oauthPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "authorizationEndpointUrlSafety");
            copyPolicyValue(summary, safe, "tokenEndpointUrlSafety");
            copyPolicyValue(summary, safe, "tokenEndpointRedirectUrlSafety");
            copyPolicyValue(summary, safe, "tokenEndpointRedirectLimit");
            copyPolicyValue(summary, safe, "crossOriginRedirectBodyForwardingBlocked");
            copyPolicyValue(summary, safe, "stateValidationRequired");
            copyPolicyValue(summary, safe, "pkceS256Required");
            copyPolicyValue(summary, safe, "codeVerifierHiddenFromStatus");
            copyPolicyValue(summary, safe, "accessTokenRedacted");
            copyPolicyValue(summary, safe, "refreshTokenRedacted");
            copyPolicyValue(summary, safe, "clientSecretRedacted");
            copyPolicyValue(summary, safe, "refreshRequiresRefreshToken");
            copyPolicyValue(summary, safe, "handle401RefreshThenReauth");
            copyPolicyValue(summary, safe, "clearRemovesSecretPresenceFlags");
            copyPolicyValue(summary, safe, "statusPresenceFields");
            copyPolicyValue(summary, safe, "callbackErrorsRedacted");
            copyPolicyValue(summary, safe, "tokenErrorsRedacted");
            copyPolicyValue(summary, safe, "tokenResponseRequiresAccessToken");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的MCP包安全策略摘要。
     *
     * @return 返回safe MCP Package安全策略Summary结果。
     */
    private Map<String, Object> safeMcpPackageSecurityPolicySummary() {
        try {
            Map<String, Object> summary = new McpPackageSecurityService(null).policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabledForTransport");
            copyPolicyValue(summary, safe, "checkedLaunchers");
            copyPolicyValue(summary, safe, "supportedEcosystems");
            copyPolicyValue(summary, safe, "endpointUrlSafetyChecked");
            copyPolicyValue(summary, safe, "endpointOverrideEnvironment");
            copyPolicyValue(summary, safe, "projectEndpointOverrideEnvironment");
            copyPolicyValue(summary, safe, "malwareAdvisoryPrefix");
            copyPolicyValue(summary, safe, "nonMalwareVulnerabilitiesIgnored");
            copyPolicyValue(summary, safe, "malwareBlocksSaveAndCheck");
            copyPolicyValue(summary, safe, "requestFailureFailsOpen");
            copyPolicyValue(summary, safe, "unsafeEndpointBlocksBeforeNetwork");
            copyPolicyValue(summary, safe, "structuredReasons");
            copyPolicyValue(summary, safe, "persistedListReasonExposed");
            copyPolicyValue(summary, safe, "packageVersionParsed");
            copyPolicyValue(summary, safe, "scopedNpmPackageParsed");
            copyPolicyValue(summary, safe, "npxPackageOptionParsed");
            copyPolicyValue(summary, safe, "pipxRunSubcommandSkipped");
            copyPolicyValue(summary, safe, "pypiSourceOptionParsed");
            copyPolicyValue(summary, safe, "pypiExtrasIgnored");
            copyPolicyValue(summary, safe, "jsonArgsSupported");
            copyPolicyValue(summary, safe, "advisoryMessageLimit");
            copyPolicyValue(summary, safe, "messageRedacted");
            copyPolicyValue(summary, safe, "endpointRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的结构清理器策略摘要。
     *
     * @return 返回safe结构清理器策略Summary结果。
     */
    private Map<String, Object> safeSchemaSanitizerPolicySummary() {
        try {
            Map<String, Object> summary = SolonClawToolSchemaSanitizer.policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "appliesTo");
            copyPolicyValue(summary, safe, "inputSchemaSanitized");
            copyPolicyValue(summary, safe, "outputFunctionToolSchemaSanitized");
            copyPolicyValue(summary, safe, "mcpInputSchemaSanitized");
            copyPolicyValue(summary, safe, "invalidSchemaDefaultsToObject");
            copyPolicyValue(summary, safe, "topLevelObjectRequired");
            copyPolicyValue(summary, safe, "propertiesInjectedForObject");
            copyPolicyValue(summary, safe, "requiredPrunedToKnownProperties");
            copyPolicyValue(summary, safe, "nullableUnionCollapsed");
            copyPolicyValue(summary, safe, "patternAndFormatStripped");
            copyPolicyValue(summary, safe, "schemaObjectSanitizationNonMutating");
            copyPolicyValue(summary, safe, "jsonLibrary");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的补丁Parser策略摘要。
     *
     * @return 返回safe Patch Parser策略Summary结果。
     */
    private Map<String, Object> safePatchParserPolicySummary() {
        try {
            Map<String, Object> summary = SolonClawPatchTools.patchParserPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "toolName");
            copyPolicyValue(summary, safe, "modes");
            copyPolicyValue(summary, safe, "patchFormat");
            copyPolicyValue(summary, safe, "beginEndMarkersRequired");
            copyPolicyValue(summary, safe, "operations");
            copyPolicyValue(summary, safe, "atomicValidationBeforeWrite");
            copyPolicyValue(summary, safe, "noPartialWritesOnValidationFailure");
            copyPolicyValue(summary, safe, "replaceRequiresUniqueMatchByDefault");
            copyPolicyValue(summary, safe, "replaceAllRequiresExplicitFlag");
            copyPolicyValue(summary, safe, "additionOnlyContextHintsSupported");
            copyPolicyValue(summary, safe, "ambiguousHunksBlocked");
            copyPolicyValue(summary, safe, "missingHunksBlocked");
            copyPolicyValue(summary, safe, "addWillNotOverwriteExistingFile");
            copyPolicyValue(summary, safe, "moveWillNotOverwriteDestination");
            copyPolicyValue(summary, safe, "deleteRequiresExistingFile");
            copyPolicyValue(summary, safe, "pathTraversalBlocked");
            copyPolicyValue(summary, safe, "nulPathBlocked");
            copyPolicyValue(summary, safe, "jarInternalPathBlocked");
            copyPolicyValue(summary, safe, "symlinkEscapeBlocked");
            copyPolicyValue(summary, safe, "credentialPolicyPrechecked");
            copyPolicyValue(summary, safe, "moveDestinationPolicyChecked");
            copyPolicyValue(summary, safe, "errorsRedacted");
            copyPolicyValue(summary, safe, "staleFileWarnings");
            copyPolicyValue(summary, safe, "diffReturned");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的CodeExecution策略摘要。
     *
     * @return 返回safe Code Execution策略Summary结果。
     */
    private Map<String, Object> safeCodeExecutionPolicySummary() {
        try {
            Map<String, Object> summary =
                    SolonClawCodeExecutionSkills.codeExecutionPolicySummary(appConfig);
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "executeCodeSupported");
            copyPolicyValue(summary, safe, "executePythonSupported");
            copyPolicyValue(summary, safe, "executeJsSupported");
            copyPolicyValue(summary, safe, "solonAiSysSkillsWrapped");
            copyPolicyValue(summary, safe, "workdirTextValidated");
            copyPolicyValue(summary, safe, "scriptPreflightPathPolicy");
            copyPolicyValue(summary, safe, "scriptPreflightUrlPolicy");
            copyPolicyValue(summary, safe, "fileGuardrailMode");
            copyPolicyValue(summary, safe, "urlGuardrailMode");
            copyPolicyValue(summary, safe, "dangerousCommandRulesApplied");
            copyPolicyValue(summary, safe, "hardlineRulesApplied");
            copyPolicyValue(summary, safe, "foregroundBackgroundGuardrail");
            copyPolicyValue(summary, safe, "managedFileToolPathLiteralsIgnoredForPreflight");
            copyPolicyValue(summary, safe, "stagingDirectoryPerRun");
            copyPolicyValue(summary, safe, "stagingCleanup");
            copyPolicyValue(summary, safe, "sandboxEnvironmentSanitized");
            copyPolicyValue(summary, safe, "pythonPathPrependsStaging");
            copyPolicyValue(summary, safe, "pythonIoEncodingUtf8");
            copyPolicyValue(summary, safe, "pythonDontWriteBytecode");
            copyPolicyValue(summary, safe, "rpcToolBridgeEnabled");
            copyPolicyValue(summary, safe, "rpcRequestFilesSorted");
            copyPolicyValue(summary, safe, "rpcToolOutputsRedacted");
            copyPolicyValue(summary, safe, "defaultTimeoutSeconds");
            copyPolicyValue(summary, safe, "maxTimeoutClampedByTerminalConfig");
            copyPolicyValue(summary, safe, "timeoutKillsProcess");
            copyPolicyValue(summary, safe, "stdoutLimitChars");
            copyPolicyValue(summary, safe, "stderrLimitChars");
            copyPolicyValue(summary, safe, "ansiOutputStripped");
            copyPolicyValue(summary, safe, "outputRedacted");
            copyPolicyValue(summary, safe, "outputTruncated");
            copyPolicyValue(summary, safe, "stderrReturnedOnlyOnErrors");
            copyPolicyValue(summary, safe, "safeErrorTextRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的子进程Environment策略摘要。
     *
     * @return 返回safe子进程Environment策略Summary结果。
     */
    private Map<String, Object> safeSubprocessEnvironmentPolicySummary() {
        try {
            Map<String, Object> summary = SubprocessEnvironmentSanitizer.policySummary(appConfig);
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "defaultDenyUnknownEnv");
            copyPolicyValue(summary, safe, "safePrefixCount");
            copyPolicyValue(summary, safe, "safeContextEnvCount");
            copyPolicyValue(summary, safe, "secretSubstringCount");
            copyPolicyValue(summary, safe, "providerBlocklistCount");
            copyPolicyValue(summary, safe, "configuredPassthroughCount");
            copyPolicyValue(summary, safe, "decisionProbeSupported");
            copyPolicyValue(summary, safe, "decisionProbeValueRedacted");
            copyPolicyValue(summary, safe, "decisionProbeEffectiveNameSupported");
            copyPolicyValue(summary, safe, "decisionProbeVisibilitySupported");
            copyPolicyValue(summary, safe, "decisionProbeSourceSupported");
            copyPolicyValue(summary, safe, "decisionCategories");
            copyPolicyValue(summary, safe, "skillScopedPassthroughSupported");
            copyPolicyValue(summary, safe, "skillScopedPassthroughThreadLocal");
            copyPolicyValue(summary, safe, "providerBlocklistOverridesPassthrough");
            copyPolicyValue(summary, safe, "forcePrefixSupported");
            copyPolicyValue(summary, safe, "forcePrefixRequiresValidEnvName");
            copyPolicyValue(summary, safe, "secretNameSubstringsBlocked");
            copyPolicyValue(summary, safe, "runtimeSafetyTogglesBlocked");
            copyPolicyValue(summary, safe, "channelSecretsBlocked");
            copyPolicyValue(summary, safe, "toolBackendSecretsBlocked");
            copyPolicyValue(summary, safe, "gatewaySecretsBlocked");
            copyPolicyValue(summary, safe, "pathFallbackEnabledForPosix");
            copyPolicyValue(summary, safe, "windowsPathFallbackDisabled");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的附件Download策略摘要。
     *
     * @return 返回safe附件Download策略Summary结果。
     */
    private Map<String, Object> safeAttachmentDownloadPolicySummary() {
        try {
            Map<String, Object> summary = BoundedAttachmentIO.policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "hutoolDownloadGuarded");
            copyPolicyValue(summary, safe, "okHttpDownloadGuarded");
            copyPolicyValue(summary, safe, "initialUrlChecked");
            copyPolicyValue(summary, safe, "redirectUrlCheckedBeforeFollow");
            copyPolicyValue(summary, safe, "manualRedirectHandling");
            copyPolicyValue(summary, safe, "maxRedirects");
            copyPolicyValue(summary, safe, "redirectLocationRequired");
            copyPolicyValue(summary, safe, "redirectUrlResolvedAgainstCurrentUrl");
            copyPolicyValue(summary, safe, "crossHostHeaderForwardingBlocked");
            copyPolicyValue(summary, safe, "sameOriginHeadersAllowed");
            copyPolicyValue(summary, safe, "blockedUrlMasked");
            copyPolicyValue(summary, safe, "contentLengthChecked");
            copyPolicyValue(summary, safe, "streamReadBounded");
            copyPolicyValue(summary, safe, "defaultMaxBytes");
            copyPolicyValue(summary, safe, "jsonMaxBytes");
            copyPolicyValue(summary, safe, "updateJarMaxBytes");
            copyPolicyValue(summary, safe, "contentTypeCaptured");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的附件媒体缓存策略摘要。
     *
     * @return 返回safe附件媒体缓存策略Summary结果。
     */
    private Map<String, Object> safeAttachmentMediaCachePolicySummary() {
        try {
            Map<String, Object> summary = new AttachmentCacheService(appConfig).policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "mediaReferencePrefix");
            copyPolicyValue(summary, safe, "maxCacheBytes");
            copyPolicyValue(summary, safe, "cacheBytesSizeChecked");
            copyPolicyValue(summary, safe, "safeOriginalNameSanitized");
            copyPolicyValue(summary, safe, "safeOriginalNameSecretRedacted");
            copyPolicyValue(summary, safe, "mimeSniffingEnabled");
            copyPolicyValue(summary, safe, "kindNormalized");
            copyPolicyValue(summary, safe, "fromLocalFileRequiresRuntimeCache");
            copyPolicyValue(summary, safe, "fromMediaCacheRequiresMediaRoot");
            copyPolicyValue(summary, safe, "mediaReferenceRequiresMediaRoot");
            copyPolicyValue(summary, safe, "mediaReferenceTraversalBlocked");
            copyPolicyValue(summary, safe, "generatedAttachmentSingleRuntimeLevelOnly");
            copyPolicyValue(summary, safe, "generatedAttachmentExtensionAllowlist");
            copyPolicyValue(summary, safe, "hostPathsNotReturnedInMediaReference");
            copyPolicyValue(summary, safe, "mediaRoot");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的附件终端Paste策略摘要。
     *
     * @return 返回safe附件终端Paste策略Summary结果。
     */
    private Map<String, Object> safeAttachmentTerminalPastePolicySummary() {
        try {
            Map<String, Object> summary = CliAttachmentResolver.policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "pastedLocalPathDetection");
            copyPolicyValue(summary, safe, "fileUriDetection");
            copyPolicyValue(summary, safe, "fileUriPercentDecoded");
            copyPolicyValue(summary, safe, "windowsPathDetection");
            copyPolicyValue(summary, safe, "windowsPathPreviewCrossPlatform");
            copyPolicyValue(summary, safe, "windowsDrivePathNotDuplicatedAsPosix");
            copyPolicyValue(summary, safe, "posixPathDetection");
            copyPolicyValue(summary, safe, "tildeHomeExpansion");
            copyPolicyValue(summary, safe, "canonicalPathResolvedBeforePolicy");
            copyPolicyValue(summary, safe, "duplicatePathDeduplicated");
            copyPolicyValue(summary, safe, "pathPolicyCheckedBeforeCache");
            copyPolicyValue(summary, safe, "cacheWriteAfterPolicyOnly");
            copyPolicyValue(summary, safe, "credentialPathBlocked");
            copyPolicyValue(summary, safe, "blockedPreviewRedacted");
            copyPolicyValue(summary, safe, "missingPreviewRedacted");
            copyPolicyValue(summary, safe, "resolvedDisplayNameRedacted");
            copyPolicyValue(summary, safe, "rawPathHiddenInPrompt");
            copyPolicyValue(summary, safe, "maxAttachmentPaths");
            copyPolicyValue(summary, safe, "maxAttachmentBytes");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的凭据文件策略摘要。
     *
     * @return 返回safe凭据文件策略Summary结果。
     */
    private Map<String, Object> safeCredentialFilePolicySummary() {
        try {
            Map<String, Object> summary = new SkillCredentialFileService(appConfig).policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "configCredentialFileCount");
            copyPolicyValue(summary, safe, "configuredMountCount");
            copyPolicyValue(summary, safe, "configuredMissingCount");
            copyPolicyValue(summary, safe, "configuredRejectedCount");
            copyPolicyValue(summary, safe, "sandboxCredentialMountCount");
            copyPolicyValue(summary, safe, "runtimeRelativeOnly");
            copyPolicyValue(summary, safe, "absolutePathRejected");
            copyPolicyValue(summary, safe, "pathTraversalRejected");
            copyPolicyValue(summary, safe, "controlCharacterRejected");
            copyPolicyValue(summary, safe, "runtimeHomeEscapeRejected");
            copyPolicyValue(summary, safe, "missingFilesNotMounted");
            copyPolicyValue(summary, safe, "hostPathsOmittedFromMetadata");
            copyPolicyValue(summary, safe, "rejectedPathsRedacted");
            copyPolicyValue(summary, safe, "skillFrontmatterKey");
            copyPolicyValue(summary, safe, "configKey");
            return safe;
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<String, Object>();
            fallback.put("available", Boolean.FALSE);
            fallback.put(
                    "summary",
                    SecretRedactor.redact(
                            StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                            1000));
            return fallback;
        }
    }

    /**
     * 生成安全展示用的终端输出策略摘要。
     *
     * @return 返回safe终端输出策略Summary结果。
     */
    private Map<String, Object> safeTerminalOutputPolicySummary() {
        try {
            Map<String, Object> summary =
                    SolonClawShellSkill.terminalOutputPolicySummary(appConfig);
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "ansiStripped");
            copyPolicyValue(summary, safe, "ecma48SequencesStripped");
            copyPolicyValue(summary, safe, "oscSequencesStripped");
            copyPolicyValue(summary, safe, "eightBitC1ControlsStripped");
            copyPolicyValue(summary, safe, "displayControlCharsStripped");
            copyPolicyValue(summary, safe, "bidiControlsStripped");
            copyPolicyValue(summary, safe, "secretRedactionApplied");
            copyPolicyValue(summary, safe, "maxInlineChars");
            copyPolicyValue(summary, safe, "headTailTruncation");
            copyPolicyValue(summary, safe, "truncationNoticeIncluded");
            copyPolicyValue(summary, safe, "emptySuccessMessage");
            copyPolicyValue(summary, safe, "timeoutNoticeAppended");
            copyPolicyValue(summary, safe, "sudoFailureHintAppended");
            copyPolicyValue(summary, safe, "outputTransformersSupported");
            copyPolicyValue(summary, safe, "transformerFailureIsolated");
            copyPolicyValue(summary, safe, "exitCodeSemanticsAvailable");
            copyPolicyValue(summary, safe, "exitCodeMeaningReturned");
            copyPolicyValue(summary, safe, "executeShellExitMeaningNotice");
            if (summary.get("exitCodeSemantics") instanceof Map) {
                safe.put(
                        "exitCodeSemantics",
                        safeTerminalExitCodeSemantics(
                                (Map<String, Object>) summary.get("exitCodeSemantics")));
            }
            copyPolicyValue(summary, safe, "foregroundRetryErrorsInterpreted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的终端退出CodeSemantics。
     *
     * @param summary 摘要参数。
     * @return 返回safe终端退出码 Semantics结果。
     */
    private Map<String, Object> safeTerminalExitCodeSemantics(Map<String, Object> summary) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        copyPolicyValue(summary, safe, "knownCommandCount");
        copyPolicyValue(summary, safe, "grepNoMatchExitOneInformational");
        copyPolicyValue(summary, safe, "diffExitOneInformational");
        copyPolicyValue(summary, safe, "gitDiffExitOneInformational");
        copyPolicyValue(summary, safe, "curlNetworkErrorsExplained");
        copyPolicyValue(summary, safe, "testExitOneInformational");
        copyPolicyValue(summary, safe, "findExitOnePartialResult");
        copyPolicyValue(summary, safe, "commandSamples");
        copyPolicyValue(summary, safe, "exitCodeSamples");
        return safe;
    }

    /**
     * 生成安全展示用的工具结果Storage策略摘要。
     *
     * @return 返回safe工具结果Storage策略Summary结果。
     */
    private Map<String, Object> safeToolResultStoragePolicySummary() {
        try {
            ToolResultStorageService service =
                    toolResultStorageService == null
                            ? new ToolResultStorageService(
                                    appConfig.getRuntime().getCacheDir(),
                                    appConfig.getTask().getToolOutputInlineLimit(),
                                    appConfig.getTask().getToolOutputTurnBudget(),
                                    appConfig.getTrace().getToolPreviewLength())
                            : toolResultStorageService;
            Map<String, Object> summary = service.policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "interceptorBacked");
            copyPolicyValue(summary, safe, "inlineLimitBytes");
            copyPolicyValue(summary, safe, "turnBudgetBytes");
            copyPolicyValue(summary, safe, "previewLength");
            copyPolicyValue(summary, safe, "pinnedInlineTools");
            copyPolicyValue(summary, safe, "pinnedInlineRawObservationAllowed");
            copyPolicyValue(summary, safe, "pinnedInlineObservationRedacted");
            copyPolicyValue(summary, safe, "pinnedInlinePreviewRedacted");
            copyPolicyValue(summary, safe, "oversizedResultsPersisted");
            copyPolicyValue(summary, safe, "turnBudgetOverflowPersisted");
            copyPolicyValue(summary, safe, "persistedOutputBlock");
            copyPolicyValue(summary, safe, "resultRefReturned");
            copyPolicyValue(summary, safe, "readBackGuidanceIncluded");
            copyPolicyValue(summary, safe, "untrustedToolResultBoundary");
            copyPolicyValue(summary, safe, "untrustedBoundaryAppliesToInlineResults");
            copyPolicyValue(summary, safe, "untrustedBoundaryAppliesToPersistedOutputBlocks");
            copyPolicyValue(summary, safe, "untrustedBoundarySkippedForPinnedInlineTools");
            copyPolicyValue(summary, safe, "untrustedToolNames");
            copyPolicyValue(summary, safe, "untrustedToolPrefixes");
            copyPolicyValue(summary, safe, "previewRedacted");
            copyPolicyValue(summary, safe, "describedPreviewRedacted");
            copyPolicyValue(summary, safe, "persistedOutputRedacted");
            copyPolicyValue(summary, safe, "fullOutputSavedRaw");
            copyPolicyValue(summary, safe, "pathSegmentsSanitized");
            copyPolicyValue(summary, safe, "canonicalChildPathCheck");
            copyPolicyValue(summary, safe, "workspaceRelativeRefsPreferred");
            copyPolicyValue(summary, safe, "storageBase");
            copyPolicyValue(summary, safe, "describePersistedObservation");
            copyPolicyValue(summary, safe, "storageFailureFallsBackToPreviewOnly");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的sudoRewrite策略摘要。
     *
     * @return 返回safe Sudo Rewrite策略Summary结果。
     */
    private Map<String, Object> safeSudoRewritePolicySummary() {
        try {
            boolean sudoPasswordConfigured =
                    appConfig != null
                            && appConfig.getTerminal() != null
                            && appConfig.getTerminal().getSudoPassword() != null;
            Map<String, Object> summary =
                    SolonClawShellSkill.sudoRewritePolicySummary(sudoPasswordConfigured);
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "configured");
            copyPolicyValue(summary, safe, "configKey");
            copyPolicyValue(summary, safe, "rewritesRealSudoInvocations");
            copyPolicyValue(summary, safe, "stdinPasswordInjection");
            copyPolicyValue(summary, safe, "passwordRedacted");
            copyPolicyValue(summary, safe, "existingStdinFlagPreserved");
            copyPolicyValue(summary, safe, "commentsIgnored");
            copyPolicyValue(summary, safe, "quotedSudoIgnored");
            copyPolicyValue(summary, safe, "envAssignmentPrefixSupported");
            copyPolicyValue(summary, safe, "compoundCommandSupported");
            copyPolicyValue(summary, safe, "ptyDisabledForStdinPipe");
            copyPolicyValue(summary, safe, "missingPasswordHint");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的Background进程策略摘要。
     *
     * @return 返回safe Background进程策略Summary结果。
     */
    private Map<String, Object> safeBackgroundProcessPolicySummary() {
        return safeBackgroundProcessPolicySummary(false);
    }

    /**
     * 生成安全展示用的Background进程策略摘要。
     *
     * @param includeWrapperFamilies includeWrapperFamilies 参数。
     * @return 返回safe Background进程策略Summary结果。
     */
    private Map<String, Object> safeBackgroundProcessPolicySummary(boolean includeWrapperFamilies) {
        try {
            Map<String, Object> summary = ProcessTools.backgroundProcessPolicySummary(appConfig);
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "actions");
            copyPolicyValue(summary, safe, "processRegistryBacked");
            copyPolicyValue(summary, safe, "trackedSessionId");
            copyPolicyValue(summary, safe, "pidExposed");
            copyPolicyValue(summary, safe, "stdoutPreview");
            copyPolicyValue(summary, safe, "outputRedacted");
            copyPolicyValue(summary, safe, "completionEvents");
            copyPolicyValue(summary, safe, "stopSupported");
            copyPolicyValue(summary, safe, "stdinWriteSubmitCloseSupported");
            copyPolicyValue(summary, safe, "startDangerousCommandChecked");
            copyPolicyValue(summary, safe, "startHardlineBlocked");
            copyPolicyValue(summary, safe, "startPathPolicyChecked");
            copyPolicyValue(summary, safe, "startUrlPolicyChecked");
            copyPolicyValue(summary, safe, "currentThreadApprovalCanBypassStartCheck");
            copyPolicyValue(summary, safe, "stdinExecutionPayloadChecked");
            copyPolicyValue(summary, safe, "stdinExecutionTools");
            copyPolicyValue(summary, safe, "stdinPrivilegeWrapperDetection");
            if (summary.containsKey("stdinWrapperFamilies")) {
                safe.put(
                        "stdinPrivilegeWrapperFamilyCount",
                        Integer.valueOf(listSize(summary.get("stdinWrapperFamilies"))));
                if (includeWrapperFamilies) {
                    copyPolicyValue(summary, safe, "stdinWrapperFamilies");
                }
            }
            copyPolicyValue(summary, safe, "waitTimeoutClamped");
            copyPolicyValue(summary, safe, "processWaitTimeoutSeconds");
            copyPolicyValue(summary, safe, "managedBackgroundRequiredForLongRunningCommands");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的终端防护策略摘要。
     *
     * @return 返回safe终端防护策略Summary结果。
     */
    private Map<String, Object> safeTerminalGuardrailPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.terminalGuardrailPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "backgroundShellWrappersBlocked");
            copyPolicyValue(summary, safe, "detachedSessionLaunchersBlocked");
            copyPolicyValue(summary, safe, "powershellBackgroundCommandsBlocked");
            copyPolicyValue(summary, safe, "inlineAmpersandBlocked");
            copyPolicyValue(summary, safe, "trailingAmpersandBlocked");
            copyPolicyValue(summary, safe, "longLivedForegroundBlocked");
            copyPolicyValue(summary, safe, "longLivedForegroundPatternCount");
            copyPolicyValue(summary, safe, "longLivedForegroundSamples");
            copyPolicyValue(summary, safe, "appliesToTools");
            copyPolicyValue(summary, safe, "commandPathPrechecked");
            copyPolicyValue(summary, safe, "credentialPathPrechecked");
            copyPolicyValue(summary, safe, "downloadOutputPathPrechecked");
            copyPolicyValue(summary, safe, "downloadOutputDetachedOptionPrechecked");
            copyPolicyValue(summary, safe, "networkUploadSourcePathPrechecked");
            copyPolicyValue(summary, safe, "proxyUrlPrechecked");
            copyPolicyValue(summary, safe, "preproxyUrlPrechecked");
            copyPolicyValue(summary, safe, "systemDnsCommandPrechecked");
            copyPolicyValue(summary, safe, "systemProxyCommandPrechecked");
            copyPolicyValue(summary, safe, "windowsRegistryProxyCommandPrechecked");
            copyPolicyValue(summary, safe, "hostsAndResolverPathPrechecked");
            copyPolicyValue(summary, safe, "managedBackgroundProcessRequired");
            copyPolicyValue(summary, safe, "processRegistryBacked");
            copyPolicyValue(summary, safe, "sudoRewriteConfigured");
            copyPolicyValue(summary, safe, "sudoPasswordRedacted");
            copyPolicyValue(summary, safe, "powershellStartProcessRequiresWait");
            copyPolicyValue(summary, safe, "powershellStartProcessNoNewWindowNotEnough");
            copyPolicyValue(summary, safe, "powershellStartProcessPassThruNotEnough");
            copyPolicyValue(summary, safe, "codeToolShellExtractionCovered");
            copyPolicyValue(summary, safe, "codeToolShellSources");
            copyPolicyValue(summary, safe, "foregroundMaxTimeoutSeconds");
            copyPolicyValue(summary, safe, "foregroundMaxRetries");
            copyPolicyValue(summary, safe, "foregroundRetryBaseDelaySeconds");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的URL策略摘要。
     *
     * @return 返回safe URL策略Summary结果。
     */
    private Map<String, Object> safeUrlPolicySummary() {
        if (securityPolicyService == null) {
            return unavailablePolicy("url policy service is unavailable");
        }
        try {
            Map<String, Object> summary = securityPolicyService.urlPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "allowPrivateUrls");
            copyPolicyValue(summary, safe, "alwaysBlockedHostCount");
            copyPolicyValue(summary, safe, "alwaysBlockedIpCount");
            copyPolicyValue(summary, safe, "trustedPrivateIpHostCount");
            copyPolicyValue(summary, safe, "sensitiveQueryNameCount");
            copyPolicyValue(summary, safe, "websiteBlocklistEnabled");
            copyPolicyValue(summary, safe, "websiteBlocklistDomainCount");
            copyPolicyValue(summary, safe, "websiteBlocklistSharedFileCount");
            copyPolicyValue(summary, safe, "websiteBlocklistSharedRuleCount");
            copyPolicyValue(summary, safe, "websiteBlocklistLoadedSharedFileCount");
            copyPolicyValue(summary, safe, "websiteBlocklistSkippedSharedFileCount");
            copyPolicyValue(summary, safe, "allowedNetworkSchemes");
            copyPolicyValue(summary, safe, "unsupportedNetworkSchemeBlocked");
            copyPolicyValue(summary, safe, "protocolRelativeUrlChecked");
            copyPolicyValue(summary, safe, "schemelessHostChecked");
            copyPolicyValue(summary, safe, "percentEncodedHostChecked");
            copyPolicyValue(summary, safe, "idnHostNormalized");
            copyPolicyValue(summary, safe, "dnsResolutionRequired");
            copyPolicyValue(summary, safe, "systemDnsCommandChecked");
            copyPolicyValue(summary, safe, "powershellProxyEnvironmentChecked");
            copyPolicyValue(summary, safe, "setxProxyEnvironmentChecked");
            copyPolicyValue(summary, safe, "systemProxyCommandChecked");
            copyPolicyValue(summary, safe, "windowsRegistryProxyCommandChecked");
            copyPolicyValue(summary, safe, "proxyBypassEnvironmentChecked");
            copyPolicyValue(summary, safe, "gitPersistentProxyConfigChecked");
            copyPolicyValue(summary, safe, "packageManagerProxyBypassEnvironmentChecked");
            copyPolicyValue(summary, safe, "packageManagerPersistentProxyConfigChecked");
            copyPolicyValue(summary, safe, "userinfoBlocked");
            copyPolicyValue(summary, safe, "sensitiveQueryBlocked");
            copyPolicyValue(summary, safe, "schemelessSensitiveQueryBlocked");
            copyPolicyValue(summary, safe, "sensitiveQueryNameAliasNormalized");
            copyPolicyValue(summary, safe, "encodedSensitiveQueryBlocked");
            copyPolicyValue(summary, safe, "repeatedEncodedSensitiveQueryBlocked");
            copyPolicyValue(summary, safe, "semicolonSensitiveQueryBlocked");
            copyPolicyValue(summary, safe, "fragmentSensitiveQueryBlocked");
            copyPolicyValue(summary, safe, "sensitivePathCredentialBlocked");
            copyPolicyValue(summary, safe, "cloudMetadataBlocked");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的私有 URL策略摘要。
     *
     * @return 返回safe私有 URL策略Summary结果。
     */
    private Map<String, Object> safePrivateUrlPolicySummary() {
        if (securityPolicyService == null) {
            return unavailablePolicy("url policy service is unavailable");
        }
        try {
            Map<String, Object> summary = securityPolicyService.privateUrlPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "allowPrivateUrls");
            copyPolicyValue(summary, safe, "environmentOverrideName");
            copyPolicyValue(summary, safe, "cloudMetadataAlwaysBlocked");
            copyPolicyValue(summary, safe, "dnsResolutionRequired");
            copyPolicyValue(summary, safe, "obfuscatedIpv4Checked");
            copyPolicyValue(summary, safe, "percentEncodedHostChecked");
            copyPolicyValue(summary, safe, "ipv4MappedIpv6Checked");
            copyPolicyValue(summary, safe, "loopbackBlocked");
            copyPolicyValue(summary, safe, "linkLocalBlocked");
            copyPolicyValue(summary, safe, "siteLocalBlocked");
            copyPolicyValue(summary, safe, "multicastBlocked");
            copyPolicyValue(summary, safe, "reservedDocumentationRangesBlocked");
            copyPolicyValue(summary, safe, "trustedPrivateIpHostCount");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的网站策略摘要。
     *
     * @return 返回safe Website策略Summary结果。
     */
    private Map<String, Object> safeWebsitePolicySummary() {
        if (securityPolicyService == null) {
            return unavailablePolicy("website policy service is unavailable");
        }
        try {
            Map<String, Object> summary = securityPolicyService.websitePolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "configuredDomainCount");
            copyPolicyValue(summary, safe, "sharedFileCount");
            copyPolicyValue(summary, safe, "loadedSharedFileCount");
            copyPolicyValue(summary, safe, "skippedSharedFileCount");
            copyPolicyValue(summary, safe, "sharedRuleCount");
            copyPolicyValue(summary, safe, "hostRuleNormalization");
            copyPolicyValue(summary, safe, "wildcardSubdomainSupported");
            copyPolicyValue(summary, safe, "schemeAndPathIgnoredForRules");
            copyPolicyValue(summary, safe, "wwwPrefixIgnored");
            copyPolicyValue(summary, safe, "sharedFilePathSafetyChecked");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的路径策略摘要。
     *
     * @return 返回safe路径策略Summary结果。
     */
    private Map<String, Object> safePathPolicySummary() {
        if (securityPolicyService == null) {
            return unavailablePolicy("security policy service is unavailable");
        }
        try {
            Map<String, Object> summary = securityPolicyService.pathPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "traversalBlocked");
            copyPolicyValue(summary, safe, "controlCharactersBlocked");
            copyPolicyValue(summary, safe, "rawControlCharactersBlocked");
            copyPolicyValue(summary, safe, "normalizedControlCharactersBlocked");
            copyPolicyValue(summary, safe, "devicePathBlocked");
            copyPolicyValue(summary, safe, "rawBlockDeviceWriteBlocked");
            copyPolicyValue(summary, safe, "credentialPathReadBlocked");
            copyPolicyValue(summary, safe, "credentialPathWriteBlocked");
            copyPolicyValue(summary, safe, "projectEnvFileWriteBlocked");
            copyPolicyValue(summary, safe, "skillsHubInternalReadBlocked");
            copyPolicyValue(summary, safe, "skillsHubInternalWriteBlocked");
            copyPolicyValue(summary, safe, "localManagementSocketReadBlocked");
            copyPolicyValue(summary, safe, "localManagementSocketWriteBlocked");
            copyPolicyValue(summary, safe, "localManagementSocketAccessBlocked");
            copyPolicyValue(summary, safe, "localManagementSocketEnvironmentBlocked");
            copyPolicyValue(summary, safe, "localManagementPipeReadBlocked");
            copyPolicyValue(summary, safe, "localManagementPipeWriteBlocked");
            copyPolicyValue(summary, safe, "localManagementPipeAccessBlocked");
            copyPolicyValue(summary, safe, "workspaceWriteFree");
            copyPolicyValue(summary, safe, "outsideWorkspaceReadFree");
            copyPolicyValue(summary, safe, "outsideWorkspaceWriteApprovalRequired");
            copyPolicyValue(summary, safe, "writeDeniedExactPathCount");
            copyPolicyValue(summary, safe, "writeDeniedPrefixCount");
            copyPolicyValue(summary, safe, "writeDeniedHomeFileCount");
            copyPolicyValue(summary, safe, "blockedDevicePathCount");
            copyPolicyValue(summary, safe, "localManagementSocketPathCount");
            copyPolicyValue(summary, safe, "localManagementPipePathCount");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的凭据策略摘要。
     *
     * @return 返回safe凭据策略Summary结果。
     */
    private Map<String, Object> safeCredentialPolicySummary() {
        if (securityPolicyService == null) {
            return unavailablePolicy("security policy service is unavailable");
        }
        try {
            Map<String, Object> summary = securityPolicyService.credentialPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "directorySegmentCount");
            copyPolicyValue(summary, safe, "fileNameCount");
            copyPolicyValue(summary, safe, "pathSuffixCount");
            copyPolicyValue(summary, safe, "keyFileExtensionCount");
            copyPolicyValue(summary, safe, "keyFileMarkerCount");
            copyPolicyValue(summary, safe, "configuredCredentialFileCount");
            copyPolicyValue(summary, safe, "envExampleFilesAllowed");
            copyPolicyValue(summary, safe, "projectEnvFileReadBlocked");
            copyPolicyValue(summary, safe, "projectEnvFileWriteBlocked");
            copyPolicyValue(summary, safe, "credentialPathReadBlocked");
            copyPolicyValue(summary, safe, "credentialPathWriteBlocked");
            copyPolicyValue(summary, safe, "writePolicySharesCredentialClassifier");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的工具参数策略摘要。
     *
     * @return 返回safe工具参数策略Summary结果。
     */
    private Map<String, Object> safeToolArgsPolicySummary() {
        if (securityPolicyService == null) {
            return unavailablePolicy("security policy service is unavailable");
        }
        try {
            Map<String, Object> summary = securityPolicyService.toolArgsPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "recursiveUrlExtraction");
            copyPolicyValue(summary, safe, "returnedContentUrlExtraction");
            copyPolicyValue(summary, safe, "returnedSchemelessUrlChecked");
            copyPolicyValue(summary, safe, "returnedDocumentContentChecked");
            copyPolicyValue(summary, safe, "returnedDocumentMetadataUrlChecked");
            copyPolicyValue(summary, safe, "returnedPojoUrlChecked");
            copyPolicyValue(summary, safe, "recursivePathExtraction");
            copyPolicyValue(summary, safe, "encodedUrlParameterPolicyInherited");
            copyPolicyValue(summary, safe, "rawPathControlCharacterPolicyInherited");
            copyPolicyValue(summary, safe, "writeIntentDetection");
            copyPolicyValue(summary, safe, "patchTargetExtraction");
            copyPolicyValue(summary, safe, "downloadOutputPathOptionChecked");
            copyPolicyValue(summary, safe, "downloadOutputDetachedOptionChecked");
            copyPolicyValue(summary, safe, "networkUploadSourcePathChecked");
            copyPolicyValue(summary, safe, "networkUploadCredentialOnlyBlocked");
            copyPolicyValue(summary, safe, "proxyOptionUrlChecked");
            copyPolicyValue(summary, safe, "preproxyOptionUrlChecked");
            copyPolicyValue(summary, safe, "systemDnsCommandChecked");
            copyPolicyValue(summary, safe, "powershellProxyEnvironmentChecked");
            copyPolicyValue(summary, safe, "setxProxyEnvironmentChecked");
            copyPolicyValue(summary, safe, "systemProxyCommandChecked");
            copyPolicyValue(summary, safe, "windowsRegistryProxyCommandChecked");
            copyPolicyValue(summary, safe, "proxyBypassEnvironmentChecked");
            copyPolicyValue(summary, safe, "gitPersistentProxyConfigChecked");
            copyPolicyValue(summary, safe, "packageManagerProxyBypassEnvironmentChecked");
            copyPolicyValue(summary, safe, "packageManagerPersistentProxyConfigChecked");
            copyPolicyValue(summary, safe, "unsupportedNetworkSchemeChecked");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的Tirith策略摘要。
     *
     * @return 返回safe Tirith策略Summary结果。
     */
    private Map<String, Object> safeTirithPolicySummary() {
        if (tirithSecurityService == null) {
            return unavailablePolicy("tirith security service is unavailable");
        }
        try {
            Map<String, Object> summary = tirithSecurityService.policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "configured");
            copyPolicyValue(summary, safe, "available");
            copyPolicyValue(summary, safe, "timeoutSeconds");
            copyPolicyValue(summary, safe, "failOpen");
            copyPolicyValue(summary, safe, "scannerState");
            copyPolicyValue(summary, safe, "failureMode");
            copyPolicyValue(summary, safe, "failureBehavior");
            copyPolicyValue(summary, safe, "diagnosticSummary");
            copyPolicyValue(summary, safe, "auditSurface");
            copyPolicyValue(summary, safe, "lastAuditAvailable");
            copyPolicyValue(summary, safe, "lastAudit");
            copyPolicyValue(summary, safe, "sampleAudit");
            copyPolicyValue(summary, safe, "actions");
            copyPolicyValue(summary, safe, "warnRequiresApproval");
            copyPolicyValue(summary, safe, "blockRequiresApproval");
            copyPolicyValue(summary, safe, "commandPassedAsSingleArgument");
            copyPolicyValue(summary, safe, "nonInteractiveMode");
            copyPolicyValue(summary, safe, "jsonOutputMode");
            copyPolicyValue(summary, safe, "subprocessEnvironmentSanitized");
            copyPolicyValue(summary, safe, "timeoutKillsProcess");
            copyPolicyValue(summary, safe, "stdoutStderrCollectedSeparately");
            copyPolicyValue(summary, safe, "exitCodeZeroAllows");
            copyPolicyValue(summary, safe, "exitCodeOneBlocks");
            copyPolicyValue(summary, safe, "exitCodeTwoWarns");
            copyPolicyValue(summary, safe, "unexpectedExitCodeUsesFailureMode");
            copyPolicyValue(summary, safe, "parseFailureKeepsDecision");
            copyPolicyValue(summary, safe, "toolShellDetectionApplied");
            copyPolicyValue(summary, safe, "findingLimit");
            copyPolicyValue(summary, safe, "summaryLimit");
            copyPolicyValue(summary, safe, "secretRedaction");
            copyPolicyValue(summary, safe, "redactedSummaryFields");
            copyPolicyValue(summary, safe, "rawConfiguredPathExposed");
            copyPolicyValue(summary, safe, "rawResolvedPathExposed");
            copyPolicyValue(summary, safe, "rawFindingsExposed");
            copyPolicyValue(summary, safe, "rawCommandExposed");
            copyPolicyValue(summary, safe, "rawPathExposed");
            copyPolicyValue(summary, safe, "lastAuditRedacted");
            copyPolicyValue(summary, safe, "sampleAuditRedacted");
            copyPolicyValue(summary, safe, "shellDetection");
            copyPolicyValue(summary, safe, "failOpenMode");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 生成安全展示用的Tirith诊断。
     *
     * @return 返回safe Tirith诊断结果。
     */
    private Map<String, Object> safeTirithDiagnostic() {
        if (tirithSecurityService == null) {
            return unavailablePolicy("tirith security service is unavailable");
        }
        try {
            return tirithSecurityService.diagnose().toMap();
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    /**
     * 执行unavailable策略相关逻辑。
     *
     * @param e 捕获到的异常。
     * @return 返回unavailable策略结果。
     */
    private Map<String, Object> unavailablePolicy(Exception e) {
        return unavailablePolicy(
                StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
    }

    /**
     * 执行unavailable策略相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回unavailable策略结果。
     */
    private Map<String, Object> unavailablePolicy(String message) {
        Map<String, Object> fallback = new LinkedHashMap<String, Object>();
        fallback.put("available", Boolean.FALSE);
        fallback.put("summary", SecretRedactor.redact(message, 1000));
        return fallback;
    }

    /**
     * 复制策略Value。
     *
     * @param source 来源参数。
     * @param target target 参数。
     * @param key 配置键或映射键。
     */
    private static void copyPolicyValue(
            Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    /**
     * 列出大小。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回大小列表。
     */
    private int listSize(Object value) {
        if (!(value instanceof Iterable)) {
            return 0;
        }
        int count = 0;
        for (Object ignored : (Iterable<?>) value) {
            count++;
        }
        return count;
    }

    /**
     * 生成安全展示用的安全审计结果。
     *
     * @param result 结果响应或执行结果。
     * @return 返回safe安全审计结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> safeSecurityAuditResult(Map<String, Object> result) {
        if (result == null) {
            return result;
        }
        Object action = result.get("action");
        if (!("policy".equals(action) || "status".equals(action))) {
            return result;
        }
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        safe.put("success", Boolean.valueOf("success".equals(result.get("status"))));
        copyPolicyValue(result, safe, "status");
        copyPolicyValue(result, safe, "success");
        copyPolicyValue(result, safe, "action");
        copyPolicyValue(result, safe, "decision");
        copyPolicyValue(result, safe, "blocking");
        copyPolicyValue(result, safe, "approval_required");
        copyPolicyValue(result, safe, "summary");
        copyPolicyValue(result, safe, "timestamp");
        Object policy = result.get("policy");
        if (policy instanceof Map) {
            safe.put("policy", safeSecurityAuditPolicy((Map<String, Object>) policy));
        }
        return safe;
    }

    /**
     * 生成安全展示用的安全审计策略。
     *
     * @param policy 策略参数。
     * @return 返回safe安全审计策略结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> safeSecurityAuditPolicy(Map<String, Object> policy) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        Object approvals = policy.get("approvals");
        if (approvals instanceof Map) {
            safe.put("approvals", safeSecurityAuditApprovals((Map<String, Object>) approvals));
        }
        Object security = policy.get("security");
        if (security instanceof Map) {
            safe.put("security", safeSecurityAuditSecurity((Map<String, Object>) security));
        }
        Object terminal = policy.get("terminal");
        if (terminal instanceof Map) {
            safe.put("terminal", safeSecurityAuditTerminal((Map<String, Object>) terminal));
        }
        Object coverage = policy.get("coverage");
        if (coverage instanceof Map) {
            safe.put("coverage", safeSecurityAuditCoverage((Map<String, Object>) coverage));
        }
        copyPolicyValue(policy, safe, "activeSurfaces");
        return safe;
    }

    /**
     * 生成安全展示用的安全审计Approvals。
     *
     * @param approvals approvals 参数。
     * @return 返回safe安全审计Approvals结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> safeSecurityAuditApprovals(Map<String, Object> approvals) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        copyPolicyValue(approvals, safe, "guardrailMode");
        copyPolicyValue(approvals, safe, "smartMode");
        copyPolicyValue(approvals, safe, "smartJudgeConfigured");
        copyPolicyValue(approvals, safe, "smartApprovalActive");
        copyPolicyValue(approvals, safe, "smartCoversTirith");
        copyPolicyValue(approvals, safe, "guardrailCronMode");
        copyPolicyValue(approvals, safe, "cronAutoApprove");
        copyPolicyValue(approvals, safe, "subagentAutoApprove");
        copyPolicyValue(approvals, safe, "subagentApprovalDefault");
        copyPolicyValue(approvals, safe, "timeoutSeconds");
        copyPolicyValue(approvals, safe, "gatewayTimeoutSeconds");
        copyPolicyValue(approvals, safe, "mcpReloadConfirm");
        copyPolicyValue(approvals, safe, "mcpReloadConfirmationDefault");
        copyPolicyValue(approvals, safe, "alwaysApprovalCount");
        if (approvals.get("approvalPolicy") instanceof Map) {
            safe.put("approvalPolicy", safeApprovalPolicySummary());
        }
        if (approvals.get("cronApprovalPolicy") instanceof Map) {
            safe.put("cronApprovalPolicy", safeCronApprovalPolicySummary());
        }
        if (approvals.get("subagentApprovalPolicy") instanceof Map) {
            safe.put("subagentApprovalPolicy", safeSubagentApprovalPolicySummary());
        }
        if (approvals.get("smartApprovalPolicy") instanceof Map) {
            safe.put("smartApprovalPolicy", safeSmartApprovalPolicySummary());
        }
        if (approvals.get("tirithApprovalPolicy") instanceof Map) {
            safe.put("tirithApprovalPolicy", safeTirithApprovalPolicySummary());
        }
        if (approvals.get("slashConfirmPolicy") instanceof Map) {
            safe.put(
                    "slashConfirmPolicy",
                    safeSlashConfirmPolicy(
                            (Map<String, Object>) approvals.get("slashConfirmPolicy")));
        }
        if (approvals.get("approvalCardPolicy") instanceof Map) {
            safe.put(
                    "approvalCardPolicy",
                    safeApprovalCardPolicy(
                            (Map<String, Object>) approvals.get("approvalCardPolicy")));
        }
        if (approvals.get("auditLogPolicy") instanceof Map) {
            safe.put(
                    "auditLogPolicy",
                    safeApprovalAuditPolicy((Map<String, Object>) approvals.get("auditLogPolicy")));
        }
        if (approvals.get("mcpReloadPolicy") instanceof Map) {
            safe.put(
                    "mcpReloadPolicy",
                    safeMcpReloadPolicy((Map<String, Object>) approvals.get("mcpReloadPolicy")));
        }
        return safe;
    }

    /**
     * 生成安全展示用的安全审计安全。
     *
     * @param security 待校验或访问的地址参数。
     * @return 返回safe安全审计安全结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> safeSecurityAuditSecurity(Map<String, Object> security) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        copyPolicyValue(security, safe, "allowPrivateUrls");
        if (security.get("urlPolicy") instanceof Map) {
            safe.put("urlPolicy", safeUrlPolicySummary());
        }
        copyPolicyValue(security, safe, "tirithEnabled");
        copyPolicyValue(security, safe, "tirithConfigured");
        copyPolicyValue(security, safe, "tirithTimeoutSeconds");
        copyPolicyValue(security, safe, "tirithFailOpen");
        copyPolicyValue(security, safe, "tirithAvailable");
        if (security.get("tirithDiagnostic") instanceof Map) {
            safe.put("tirithDiagnostic", safeTirithDiagnostic());
        }
        if (security.get("tirithPolicy") instanceof Map) {
            safe.put("tirithPolicy", safeTirithPolicySummary());
        }
        copyPolicyValue(security, safe, "websiteBlocklistEnabled");
        copyPolicyValue(security, safe, "websiteBlocklistDomainCount");
        copyPolicyValue(security, safe, "websiteBlocklistSharedFileCount");
        copyPolicyValue(security, safe, "websiteBlocklistSharedRuleCount");
        copyPolicyValue(security, safe, "websiteBlocklistLoadedSharedFileCount");
        copyPolicyValue(security, safe, "websiteBlocklistSkippedSharedFileCount");
        return safe;
    }

    /**
     * 生成安全展示用的安全审计终端。
     *
     * @param terminal 终端参数。
     * @return 返回safe安全审计终端结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> safeSecurityAuditTerminal(Map<String, Object> terminal) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        copyPolicyValue(terminal, safe, "credentialFileCount");
        if (terminal.get("credentialPolicy") instanceof Map) {
            safe.put("credentialPolicy", safeCredentialPolicySummary());
        }
        if (terminal.get("pathPolicy") instanceof Map) {
            safe.put("pathPolicy", safePathPolicySummary());
        }
        if (terminal.get("credentialMountPolicy") instanceof Map) {
            safe.put("credentialMountPolicy", safeCredentialFilePolicySummary());
        }
        copyPolicyValue(terminal, safe, "envPassthroughCount");
        copyPolicyValue(terminal, safe, "sudoPasswordConfigured");
        if (terminal.get("sudoRewritePolicy") instanceof Map) {
            safe.put("sudoRewritePolicy", safeSudoRewritePolicySummary());
        }
        if (terminal.get("terminalOutputPolicy") instanceof Map) {
            safe.put("terminalOutputPolicy", safeTerminalOutputPolicySummary());
        }
        copyPolicyValue(terminal, safe, "workspaceWriteFree");
        copyPolicyValue(terminal, safe, "outsideWorkspaceReadFree");
        copyPolicyValue(terminal, safe, "outsideWorkspaceWriteApprovalRequired");
        if (terminal.get("terminalGuardrailPolicy") instanceof Map) {
            safe.put("terminalGuardrailPolicy", safeTerminalGuardrailPolicySummary());
        }
        if (terminal.get("backgroundProcessPolicy") instanceof Map) {
            safe.put("backgroundProcessPolicy", safeBackgroundProcessPolicySummary());
        }
        copyPolicyValue(terminal, safe, "maxForegroundTimeoutSeconds");
        copyPolicyValue(terminal, safe, "foregroundMaxRetries");
        copyPolicyValue(terminal, safe, "foregroundRetryBaseDelaySeconds");
        return safe;
    }

    /**
     * 生成安全展示用的安全审计Coverage。
     *
     * @param coverage coverage 参数。
     * @return 返回safe安全审计Coverage结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> safeSecurityAuditCoverage(Map<String, Object> coverage) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        if (coverage.get("urlPolicyDetails") instanceof Map) {
            safe.put("urlPolicyDetails", safeUrlPolicySummary());
        }
        if (coverage.get("privateUrlPolicyDetails") instanceof Map) {
            safe.put("privateUrlPolicyDetails", safePrivateUrlPolicySummary());
        }
        if (coverage.get("websitePolicyDetails") instanceof Map) {
            safe.put("websitePolicyDetails", safeWebsitePolicySummary());
        }
        if (coverage.get("pathPolicyDetails") instanceof Map) {
            safe.put("pathPolicyDetails", safePathPolicySummary());
        }
        if (coverage.get("credentialPolicyDetails") instanceof Map) {
            safe.put("credentialPolicyDetails", safeCredentialPolicySummary());
        }
        if (coverage.get("credentialMountPolicyDetails") instanceof Map) {
            safe.put("credentialMountPolicyDetails", safeCredentialFilePolicySummary());
        }
        if (coverage.get("toolArgsPolicy") instanceof Map) {
            safe.put("toolArgsPolicy", safeToolArgsPolicySummary());
        }
        if (coverage.get("schemaSanitizerPolicy") instanceof Map) {
            safe.put("schemaSanitizerPolicy", safeSchemaSanitizerPolicySummary());
        }
        if (coverage.get("patchParserPolicy") instanceof Map) {
            safe.put("patchParserPolicy", safePatchParserPolicySummary());
        }
        if (coverage.get("readOnlyAuditPolicy") instanceof Map) {
            safe.put("readOnlyAuditPolicy", SecurityAuditTools.readOnlyAuditPolicySummary());
        }
        if (coverage.get("subprocessEnvironmentPolicy") instanceof Map) {
            safe.put("subprocessEnvironmentPolicy", safeSubprocessEnvironmentPolicySummary());
        }
        if (coverage.get("codeExecutionPolicy") instanceof Map) {
            safe.put("codeExecutionPolicy", safeCodeExecutionPolicySummary());
        }
        if (coverage.get("mcpRuntimePolicy") instanceof Map) {
            safe.put("mcpRuntimePolicy", safeMcpRuntimePolicySummary());
        }
        if (coverage.get("mcpOAuthPolicy") instanceof Map) {
            safe.put("mcpOAuthPolicy", safeMcpOAuthPolicySummary());
        }
        if (coverage.get("mcpPackageSecurityPolicy") instanceof Map) {
            safe.put("mcpPackageSecurityPolicy", safeMcpPackageSecurityPolicySummary());
        }
        if (coverage.get("attachmentPolicy") instanceof Map) {
            safe.put(
                    "attachmentPolicy",
                    safeSecurityAuditAttachmentPolicy(
                            (Map<String, Object>) coverage.get("attachmentPolicy")));
        }
        if (coverage.get("toolResultStoragePolicy") instanceof Map) {
            safe.put("toolResultStoragePolicy", safeToolResultStoragePolicySummary());
        }
        if (coverage.get("dangerousCommandApprovalPolicy") instanceof Map) {
            safe.put("dangerousCommandApprovalPolicy", safeApprovalPolicySummary());
        }
        if (coverage.get("hardlinePolicy") instanceof Map) {
            safe.put("hardlinePolicy", safeHardlinePolicySummary());
        }
        if (coverage.get("terminalGuardrailPolicy") instanceof Map) {
            safe.put("terminalGuardrailPolicy", safeTerminalGuardrailPolicySummary());
        }
        if (coverage.get("smartApprovalPolicy") instanceof Map) {
            safe.put("smartApprovalPolicy", safeSmartApprovalPolicySummary());
        }
        if (coverage.get("tirithApprovalPolicy") instanceof Map) {
            safe.put("tirithApprovalPolicy", safeTirithApprovalPolicySummary());
        }
        if (coverage.get("cronApprovalPolicyDetails") instanceof Map) {
            safe.put("cronApprovalPolicyDetails", safeCronApprovalPolicySummary());
        }
        if (coverage.get("subagentApprovalPolicyDetails") instanceof Map) {
            safe.put("subagentApprovalPolicyDetails", safeSubagentApprovalPolicySummary());
        }
        if (coverage.get("sudoRewritePolicy") instanceof Map) {
            safe.put("sudoRewritePolicy", safeSudoRewritePolicySummary());
        }
        if (coverage.get("terminalOutputPolicy") instanceof Map) {
            safe.put("terminalOutputPolicy", safeTerminalOutputPolicySummary());
        }
        if (coverage.get("backgroundProcessPolicy") instanceof Map) {
            safe.put("backgroundProcessPolicy", safeBackgroundProcessPolicySummary(true));
        }
        if (coverage.get("tirithPolicy") instanceof Map) {
            safe.put("tirithPolicy", safeTirithPolicySummary());
        }
        if (coverage.get("approvalLifecyclePolicy") instanceof Map) {
            safe.put(
                    "approvalLifecyclePolicy",
                    safeApprovalLifecyclePolicy(
                            (Map<String, Object>) coverage.get("approvalLifecyclePolicy")));
        }
        if (coverage.get("slashConfirmPolicy") instanceof Map) {
            safe.put(
                    "slashConfirmPolicy",
                    safeSlashConfirmPolicy(
                            (Map<String, Object>) coverage.get("slashConfirmPolicy")));
        }
        if (coverage.get("approvalCardPolicy") instanceof Map) {
            safe.put(
                    "approvalCardPolicy",
                    safeApprovalCardPolicy(
                            (Map<String, Object>) coverage.get("approvalCardPolicy")));
        }
        if (coverage.get("approvalAuditPolicy") instanceof Map) {
            safe.put(
                    "approvalAuditPolicy",
                    safeApprovalAuditPolicy(
                            (Map<String, Object>) coverage.get("approvalAuditPolicy")));
        }
        if (coverage.get("mcpReloadPolicy") instanceof Map) {
            safe.put(
                    "mcpReloadPolicy",
                    safeMcpReloadPolicy((Map<String, Object>) coverage.get("mcpReloadPolicy")));
        }
        copyAuditCoverageBooleans(coverage, safe);
        return safe;
    }

    /**
     * 生成安全展示用的审批生命周期策略。
     *
     * @param source 来源参数。
     * @return 返回safe审批生命周期策略结果。
     */
    private Map<String, Object> safeApprovalLifecyclePolicy(Map<String, Object> source) {
        return filterPolicyMap(
                source,
                "pendingListPrunedBeforeRead",
                "selectorSupported",
                "selectorTokenPattern",
                "unsafeSelectorRejected",
                "listSupported",
                "approveAllSupported",
                "rejectAllSupported",
                "bulkRejectUsesSafeSelector",
                "clearSessionSupported",
                "clearAlwaysSupported",
                "clearAllSupported",
                "scopes",
                "alwaysScopeUsesGlobalSettings",
                "tirithAlwaysScopeDowngradedToSession",
                "currentThreadApprovalTtlMillis",
                "currentThreadApprovalEnabled",
                "approveRemovesPendingApproval",
                "rejectRemovesPendingApproval",
                "sessionSnapshotUpdated",
                "approvalRequestObserved",
                "approvalResponseObserved",
                "approverRedacted",
                "approvalKeyRedacted",
                "commandPreviewRedacted",
                "encodedUrlParameterRedacted");
    }

    /**
     * 生成安全展示用的斜杠命令Confirm策略。
     *
     * @param source 来源参数。
     * @return 返回safe Slash Confirm策略结果。
     */
    private Map<String, Object> safeSlashConfirmPolicy(Map<String, Object> source) {
        return filterPolicyMap(
                source,
                "commands",
                "selectorSupported",
                "selectorTokenPattern",
                "unsafeSelectorRejected",
                "listSupported",
                "approveAllSupported",
                "denyAllSupported",
                "clearSessionSupported",
                "clearAlwaysSupported",
                "clearAllSupported",
                "scopes",
                "defaultScope",
                "managementCommands",
                "pendingQueueSupported",
                "pendingListHidesApprovalKey",
                "pendingListUsesSafeSelector",
                "pendingListShowsPatternKey",
                "sessionApprovalListShowsCountOnly",
                "alwaysApprovalListShowsCountOnly",
                "approvalCardDeliveryMode",
                "approvalCardPlatforms",
                "approvalCardActionKey",
                "approvalCardApproveAction",
                "approvalCardDenyAction",
                "approvalCardScopeKey",
                "approvalCardApprovalIdKey",
                "permanentApprovalAllowedExceptTirith",
                "tirithAlwaysDowngradedToSession",
                "approverRedacted",
                "commandPreviewRedacted",
                "encodedUrlParameterRedacted",
                "approvalMetadataRedacted",
                "observerEventsRedacted",
                "approvalTimeoutSeconds",
                "gatewayTimeoutSeconds");
    }

    /**
     * 生成安全展示用的审批卡片策略。
     *
     * @param source 来源参数。
     * @return 返回safe审批Card策略结果。
     */
    private Map<String, Object> safeApprovalCardPolicy(Map<String, Object> source) {
        return filterPolicyMap(
                source,
                "deliveryMode",
                "supportedPlatforms",
                "unsupportedPlatformsReturnEmptyExtras",
                "actionKey",
                "approveAction",
                "denyAction",
                "scopeKey",
                "approvalIdKey",
                "scopeOptions",
                "defaultScope",
                "approvalIdSelectorSupported",
                "selectorTokenPattern",
                "unsafeSelectorRejected",
                "outboundApprovalIdSanitized",
                "unsafeApprovalIdFallsBackToKeySelector",
                "approveCommandGenerated",
                "denyCommandGenerated",
                "alwaysScopeCommandGenerated",
                "sessionScopeCommandGenerated",
                "domesticCardLabelsLocalized",
                "feishuChineseCardLabels",
                "qqbotSessionActionSupported",
                "tirithPermanentApprovalHidden",
                "commandPreviewRedacted",
                "descriptionPreviewRedacted",
                "toolNameRedacted",
                "commandPreviewRedactedInExtras",
                "rawCommandRedactedInExtras",
                "encodedUrlParameterRedacted",
                "encodedUrlParameterRedactedInExtras",
                "semicolonUrlParameterRedacted",
                "fragmentUrlParameterRedacted");
    }

    /**
     * 生成安全展示用的审批审计策略。
     *
     * @param source 来源参数。
     * @return 返回safe审批审计策略结果。
     */
    private Map<String, Object> safeApprovalAuditPolicy(Map<String, Object> source) {
        return filterPolicyMap(
                source,
                "observerCount",
                "requestEvents",
                "responseEvents",
                "eventTypes",
                "repositoryBackedWhenConfigured",
                "observerFailureIsolated",
                "approverRedacted",
                "commandPreviewRedacted",
                "descriptionRedacted",
                "approvalKeyRedacted",
                "encodedUrlParameterRedacted",
                "commandHashStored",
                "patternKeysStored",
                "timestampsStored",
                "recentDashboardViewSupported",
                "manualRevocationAudited");
    }

    /**
     * 生成安全展示用的MCPReload策略。
     *
     * @param source 来源参数。
     * @return 返回safe MCP Reload策略结果。
     */
    private Map<String, Object> safeMcpReloadPolicy(Map<String, Object> source) {
        return filterPolicyMap(
                source,
                "command",
                "confirmRequired",
                "configKey",
                "slashConfirmBacked",
                "directRunArgument",
                "alwaysConfirmArgument",
                "persistentDisableSupported",
                "runtimeConfigPersisted",
                "toolChangeNoticeInjected",
                "changedServerSummary",
                "toolCountSummary",
                "oauthUrlSafetyCovered",
                "encodedUrlParameterRedacted",
                "reloadHistoryNoticeRedacted");
    }

    /**
     * 生成安全展示用的安全审计附件策略。
     *
     * @param attachment 附件参数。
     * @return 返回safe安全审计附件策略结果。
     */
    private Map<String, Object> safeSecurityAuditAttachmentPolicy(Map<String, Object> attachment) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        if (attachment.get("downloadIo") instanceof Map) {
            safe.put("downloadIo", safeAttachmentDownloadPolicySummary());
        }
        if (attachment.get("mediaCache") instanceof Map) {
            safe.put("mediaCache", safeAttachmentMediaCachePolicySummary());
        }
        if (attachment.get("terminalPaste") instanceof Map) {
            safe.put("terminalPaste", safeAttachmentTerminalPastePolicySummary());
        }
        return safe;
    }

    /**
     * 复制审计Coverage Booleans。
     *
     * @param source 来源参数。
     * @param target target 参数。
     */
    private void copyAuditCoverageBooleans(Map<String, Object> source, Map<String, Object> target) {
        copyPolicyValue(source, target, "dangerousCommandApproval");
        copyPolicyValue(source, target, "configuredCredentialCommandPathApproval");
        copyPolicyValue(source, target, "slashApprovalConfirm");
        copyPolicyValue(source, target, "smartApproval");
        copyPolicyValue(source, target, "tirithSmartApproval");
        copyPolicyValue(source, target, "cronApprovalPolicy");
        copyPolicyValue(source, target, "subagentApprovalPolicy");
        copyPolicyValue(source, target, "approvalAuditLog");
        copyPolicyValue(source, target, "hardlineCommandBlocks");
        copyPolicyValue(source, target, "terminalGuardrails");
        copyPolicyValue(source, target, "sudoRewrite");
        copyPolicyValue(source, target, "backgroundProcessGuard");
        copyPolicyValue(source, target, "urlSafety");
        copyPolicyValue(source, target, "privateUrlPolicy");
        copyPolicyValue(source, target, "websitePolicy");
        copyPolicyValue(source, target, "credentialFilePolicy");
        copyPolicyValue(source, target, "credentialMountPolicy");
        copyPolicyValue(source, target, "pathSecurity");
        copyPolicyValue(source, target, "toolArgsSecurity");
        copyPolicyValue(source, target, "toolReturnedContentUrlSafety");
        copyPolicyValue(source, target, "schemaSanitizer");
        copyPolicyValue(source, target, "patchParser");
        copyPolicyValue(source, target, "subprocessEnvironmentSanitizer");
        copyPolicyValue(source, target, "toolResultStorage");
        copyPolicyValue(source, target, "codeExecutionGuardrails");
        copyPolicyValue(source, target, "codeExecutionPolicyAuditable");
        copyPolicyValue(source, target, "mcpUrlSafety");
        copyPolicyValue(source, target, "mcpReloadConfirmation");
        copyPolicyValue(source, target, "mcpToolChangeNotice");
        copyPolicyValue(source, target, "mcpRuntimePolicyAuditable");
        copyPolicyValue(source, target, "mcpPackageSecurity");
        copyPolicyValue(source, target, "attachmentUrlSafety");
        copyPolicyValue(source, target, "attachmentCachePathSafety");
        copyPolicyValue(source, target, "attachmentDisplayNameRedaction");
        copyPolicyValue(source, target, "terminalAttachmentPathSafety");
        copyPolicyValue(source, target, "terminalAttachmentPreviewRedaction");
        copyPolicyValue(source, target, "terminalAttachmentResolvedNameRedaction");
        copyPolicyValue(source, target, "tirithSecurity");
        copyPolicyValue(source, target, "readOnlyAuditTool");
    }

    /**
     * 执行过滤器策略映射相关逻辑。
     *
     * @param source 来源参数。
     * @param keys 候选键列表。
     * @return 返回filter策略Map结果。
     */
    private Map<String, Object> filterPolicyMap(Map<String, Object> source, String... keys) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        for (String key : keys) {
            copyPolicyValue(source, safe, key);
        }
        return safe;
    }

    /**
     * 执行安全审计策略相关逻辑。
     *
     * @return 返回安全审计策略结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> securityAuditPolicy() {
        Map<String, Object> fallback = new LinkedHashMap<String, Object>();
        try {
            Map<String, Object> result =
                    securityAudit(Collections.singletonMap("action", "policy"));
            Object policy = result.get("policy");
            if (policy instanceof Map) {
                return (Map<String, Object>) policy;
            }
            fallback.put("available", Boolean.FALSE);
            fallback.put(
                    "summary", SecretRedactor.redact(String.valueOf(result.get("summary")), 1000));
        } catch (Exception e) {
            fallback.put("available", Boolean.FALSE);
            fallback.put(
                    "summary",
                    SecretRedactor.redact(
                            StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                            1000));
        }
        return fallback;
    }

    /**
     * 执行安全策略Probes相关逻辑。
     *
     * @return 返回安全策略Probes结果。
     */
    private Map<String, Object> securityPolicyProbes() {
        return new DashboardSecurityProbeRunner(
                        appConfig,
                        approvalService,
                        securityPolicyService,
                        tirithSecurityService,
                        toolResultStorageService,
                        slashConfirmService)
                .securityPolicyProbes();
    }

    /**
     * 执行待恢复审批Item相关逻辑。
     *
     * @param session 会话参数。
     * @param pending 待恢复参数。
     * @return 返回pending审批Item结果。
     */
    private Map<String, Object> pendingApprovalItem(
            SessionRecord session, DangerousCommandApprovalService.PendingApproval pending) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("session_id", safeAuditPreview(session.getSessionId(), 240));
        item.put("source_ref", sourceRef(session.getSourceKey()));
        item.put(
                "title",
                safeAuditPreview(
                        StrUtil.blankToDefault(session.getTitle(), session.getSessionId()), 240));
        item.put("branch_name", safeAuditPreview(session.getBranchName(), 160));
        item.put("updated_at", Long.valueOf(session.getUpdatedAt()));
        String selector = DangerousCommandApprovalService.approvalSelector(pending);
        item.put("approval_id", selector);
        item.put("selector", selector);
        item.put("tool_name", safeAuditPreview(pending.getToolName(), 160));
        item.put("description", safeAuditPreview(pending.getDescription(), 1000));
        item.put("pattern_key", safeAuditPreview(pending.getPatternKey(), 400));
        item.put("pattern_keys", redactedTextList(pending.effectivePatternKeys(), 400));
        item.put("rule_sources", approvalRuleSources(pending));
        item.put("command_preview", safeAuditPreview(pending.getCommand(), 800));
        item.put("command_hash", redactedIdentifier(pending.getCommandHash()));
        item.put("created_at", Long.valueOf(pending.getCreatedAt()));
        item.put("expires_at", Long.valueOf(pending.getExpiresAt()));
        item.put("expires_in_seconds", Long.valueOf(expiresInSeconds(pending.getExpiresAt())));
        item.put("expired", Boolean.valueOf(isExpired(pending.getExpiresAt())));
        item.put(
                "scopes",
                pending.isPermanentApprovalAllowed() ? "once,session,always" : "once,session");
        item.put("scope_options", approvalScopeOptions(pending));
        item.put("permanent_allowed", Boolean.valueOf(pending.isPermanentApprovalAllowed()));
        item.put("permanent_disabled_reason", permanentDisabledReason(pending));
        return item;
    }

    /**
     * 执行环境变量Probe输入相关逻辑。
     *
     * @param names names 参数。
     * @return 返回env Probe输入结果。
     */
    private Map<String, String> envProbeInput(List<String> names) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (names == null) {
            return values;
        }
        for (String name : names) {
            if (StrUtil.isNotBlank(name)) {
                values.put(name, "__redacted__");
            }
        }
        return values;
    }

    /**
     * 执行环境变量ProbeNames相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回env Probe Names结果。
     */
    private List<String> envProbeNames(Object value) {
        List<String> values = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                addEnvProbeName(values, item == null ? null : String.valueOf(item));
            }
            return values;
        }
        String text =
                value == null ? "" : SecretRedactor.stripDisplayControls(String.valueOf(value));
        if (StrUtil.isBlank(text)) {
            return values;
        }
        for (String item : text.split("[,\\r\\n]+")) {
            addEnvProbeName(values, item);
        }
        return values;
    }

    /**
     * 追加环境变量Probe名称。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param raw 原始输入值。
     */
    private void addEnvProbeName(List<String> values, String raw) {
        String value = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(raw)).trim();
        if (value.length() > 0) {
            values.add(value);
        }
    }

    /**
     * 执行审批RuleSources相关逻辑。
     *
     * @param pending 待恢复参数。
     * @return 返回审批Rule Sources结果。
     */
    private List<String> approvalRuleSources(
            DangerousCommandApprovalService.PendingApproval pending) {
        List<String> sources = new ArrayList<String>();
        if (pending == null) {
            return sources;
        }
        for (String patternKey : pending.effectivePatternKeys()) {
            String source =
                    StrUtil.nullToEmpty(patternKey).startsWith("tirith:")
                            ? "security_scan"
                            : "local_policy";
            if (!sources.contains(source)) {
                sources.add(source);
            }
        }
        return sources;
    }

    /**
     * 执行permanentDisabled原因相关逻辑。
     *
     * @param pending 待恢复参数。
     * @return 返回permanent Disabled Reason结果。
     */
    private String permanentDisabledReason(
            DangerousCommandApprovalService.PendingApproval pending) {
        if (pending == null || pending.isPermanentApprovalAllowed()) {
            return "";
        }
        for (String patternKey : pending.effectivePatternKeys()) {
            if (StrUtil.nullToEmpty(patternKey).startsWith("tirith:")) {
                return "安全扫描命中项只能按本次或本会话审批，不能写入长期授权。";
            }
        }
        return "该审批项不允许长期授权。";
    }

    /**
     * 执行审批范围Options相关逻辑。
     *
     * @param pending 待恢复参数。
     * @return 返回审批范围Options结果。
     */
    private List<String> approvalScopeOptions(
            DangerousCommandApprovalService.PendingApproval pending) {
        List<String> scopes = new ArrayList<String>();
        scopes.add("once");
        scopes.add("session");
        if (pending != null && pending.isPermanentApprovalAllowed()) {
            scopes.add("always");
        }
        return scopes;
    }

    /**
     * 执行expiresInSeconds相关逻辑。
     *
     * @param expiresAt expiresAt 参数。
     * @return 返回expires In Seconds结果。
     */
    private long expiresInSeconds(long expiresAt) {
        if (expiresAt <= 0L) {
            return 0L;
        }
        long remaining = expiresAt - System.currentTimeMillis();
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    /**
     * 判断是否Expired。
     *
     * @param expiresAt expiresAt 参数。
     * @return 如果Expired满足条件则返回 true，否则返回 false。
     */
    private boolean isExpired(long expiresAt) {
        return expiresAt > 0L && expiresAt <= System.currentTimeMillis();
    }

    /**
     * 解析审批范围。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回解析后的审批范围。
     */
    private DangerousCommandApprovalService.ApprovalScope parseApprovalScope(String value) {
        String normalized = StrUtil.nullToEmpty(value).trim().toLowerCase();
        if ("always".equals(normalized)) {
            return DangerousCommandApprovalService.ApprovalScope.ALWAYS;
        }
        if ("session".equals(normalized)) {
            return DangerousCommandApprovalService.ApprovalScope.SESSION;
        }
        return DangerousCommandApprovalService.ApprovalScope.ONCE;
    }

    /**
     * 解析结果。
     *
     * @param success success 参数。
     * @param code code 参数。
     * @param message 平台消息或错误消息。
     * @param reply 回复参数。
     * @return 返回解析后的结果。
     */
    private Map<String, Object> resolveResult(
            boolean success, String code, String message, Map<String, Object> reply) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", Boolean.valueOf(success));
        result.put("code", code);
        result.put("message", safeAuditPreview(message, 1200));
        if (reply != null) {
            result.put("reply", reply);
        }
        return result;
    }

    /**
     * 执行disabled列表相关逻辑。
     *
     * @param items items 参数。
     * @param code code 参数。
     * @param message 平台消息或错误消息。
     * @return 返回disabled List结果。
     */
    private Map<String, Object> disabledList(
            List<Map<String, Object>> items, String code, String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(0));
        result.put("items", items == null ? Collections.<Map<String, Object>>emptyList() : items);
        result.put("available", Boolean.FALSE);
        result.put("code", code);
        result.put("message", message);
        return result;
    }

    /**
     * 执行回复映射相关逻辑。
     *
     * @param reply 回复参数。
     * @return 返回reply Map结果。
     */
    private Map<String, Object> replyMap(GatewayReply reply) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("session_id", safeAuditPreview(reply.getSessionId(), 240));
        map.put("branch_name", safeAuditPreview(reply.getBranchName(), 160));
        map.put("content", SecretRedactor.redact(reply.getContent(), 1200));
        map.put("error", Boolean.valueOf(reply.isError()));
        return map;
    }

    /**
     * 执行审批审计Item相关逻辑。
     *
     * @param event 事件参数。
     * @return 返回审批审计Item结果。
     */
    private Map<String, Object> approvalAuditItem(ApprovalAuditEvent event) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("event_id", safeAuditPreview(event.getEventId(), 120));
        item.put("session_id", safeAuditPreview(event.getSessionId(), 240));
        item.put("event_type", safeAuditPreview(event.getEventType(), 80));
        item.put("choice", safeAuditPreview(event.getChoice(), 80));
        item.put("outcome", safeAuditPreview(event.getOutcome(), 80));
        item.put("status", safeAuditPreview(event.getStatus(), 80));
        item.put("approved", Boolean.valueOf(event.isApproved()));
        item.put("approver", SecretRedactor.redact(event.getApprover(), 200));
        item.put("tool_name", safeAuditPreview(event.getToolName(), 160));
        item.put("command_hash", redactedIdentifier(event.getCommandHash()));
        item.put("command_preview", safeAuditPreview(event.getCommandPreview(), 800));
        item.put("description", safeAuditPreview(event.getDescription(), 1000));
        item.put("pattern_keys", redactedJsonList(event.getPatternKeysJson(), 400));
        item.put("created_at", Long.valueOf(event.getCreatedAt()));
        item.put("approval_created_at", Long.valueOf(event.getApprovalCreatedAt()));
        item.put("approval_expires_at", Long.valueOf(event.getApprovalExpiresAt()));
        return item;
    }

    /**
     * 执行always审批Item相关逻辑。
     *
     * @param approval 审批参数。
     * @return 返回always审批Item结果。
     */
    private Map<String, Object> alwaysApprovalItem(String approval) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        String value = StrUtil.nullToEmpty(approval);
        String toolName = "";
        String patternKey = "";
        int colon = value.indexOf(':');
        if (colon >= 0) {
            toolName = value.substring(0, colon);
            patternKey = value.substring(colon + 1);
        }
        item.put("approval_id", alwaysApprovalId(value));
        item.put("tool_name", safeAuditPreview(toolName, 160));
        item.put("pattern_key", safeAuditPreview(patternKey, 400));
        return item;
    }

    /**
     * 解析Always审批。
     *
     * @param approvalId 审批标识。
     * @return 返回解析后的Always审批。
     */
    private String resolveAlwaysApproval(String approvalId) {
        if (StrUtil.isNotBlank(approvalId) && approvalService != null) {
            for (String approval : approvalService.listAlwaysApprovals()) {
                if (alwaysApprovalId(approval).equals(approvalId.trim())) {
                    return approval;
                }
            }
        }
        return "";
    }

    /**
     * 执行always审批标识相关逻辑。
     *
     * @param approval 审批参数。
     * @return 返回always审批标识。
     */
    private String alwaysApprovalId(String approval) {
        String value = StrUtil.nullToEmpty(approval);
        return value.isEmpty() ? "" : SecureUtil.sha256(value).substring(0, 24);
    }

    /**
     * 追加Always审批Revoked审计。
     *
     * @param approval 审批参数。
     * @param approver approver 参数。
     */
    private void appendAlwaysApprovalRevokedAudit(String approval, String approver) {
        if (approvalAuditRepository == null) {
            return;
        }
        Map<String, Object> item = alwaysApprovalItem(approval);
        ApprovalAuditEvent audit = new ApprovalAuditEvent();
        audit.setEventId(IdSupport.newId());
        audit.setSessionId("");
        audit.setEventType("response");
        audit.setChoice("revoke");
        audit.setOutcome(DangerousCommandApprovalService.ApprovalResponseEvent.OUTCOME_REVOKED);
        audit.setStatus("revoked");
        audit.setApproved(false);
        audit.setApprover(SecretRedactor.redact(approver, 200));
        audit.setToolName(StrUtil.nullToEmpty(String.valueOf(item.get("tool_name"))));
        audit.setApprovalId("");
        audit.setApprovalKey(redactedApprovalKey(approval));
        audit.setCommandHash("");
        audit.setCommandPreview("");
        audit.setDescription("撤销长期审批授权");
        audit.setPatternKeysJson(
                ONode.serialize(Collections.singletonList(item.get("pattern_key"))));
        audit.setCreatedAt(System.currentTimeMillis());
        audit.setApprovalCreatedAt(0L);
        audit.setApprovalExpiresAt(0L);
        try {
            approvalAuditRepository.append(audit);
        } catch (Exception e) {
            log.warn(
                    "Dashboard always approval revoke audit persist failed; continuing revoke flow: {}",
                    diagnosticFailureSummary(e));
            // 审计持久化失败不能影响安全关键的审批处理。
        }
    }

    /**
     * 执行斜杠命令ConfirmItem相关逻辑。
     *
     * @param pending 待恢复参数。
     * @return 返回slash Confirm Item结果。
     */
    private Map<String, Object> slashConfirmItem(SlashConfirmService.PendingConfirm pending) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        long now = System.currentTimeMillis();
        long expiresAt = pending.getCreatedAt() + SlashConfirmService.DEFAULT_TIMEOUT_MS;
        long remainingMillis = expiresAt - now;
        boolean expired = remainingMillis <= 0L;
        List<String> actionOptions = new ArrayList<String>();
        if (!expired) {
            actionOptions.add("approve");
            actionOptions.add("deny");
            if (pending.isAllowAlways()) {
                actionOptions.add("always");
            }
        }
        item.put("confirm_id", safeAuditPreview(pending.getConfirmId(), 160));
        item.put("confirm_ref", shortId(pending.getConfirmId()));
        item.put("source_ref", sourceRef(pending.getSourceKey()));
        item.put("command_preview", safeAuditPreview(pending.getCommand(), 1000));
        item.put("prompt_preview", safeAuditPreview(pending.getPrompt(), 1000));
        item.put("allow_always", Boolean.valueOf(pending.isAllowAlways()));
        item.put("action_options", actionOptions);
        item.put("created_at", Long.valueOf(pending.getCreatedAt()));
        item.put("expires_at", Long.valueOf(expiresAt));
        item.put("expires_in_seconds", Long.valueOf(Math.max(0L, remainingMillis / 1000L)));
        item.put("expired", Boolean.valueOf(expired));
        return item;
    }

    /**
     * 查找Pending Slash Confirm。
     *
     * @param confirmId confirm标识。
     * @param fallbackSourceKey 兜底来源键标识或键值。
     * @return 返回Pending Slash Confirm结果。
     */
    private SlashConfirmService.PendingConfirm findPendingSlashConfirm(
            String confirmId, String fallbackSourceKey) {
        if (slashConfirmService == null) {
            return null;
        }
        String expected =
                SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(confirmId)).trim();
        String expectedSource =
                SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(fallbackSourceKey)).trim();
        if (StrUtil.isNotBlank(expectedSource)) {
            SlashConfirmService.PendingConfirm pending =
                    slashConfirmService.getPending(expectedSource);
            if (pending != null && StrUtil.equals(expected, pending.getConfirmId())) {
                return pending;
            }
            return null;
        }
        for (SlashConfirmService.PendingConfirm pending : slashConfirmService.listPending()) {
            if (StrUtil.equals(expected, pending.getConfirmId())) {
                return pending;
            }
        }
        return null;
    }

    /**
     * 执行短标识相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回short标识。
     */
    private String shortId(String value) {
        String safe = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(value)).trim();
        return safe.length() <= 8 ? safe : safe.substring(0, 8);
    }

    /**
     * 执行来源Ref相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回来源Ref结果。
     */
    private String sourceRef(String value) {
        String safe = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(value)).trim();
        return safe.isEmpty() ? "" : SecureUtil.sha256(safe).substring(0, 12);
    }

    /**
     * 执行斜杠命令Confirm命令行相关逻辑。
     *
     * @param action 操作参数。
     * @param confirmId confirm标识。
     * @return 返回slash Confirm命令Line结果。
     */
    private String slashConfirmCommandLine(String action, String confirmId) {
        if ("deny".equals(action)) {
            return "/deny " + StrUtil.nullToEmpty(confirmId);
        }
        if ("always".equals(action)) {
            return "/approve always " + StrUtil.nullToEmpty(confirmId);
        }
        return "/approve " + StrUtil.nullToEmpty(confirmId);
    }

    /**
     * 执行控制台消息相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param text 待处理文本。
     * @return 返回控制台消息结果。
     */
    private GatewayMessage dashboardMessage(String sourceKey, String text) {
        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "dashboard", "dashboard", text);
        message.setSourceKeyOverride(sourceKey);
        message.setUserName("dashboard");
        return message;
    }

    /**
     * 执行大小相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回大小结果。
     */
    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    /**
     * 执行文本相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @param key 配置键或映射键。
     * @return 返回text结果。
     */
    private String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : SecretRedactor.stripDisplayControls(String.valueOf(value));
    }

    /**
     * 执行bool相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @param key 配置键或映射键。
     * @return 返回bool结果。
     */
    private Boolean bool(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return null;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

}
