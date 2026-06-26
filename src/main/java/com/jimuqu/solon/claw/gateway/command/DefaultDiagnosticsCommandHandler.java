package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;
import com.jimuqu.solon.claw.tool.runtime.BrowserRuntimeService;
import com.jimuqu.solon.claw.web.DashboardSkillsService;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 处理工具集、浏览器运行时和 debug 诊断命令。 */
final class DefaultDiagnosticsCommandHandler {
    /** 应用配置，用于生成 debug 摘要。 */
    private final AppConfig appConfig;

    /** 工具注册表，用于统计工具数量。 */
    private final ToolRegistry toolRegistry;

    /** 会话仓储，用于统计会话数量。 */
    private final SessionRepository sessionRepository;

    /** 投递服务，用于读取渠道连接状态。 */
    private final DeliveryService deliveryService;

    /** Dashboard 技能服务，用于读取工具集。 */
    private final DashboardSkillsService dashboardSkillsService;

    /** 浏览器运行时服务，用于管理浏览器会话。 */
    private final BrowserRuntimeService browserRuntimeService;

    /**
     * 创建诊断命令处理器。
     *
     * @param appConfig 应用配置。
     * @param toolRegistry 工具注册表。
     * @param sessionRepository 会话仓储。
     * @param deliveryService 投递服务。
     * @param dashboardSkillsService Dashboard 技能服务。
     * @param browserRuntimeService 浏览器运行时服务。
     */
    DefaultDiagnosticsCommandHandler(
            AppConfig appConfig,
            ToolRegistry toolRegistry,
            SessionRepository sessionRepository,
            DeliveryService deliveryService,
            DashboardSkillsService dashboardSkillsService,
            BrowserRuntimeService browserRuntimeService) {
        this.appConfig = appConfig;
        this.toolRegistry = toolRegistry;
        this.sessionRepository = sessionRepository;
        this.deliveryService = deliveryService;
        this.dashboardSkillsService = dashboardSkillsService;
        this.browserRuntimeService = browserRuntimeService;
    }

    /**
     * 执行Toolsets相关逻辑。
     *
     * @return 返回Toolsets结果。
     */
    GatewayReply handleToolsets() {
        if (dashboardSkillsService == null) {
            return GatewayReply.error("工具集命令当前运行时未启用。");
        }
        List<Map<String, Object>> toolsets = dashboardSkillsService.getToolsets();
        GatewayReply reply = GatewayReply.ok(formatToolsets(toolsets));
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_TOOLSETS);
        reply.getRuntimeMetadata().put("toolset_count", Integer.valueOf(toolsets.size()));
        return reply;
    }

    /**
     * 执行浏览器相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回浏览器结果。
     */
    GatewayReply handleBrowser(String args) {
        if (browserRuntimeService == null) {
            return GatewayReply.error("浏览器命令当前运行时未启用。");
        }
        SlashCommandLine.ActionTail parsed =
                SlashCommandLine.parseActionTail(args, GatewayCommandConstants.COMMAND_STATUS);
        String action = parsed.getAction();
        String target = parsed.getTail();

        GatewayReply reply;
        if (GatewayCommandConstants.COMMAND_STATUS.equals(action)) {
            reply = GatewayReply.ok(formatBrowserStatus());
        } else if ("connect".equals(action)) {
            reply = browserReply(browserRuntimeService.create("slash-browser"), "浏览器会话已创建");
        } else if ("disconnect".equals(action) || "close".equals(action)) {
            if (StrUtil.isBlank(target)) {
                reply = GatewayReply.error(browserUsage());
            } else {
                reply = browserReply(browserRuntimeService.close(target), "浏览器会话已关闭");
            }
        } else {
            reply = GatewayReply.error(browserUsage());
        }
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_BROWSER);
        reply.getRuntimeMetadata()
                .put(
                        "browser_active_sessions",
                        Integer.valueOf(browserRuntimeService.activeLeaseCount()));
        reply.getRuntimeMetadata().put("action", action);
        return reply;
    }

    /**
     * 执行Debug相关逻辑。
     *
     * @return 返回Debug结果。
     */
    GatewayReply handleDebug() throws Exception {
        DebugSummary summary = debugSummary();
        GatewayReply reply = GatewayReply.ok(formatDebugSummary(summary));
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_DEBUG);
        reply.getRuntimeMetadata()
                .put("debug_provider_count", Integer.valueOf(summary.providerCount));
        reply.getRuntimeMetadata()
                .put("debug_channel_count", Integer.valueOf(summary.channelCount));
        reply.getRuntimeMetadata().put("debug_tool_count", Integer.valueOf(summary.toolCount));
        reply.getRuntimeMetadata()
                .put("debug_session_count", Integer.valueOf(summary.sessionCount));
        reply.getRuntimeMetadata()
                .put(
                        "debug_connected_channel_count",
                        Integer.valueOf(summary.connectedChannelCount));
        return reply;
    }

    /**
     * 格式化Toolsets。
     *
     * @param toolsets toolsets 参数。
     * @return 返回Toolsets结果。
     */
    @SuppressWarnings("unchecked")
    private String formatToolsets(List<Map<String, Object>> toolsets) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("工具集：total=").append(toolsets.size());
        for (Map<String, Object> toolset : toolsets) {
            List<Object> tools =
                    toolset.get("tools") instanceof List
                            ? (List<Object>) toolset.get("tools")
                            : Collections.<Object>emptyList();
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(String.valueOf(toolset.get("name")), "-"))
                    .append(" enabled=")
                    .append(Boolean.TRUE.equals(toolset.get("enabled")))
                    .append(" tools=")
                    .append(tools.size())
                    .append(" - ")
                    .append(StrUtil.blankToDefault(String.valueOf(toolset.get("label")), "-"));
        }
        return buffer.toString();
    }

    /**
     * 执行浏览器回复相关逻辑。
     *
     * @param result 结果响应或执行结果。
     * @param successMessage success消息参数。
     * @return 返回浏览器Reply结果。
     */
    private GatewayReply browserReply(
            BrowserRuntimeService.BrowserResult result, String successMessage) {
        if (result == null) {
            return GatewayReply.error("浏览器运行时未返回结果。");
        }
        if (!result.isSuccess()) {
            BrowserRuntimeService.BrowserError error = result.getError();
            String code = error == null ? "browser_error" : error.getCode();
            String message = error == null ? "浏览器运行时执行失败" : error.getMessage();
            return GatewayReply.error("浏览器运行时失败：" + code + " - " + message);
        }
        StringBuilder buffer = new StringBuilder(successMessage);
        if (StrUtil.isNotBlank(result.getSessionId())) {
            buffer.append('\n').append("session_id=").append(result.getSessionId());
        }
        if (StrUtil.isNotBlank(result.getStatus())) {
            buffer.append('\n').append("status=").append(result.getStatus());
        }
        return GatewayReply.ok(buffer.toString());
    }

    /**
     * 格式化浏览器状态。
     *
     * @return 返回浏览器状态。
     */
    private String formatBrowserStatus() {
        return "浏览器运行时："
                + "\nactive_sessions="
                + browserRuntimeService.activeLeaseCount()
                + "\n"
                + browserUsage();
    }

    /**
     * 执行浏览器用量相关逻辑。
     *
     * @return 返回浏览器用量结果。
     */
    private String browserUsage() {
        return "用法："
                + GatewayCommandConstants.SLASH_BROWSER
                + " [status|connect|disconnect <session-id>]";
    }

    /**
     * 执行debug摘要相关逻辑。
     *
     * @return 返回debug Summary结果。
     */
    private DebugSummary debugSummary() throws Exception {
        DebugSummary summary = new DebugSummary();
        summary.workspaceHome = "workspace://";
        summary.providerCount =
                appConfig == null || appConfig.getProviders() == null
                        ? 0
                        : appConfig.getProviders().size();
        List<ChannelStatus> statuses =
                deliveryService == null
                        ? Collections.<ChannelStatus>emptyList()
                        : deliveryService.statuses();
        summary.channelCount = statuses.size();
        for (ChannelStatus status : statuses) {
            if (status == null) {
                continue;
            }
            if (status.isEnabled()) {
                summary.enabledChannelCount++;
            }
            if (status.isConnected()) {
                summary.connectedChannelCount++;
            }
        }
        summary.toolCount = toolRegistry == null ? 0 : toolRegistry.listToolNames().size();
        summary.sessionCount = sessionRepository == null ? 0 : sessionRepository.countAll();
        summary.mcpStatus =
                appConfig != null && appConfig.getMcp().isEnabled() ? "enabled" : "disabled";
        summary.guardrailMode =
                appConfig == null || appConfig.getSecurity() == null
                        ? ""
                        : StrUtil.nullToEmpty(appConfig.getSecurity().getGuardrailMode());
        summary.securityProbesPassed = "not_run";
        return summary;
    }

    /**
     * 格式化Debug Summary。
     *
     * @param summary 摘要参数。
     * @return 返回Debug Summary结果。
     */
    private String formatDebugSummary(DebugSummary summary) {
        return "调试诊断："
                + "\nworkspace_home="
                + summary.workspaceHome
                + "\nproviders="
                + summary.providerCount
                + "\nchannels="
                + summary.channelCount
                + " enabled="
                + summary.enabledChannelCount
                + " connected="
                + summary.connectedChannelCount
                + "\ntools="
                + summary.toolCount
                + "\nsessions="
                + summary.sessionCount
                + "\nmcp="
                + summary.mcpStatus
                + "\nguardrail_mode="
                + summary.guardrailMode
                + "\nsecurity_probes_passed="
                + summary.securityProbesPassed
                + "\n"
                + debugUsage();
    }

    /**
     * 执行debug用量相关逻辑。
     *
     * @return 返回debug用量结果。
     */
    private String debugUsage() {
        return "用法：" + GatewayCommandConstants.SLASH_DEBUG + " [status]";
    }

    /** 承载Debug摘要相关状态和辅助逻辑。 */
    private static class DebugSummary {
        /** 记录Debug摘要中的运行时主渠道。 */
        private String workspaceHome;

        /** 记录Debug摘要中的提供方次数。 */
        private int providerCount;

        /** 记录Debug摘要中的渠道次数。 */
        private int channelCount;

        /** 标记是否启用渠道次数。 */
        private int enabledChannelCount;

        /** 记录Debug摘要中的connected渠道次数。 */
        private int connectedChannelCount;

        /** 记录Debug摘要中的工具次数。 */
        private int toolCount;

        /** 记录Debug摘要中的会话次数。 */
        private int sessionCount;

        /** 记录Debug摘要中的MCP状态。 */
        private String mcpStatus;

        /** 记录Debug摘要中的安全护栏模式。 */
        private String guardrailMode;

        /** 记录Debug摘要中的安全ProbesPassed。 */
        private String securityProbesPassed;
    }
}
