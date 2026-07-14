package com.jimuqu.solon.claw.proactive;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
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

    /** 创建主动提醒诊断服务。 */
    public ProactiveDiagnosticsService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            GatewayPolicyRepository gatewayPolicyRepository) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
        this.gatewayPolicyRepository = gatewayPolicyRepository;
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
        return result;
    }

    /** 返回 Dashboard 诊断使用的状态。 */
    public Map<String, Object> diagnostics() {
        return status();
    }

    /** 返回渠道命令使用的简短状态。 */
    public String statusLine() {
        AppConfig.ProactiveConfig config = appConfig.getProactive();
        return "主动提醒"
                + (config.isEnabled() ? "已启用" : "已暂停")
                + "，每 "
                + config.getIntervalHours()
                + " 小时检查一次。";
    }

    /** 判断是否存在可投递的国内渠道主对话。 */
    private boolean hasHomeChannel() {
        if (sessionRepository == null || gatewayPolicyRepository == null) {
            return false;
        }
        try {
            java.util.List<HomeChannelRecord> homes = gatewayPolicyRepository.listHomeChannels();
            if (homes == null || homes.isEmpty()) {
                return false;
            }
            for (SessionRecord session : sessionRepository.listRecent(50)) {
                for (HomeChannelRecord home : homes) {
                    if (ProactiveReminderScheduler.matchesHome(session, home)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // 只读诊断失败时返回不可用，不影响主服务。
        }
        return false;
    }
}
