package com.jimuqu.solon.claw.proactive;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import java.util.LinkedHashMap;
import java.util.Map;

/** 提供主动提醒配置和主渠道可用性的只读诊断，不包含候选或发送决策。 */
public class ProactiveDiagnosticsService {
    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 会话仓储。 */
    private final SessionRepository sessionRepository;

    /** 创建主动提醒诊断服务。 */
    public ProactiveDiagnosticsService(AppConfig appConfig, SessionRepository sessionRepository) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
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
        if (sessionRepository == null) {
            return false;
        }
        try {
            for (SessionRecord session : sessionRepository.listRecent(50)) {
                String[] source = SourceKeySupport.split(session.getSourceKey());
                PlatformType platform = PlatformType.fromName(source[0]);
                if (PlatformType.DOMESTIC_PLATFORMS.contains(platform)
                        && source[1] != null
                        && !source[1].trim().isEmpty()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 只读诊断失败时返回不可用，不影响主服务。
        }
        return false;
    }
}
