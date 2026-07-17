package com.jimuqu.solon.claw.proactive;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import java.util.LinkedHashMap;
import java.util.Map;

/** 提供主动提醒配置和主渠道可用性的只读诊断，不包含候选或发送决策。 */
public class ProactiveDiagnosticsService {
    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 会话仓储。 */
    private final SessionRepository sessionRepository;

    /** 网关策略仓储，用于确认主对话已显式绑定。 */
    private final GatewayPolicyRepository gatewayPolicyRepository;

    /** 主动提醒轻量状态仓储。 */
    private final ProactiveReminderStateStore stateStore;

    /** 创建主动提醒诊断服务。 */
    public ProactiveDiagnosticsService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            GatewayPolicyRepository gatewayPolicyRepository,
            GlobalSettingRepository globalSettingRepository) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
        this.gatewayPolicyRepository = gatewayPolicyRepository;
        this.stateStore = new ProactiveReminderStateStore(globalSettingRepository);
    }

    /** 返回 Dashboard 使用的主动提醒状态。 */
    public Map<String, Object> status() {
        AppConfig.ProactiveConfig config = appConfig.getProactive();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("enabled", Boolean.valueOf(config.isEnabled()));
        result.put("interval_hours", Double.valueOf(config.getIntervalHours()));
        result.put("delivery_target", config.getDeliveryTarget());
        result.put("topic_cooldown_hours", Double.valueOf(config.getTopicCooldownHours()));
        result.put("quiet_hours_enabled", Boolean.valueOf(config.isQuietHoursEnabled()));
        result.put("quiet_start", config.getQuietStart());
        result.put("quiet_end", config.getQuietEnd());
        result.put("main_conversation_ready", Boolean.valueOf(hasHomeChannel()));
        ProactiveReminderState state = stateStore.load();
        result.put("last_tick_at", timestamp(state.getLastTickAt()));
        result.put("last_outcome", state.getLastOutcome());
        result.put("last_reason", state.getLastReason());
        result.put("last_activity_level", Double.valueOf(state.getActivityLevel()));
        result.put("activity_credit", Double.valueOf(state.getActivityCredit()));
        result.put("analysis_reason", state.getAnalysisReason());
        result.put("last_sent_at", timestamp(state.getLastSentAt()));
        result.put("unanswered_count", Integer.valueOf(state.getUnansweredCount()));
        return result;
    }

    /** 返回 Dashboard 诊断使用的状态。 */
    public Map<String, Object> diagnostics() {
        return status();
    }

    /** 返回渠道命令使用的简短状态。 */
    public String statusLine() {
        AppConfig.ProactiveConfig config = appConfig.getProactive();
        ProactiveReminderState state = stateStore.load();
        return "主动提醒"
                + (config.isEnabled() ? "已启用" : "已暂停")
                + "，每 "
                + config.getIntervalHours()
                + " 小时检查一次。最近结果："
                + state.getLastOutcome()
                + "（"
                + state.getLastReason()
                + "）";
    }

    /** 判断是否存在可投递的国内渠道主对话。 */
    private boolean hasHomeChannel() {
        if (sessionRepository == null || gatewayPolicyRepository == null) {
            return false;
        }
        try {
            HomeChannelRecord home = gatewayPolicyRepository.getPrimaryHomeChannel();
            if (home == null) {
                return false;
            }
            for (SessionRecord session : sessionRepository.listRecent(50)) {
                if (ProactiveReminderScheduler.matchesHome(session, home)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 只读诊断失败时返回不可用，不影响主服务。
        }
        return false;
    }

    /** 将毫秒时间戳转为带本地时区的 ISO 时间；未设置时返回空值。 */
    private String timestamp(long millis) {
        if (millis <= 0L) {
            return null;
        }
        return java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
                .toOffsetDateTime()
                .toString();
    }
}
