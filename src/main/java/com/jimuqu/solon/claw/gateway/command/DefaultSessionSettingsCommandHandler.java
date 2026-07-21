package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;

/** 处理会话模型、reasoning 和快速模式等会话设置命令。 */
final class DefaultSessionSettingsCommandHandler {
    /** 应用配置，用于读取默认 reasoning 强度。 */
    private final AppConfig appConfig;

    /** 会话仓储，用于持久化会话级模型、reasoning 和服务等级覆盖。 */
    private final SessionRepository sessionRepository;

    /** 运行时设置服务，用于读取和修改全局模型设置。 */
    private final RuntimeSettingsService runtimeSettingsService;

    /** 展示设置服务，用于控制 reasoning 展示开关。 */
    private final DisplaySettingsService displaySettingsService;

    /**
     * 创建会话设置命令处理器。
     *
     * @param appConfig 应用配置。
     * @param sessionRepository 会话仓储。
     * @param runtimeSettingsService 运行时设置服务。
     * @param displaySettingsService 展示设置服务。
     */
    DefaultSessionSettingsCommandHandler(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
        this.runtimeSettingsService = runtimeSettingsService;
        this.displaySettingsService = displaySettingsService;
    }

    /**
     * 执行模型切换命令。
     *
     * @param session 当前会话。
     * @param args 工具或命令参数。
     * @return 返回模型命令结果。
     */
    GatewayReply handleModel(SessionRecord session, String args) throws Exception {
        if (StrUtil.isBlank(args)) {
            return GatewayReply.ok(runtimeSettingsService.describeModel(session));
        }

        ModelCommandInput input = parseModelCommand(args);
        if (input.clear) {
            sessionRepository.setModelOverride(session.getSessionId(), null);
            return GatewayReply.ok("已清除当前会话模型覆盖，下一条消息将回退到全局默认模型。");
        }
        if (StrUtil.isBlank(input.model)) {
            return GatewayReply.error(
                    "用法：/model [--global] <model> 或 /model [--global] <provider>:<model>");
        }

        if (input.global) {
            runtimeSettingsService.setGlobalModel(input.provider, input.model);
            return GatewayReply.ok(
                    "已更新全局默认模型为："
                            + (StrUtil.isNotBlank(input.provider) ? input.provider + ":" : "")
                            + input.model
                            + "（下一条消息生效）");
        }

        String override =
                runtimeSettingsService.normalizeSessionModelOverride(input.provider, input.model);
        sessionRepository.setModelOverride(session.getSessionId(), override);
        return GatewayReply.ok("已切换当前会话模型为：" + override + "（下一条消息生效）");
    }

    /**
     * 执行推理相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param session 当前会话。
     * @param args 工具或命令参数。
     * @return 返回Reasoning结果。
     */
    GatewayReply handleReasoning(GatewayMessage message, SessionRecord session, String args)
            throws Exception {
        String normalized = StrUtil.nullToEmpty(args).trim().toLowerCase();
        if (normalized.length() == 0) {
            return GatewayReply.ok(
                    "reasoning_display="
                            + displaySettingsService.describeReasoning(
                                    message.sourceKey(), message.getPlatform())
                            + "\nreasoning_effort="
                            + effectiveReasoningEffort(session)
                            + "\nusage="
                            + GatewayCommandConstants.SLASH_REASONING
                            + " [level|reset|show|hide]");
        }
        if ("show".equals(normalized) || "on".equals(normalized)) {
            displaySettingsService.setReasoningVisible(message.sourceKey(), true);
            return GatewayReply.ok("已开启当前来源键的 reasoning 展示。");
        }
        if ("hide".equals(normalized) || "off".equals(normalized)) {
            displaySettingsService.setReasoningVisible(message.sourceKey(), false);
            return GatewayReply.ok("已关闭当前来源键的 reasoning 展示。");
        }
        if (session == null) {
            return GatewayReply.error("当前没有可设置 reasoning 的会话。");
        }
        if ("reset".equals(normalized) || "default".equals(normalized)) {
            sessionRepository.setReasoningEffortOverride(session.getSessionId(), null);
            session.setReasoningEffortOverride(null);
            return GatewayReply.ok(
                    "已清除当前会话 reasoning 覆盖。\nreasoning_effort=" + effectiveReasoningEffort(session));
        }
        if (isReasoningEffortLevel(normalized)) {
            String override = "none".equals(normalized) ? "none" : normalized;
            sessionRepository.setReasoningEffortOverride(session.getSessionId(), override);
            session.setReasoningEffortOverride(override);
            return GatewayReply.ok(
                    "已设置当前会话 reasoning 强度。\nreasoning_effort=" + effectiveReasoningEffort(session));
        }
        return GatewayReply.error(
                "用法：" + GatewayCommandConstants.SLASH_REASONING + " [level|reset|show|hide]");
    }

    /**
     * 执行Fast相关逻辑。
     *
     * @param session 会话参数。
     * @param args 工具或命令参数。
     * @return 返回Fast结果。
     */
    GatewayReply handleFast(SessionRecord session, String args) throws Exception {
        String normalized = StrUtil.nullToEmpty(args).trim().toLowerCase();
        if (StrUtil.isBlank(normalized) || "status".equals(normalized)) {
            return GatewayReply.ok(formatFastStatus(session));
        }
        if ("fast".equals(normalized) || "on".equals(normalized) || "priority".equals(normalized)) {
            sessionRepository.setServiceTierOverride(session.getSessionId(), "priority");
            session.setServiceTierOverride("priority");
            return GatewayReply.ok("已开启当前会话快速模式。\n" + formatFastStatus(session));
        }
        if ("normal".equals(normalized)
                || "off".equals(normalized)
                || "default".equals(normalized)) {
            sessionRepository.setServiceTierOverride(session.getSessionId(), null);
            session.setServiceTierOverride(null);
            return GatewayReply.ok("已恢复当前会话普通模式。\n" + formatFastStatus(session));
        }
        return GatewayReply.error(
                "用法：" + GatewayCommandConstants.SLASH_FAST + " [fast|normal|status]");
    }

    /**
     * 解析模型命令。
     *
     * @param args 工具或命令参数。
     * @return 返回解析后的模型命令。
     */
    private ModelCommandInput parseModelCommand(String args) {
        String[] tokens = args.trim().split("\\s+");
        ModelCommandInput result = new ModelCommandInput();
        StringBuilder remainder = new StringBuilder();
        for (String token : tokens) {
            if ("--global".equalsIgnoreCase(token)) {
                result.global = true;
                continue;
            }
            if (remainder.length() > 0) {
                remainder.append(' ');
            }
            remainder.append(token);
        }
        String spec = remainder.toString().trim();
        if ("clear".equalsIgnoreCase(spec)
                || "default".equalsIgnoreCase(spec)
                || "none".equalsIgnoreCase(spec)) {
            result.clear = true;
            return result;
        }
        int separator = spec.indexOf(':');
        if (separator > 0) {
            String candidateProvider = spec.substring(0, separator).trim();
            if (appConfig.getProviders().containsKey(candidateProvider)) {
                result.provider = candidateProvider;
                result.model = spec.substring(separator + 1).trim();
                return result;
            }
        }
        result.model = spec;
        return result;
    }

    /**
     * 判断是否Reasoning Effort级别。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Reasoning Effort级别满足条件则返回 true，否则返回 false。
     */
    private boolean isReasoningEffortLevel(String value) {
        return "none".equals(value)
                || "minimal".equals(value)
                || "low".equals(value)
                || "medium".equals(value)
                || "high".equals(value)
                || "xhigh".equals(value);
    }

    /**
     * 执行生效推理Effort相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回生效Reasoning Effort结果。
     */
    private String effectiveReasoningEffort(SessionRecord session) {
        String override =
                session == null
                        ? ""
                        : StrUtil.nullToEmpty(session.getReasoningEffortOverride()).trim();
        return StrUtil.blankToDefault(
                StrUtil.isNotBlank(override) ? override : appConfig.getLlm().getReasoningEffort(),
                "default");
    }

    /**
     * 格式化Fast状态。
     *
     * @param session 会话参数。
     * @return 返回Fast状态。
     */
    private String formatFastStatus(SessionRecord session) {
        return "fast_mode="
                + fastModeName(session)
                + "\nservice_tier="
                + serviceTierName(session)
                + "\nusage="
                + GatewayCommandConstants.SLASH_FAST
                + " [fast|normal|status]";
    }

    /**
     * 执行fast模式名称相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回fast模式名称结果。
     */
    private String fastModeName(SessionRecord session) {
        return isPriorityServiceTier(session) ? "fast" : "normal";
    }

    /**
     * 执行服务Tier名称相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回服务Tier名称结果。
     */
    private String serviceTierName(SessionRecord session) {
        return isPriorityServiceTier(session) ? "priority" : "default";
    }

    /**
     * 判断是否Priority服务Tier。
     *
     * @param session 会话参数。
     * @return 如果Priority服务Tier满足条件则返回 true，否则返回 false。
     */
    private boolean isPriorityServiceTier(SessionRecord session) {
        return GatewayCommandSessionSupport.isPriorityServiceTier(session);
    }

    /** 承载模型命令输入相关状态和辅助逻辑。 */
    private static class ModelCommandInput {
        /** 是否启用global。 */
        private boolean global;

        /** 是否启用clear。 */
        private boolean clear;

        /** 记录模型命令输入中的提供方。 */
        private String provider;

        /** 记录模型命令输入中的模型。 */
        private String model;
    }
}
