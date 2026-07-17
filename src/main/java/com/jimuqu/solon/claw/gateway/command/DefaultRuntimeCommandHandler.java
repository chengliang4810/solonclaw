package com.jimuqu.solon.claw.gateway.command;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.proactive.ProactiveReminderState;
import com.jimuqu.solon.claw.proactive.ProactiveReminderStateStore;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;

/** 处理主动协作等运行时控制面命令。 */
final class DefaultRuntimeCommandHandler {
    /** 应用配置，用于读取主动协作运行配置。 */
    private final AppConfig appConfig;

    /** 全局设置仓储，用于保存主动协作设置覆盖。 */
    private final GlobalSettingRepository globalSettingRepository;

    /**
     * 创建运行时控制面命令处理器。
     *
     * @param appConfig 应用配置。
     * @param globalSettingRepository 全局设置仓储。
     */
    DefaultRuntimeCommandHandler(
            AppConfig appConfig, GlobalSettingRepository globalSettingRepository) {
        this.appConfig = appConfig;
        this.globalSettingRepository = globalSettingRepository;
    }

    /**
     * 执行主动提醒启停和状态命令。
     *
     * @param args 工具或命令参数。
     * @param sourceKey 当前渠道用户来源键，用于隔离人工重试候选。
     * @return 返回主动协作命令结果。
     */
    GatewayReply handleProactive(String args, String sourceKey) throws Exception {
        SlashCommandLine.ActionTail parsed = SlashCommandLine.parseActionTail(args, "status");
        String action = parsed.getAction();
        GatewayReply reply;
        if ("status".equals(action) || "state".equals(action)) {
            reply = GatewayReply.ok(proactiveStatusText());
        } else if ("why".equals(action)) {
            reply = GatewayReply.ok(proactiveWhyText());
        } else if (GatewayCommandConstants.ACTION_PAUSE.equals(action)
                || "off".equals(action)
                || "disable".equals(action)) {
            setProactiveSetting("proactive.enabled", "false");
            if (appConfig != null && appConfig.getProactive() != null) {
                appConfig.getProactive().setEnabled(false);
            }
            reply = GatewayReply.ok("已暂停主动提醒。后续不会主动联系，直到使用 /proactive resume。");
        } else if (GatewayCommandConstants.ACTION_RESUME.equals(action)
                || "on".equals(action)
                || "enable".equals(action)) {
            setProactiveSetting("proactive.enabled", "true");
            if (appConfig != null && appConfig.getProactive() != null) {
                appConfig.getProactive().setEnabled(true);
            }
            reply = GatewayReply.ok("已恢复主动提醒，无需重启服务。系统仍会遵守检查间隔、话题间隔和免打扰时段。");
        } else {
            reply = GatewayReply.error(proactiveUsage());
        }
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_PROACTIVE);
        reply.getRuntimeMetadata().put("action", action);
        return reply;
    }

    /**
     * 生成主动协作状态文本。
     *
     * @return 主动协作状态文本。
     */
    private String proactiveStatusText() {
        AppConfig.ProactiveConfig config = appConfig.getProactive();
        ProactiveReminderState state =
                new ProactiveReminderStateStore(globalSettingRepository).load();
        return "主动提醒"
                + (config.isEnabled() ? "已启用" : "已暂停")
                + "，检查间隔 "
                + config.getIntervalHours()
                + " 小时，同话题至少间隔 "
                + config.getTopicCooldownHours()
                + " 小时。最近结果："
                + state.getLastOutcome()
                + "。";
    }

    /**
     * 解释最近一次主动提醒为什么发送或跳过。
     *
     * @return 最近一次主动提醒的完整可观测状态。
     */
    private String proactiveWhyText() {
        ProactiveReminderState state =
                new ProactiveReminderStateStore(globalSettingRepository).load();
        return "最近一次主动提醒检查：\n"
                + "- 时间："
                + timestamp(state.getLastTickAt())
                + "\n- 结果："
                + state.getLastOutcome()
                + "\n- 原因："
                + state.getLastReason()
                + "\n- 活跃度："
                + state.getActivityLevel()
                + "\n- 累计额度："
                + state.getActivityCredit()
                + "\n- 分析理由："
                + state.getAnalysisReason()
                + "\n- 最近发送："
                + timestamp(state.getLastSentAt())
                + "\n- 连续未回应："
                + state.getUnansweredCount();
    }

    /** 将毫秒时间戳转为带本地时区的 ISO 时间；未设置时返回“无”。 */
    private String timestamp(long millis) {
        if (millis <= 0L) {
            return "无";
        }
        return java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
                .toOffsetDateTime()
                .toString();
    }

    /**
     * 写入主动协作运行时设置覆盖。
     *
     * @param key 设置键。
     * @param value 设置值。
     */
    private void setProactiveSetting(String key, String value) throws Exception {
        if (globalSettingRepository != null) {
            globalSettingRepository.set(key, value);
        }
    }

    /**
     * 生成主动协作命令用法文本。
     *
     * @return 用法文本。
     */
    private String proactiveUsage() {
        return "用法：/proactive status|pause|resume|why";
    }
}
