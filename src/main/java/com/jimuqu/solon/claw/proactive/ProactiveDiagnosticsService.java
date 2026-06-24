package com.jimuqu.solon.claw.proactive;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 主动协作 Dashboard 与 doctor 只读诊断服务。 */
public class ProactiveDiagnosticsService {
    /** 主动协作诊断服务的低敏日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(ProactiveDiagnosticsService.class);

    /** 应用配置快照，用于展示主动协作频率和硬门控。 */
    private final AppConfig appConfig;

    /** 主动协作仓储，用于读取候选、决策和投递记录。 */
    private final ProactiveRepository proactiveRepository;

    /** 网关策略仓储，用于判断是否已经配置可投递的 home channel。 */
    private final GatewayPolicyRepository gatewayPolicyRepository;

    /**
     * 创建主动协作诊断服务；缺少网关策略仓储时仅返回仓储侧诊断。
     *
     * @param appConfig 应用配置快照。
     * @param proactiveRepository 主动协作仓储。
     */
    public ProactiveDiagnosticsService(AppConfig appConfig, ProactiveRepository proactiveRepository) {
        this(appConfig, proactiveRepository, null);
    }

    /**
     * 创建主动协作诊断服务。
     *
     * @param appConfig 应用配置快照。
     * @param proactiveRepository 主动协作仓储。
     * @param gatewayPolicyRepository 网关策略仓储。
     */
    public ProactiveDiagnosticsService(
            AppConfig appConfig,
            ProactiveRepository proactiveRepository,
            GatewayPolicyRepository gatewayPolicyRepository) {
        this.appConfig = appConfig;
        this.proactiveRepository = proactiveRepository;
        this.gatewayPolicyRepository = gatewayPolicyRepository;
    }

    /**
     * 输出 Dashboard 状态摘要，便于页面快速展示主动协作是否具备联系用户的条件。
     *
     * @return 主动协作状态摘要。
     */
    public Map<String, Object> status() {
        Snapshot snapshot = loadSnapshot();
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("enabled", Boolean.valueOf(config().isEnabled()));
        status.put("interval_minutes", Integer.valueOf(config().getIntervalMinutes()));
        status.put("last_tick_at", millisOrNull(snapshot.lastTickAt));
        status.put("pending_candidate_count", Integer.valueOf(snapshot.pendingCandidateCount));
        status.put("sent_today", Integer.valueOf(snapshot.sentToday));
        status.put("last_sent_at", millisOrNull(snapshot.lastSentAt));
        status.put("last_skip_reason", safeText(snapshot.lastSkipReason, 500));
        status.put("home_channel_ready", Boolean.valueOf(snapshot.homeChannelReady));
        status.put("home_channels", snapshot.homeChannels);
        return status;
    }

    /**
     * 输出主动协作 doctor 诊断，重点回答“为什么没有主动联系我”。
     *
     * @return 主动协作 doctor 诊断。
     */
    public Map<String, Object> diagnostics() {
        Snapshot snapshot = loadSnapshot();
        boolean quietHours = isQuietHour(System.currentTimeMillis());
        boolean cooldownBlocked =
                snapshot.lastSentAt != null
                        && config().getCooldownMinutes() > 0
                        && snapshot.lastSentAt.longValue()
                                + config().getCooldownMinutes() * 60_000L
                                > System.currentTimeMillis();
        boolean dailyCapBlocked =
                config().getDailyMaxContacts() > 0
                        && snapshot.sentToday >= config().getDailyMaxContacts();
        boolean deliveryFailed = snapshot.lastDeliveryFailed;
        String whyNoneSent =
                explainWhyNoneSent(
                        snapshot,
                        quietHours,
                        cooldownBlocked,
                        dailyCapBlocked,
                        deliveryFailed);

        Map<String, Object> diagnostics = new LinkedHashMap<String, Object>();
        diagnostics.put("enabled", Boolean.valueOf(config().isEnabled()));
        diagnostics.put("scheduler_ran", Boolean.valueOf(snapshot.schedulerRan));
        diagnostics.put("last_tick_at", millisOrNull(snapshot.lastTickAt));
        diagnostics.put("candidates_generated", Boolean.valueOf(snapshot.pendingCandidateCount > 0));
        diagnostics.put("pending_candidate_count", Integer.valueOf(snapshot.pendingCandidateCount));
        diagnostics.put("why_none_sent", safeText(whyNoneSent, 800));
        diagnostics.put("missing_home_channel", Boolean.valueOf(!snapshot.homeChannelReady));
        diagnostics.put("quiet_hours_blocked", Boolean.valueOf(quietHours));
        diagnostics.put("cooldown_blocked", Boolean.valueOf(cooldownBlocked));
        diagnostics.put("daily_cap_blocked", Boolean.valueOf(dailyCapBlocked));
        diagnostics.put("delivery_failed", Boolean.valueOf(deliveryFailed));
        diagnostics.put("last_skip_reason", safeText(snapshot.lastSkipReason, 500));
        diagnostics.put("last_delivery_error", safeText(snapshot.lastDeliveryError, 500));
        diagnostics.put("last_decision", safeDecision(snapshot.lastDecision));
        diagnostics.put("home_channels", snapshot.homeChannels);
        return diagnostics;
    }

    /**
     * 读取最近一次决策，供对话命令回答“为什么主动协作没有联系我”。
     *
     * @return 最近决策记录；没有记录时返回 null。
     */
    public ProactiveDecisionRecord latestDecision() {
        Snapshot snapshot = loadSnapshot();
        return snapshot.lastDecision;
    }

    /**
     * 生成主动协作状态的一行中文摘要。
     *
     * @return 可直接展示给用户的状态说明。
     */
    public String statusLine() {
        Snapshot snapshot = loadSnapshot();
        String state = config().isEnabled() ? "已启用" : "已暂停";
        String home = snapshot.homeChannelReady ? "home channel 已配置" : "缺少 home channel";
        return "主动协作" + state + "，待处理候选 " + snapshot.pendingCandidateCount + " 个，今日已联系 "
                + snapshot.sentToday + " 次，" + home + "。";
    }

    /**
     * 加载主动协作诊断所需的只读快照。
     *
     * @return 聚合后的只读快照。
     */
    private Snapshot loadSnapshot() {
        Snapshot snapshot = new Snapshot();
        snapshot.homeChannels = loadHomeChannels();
        snapshot.homeChannelReady = !snapshot.homeChannels.isEmpty();
        if (proactiveRepository == null) {
            return snapshot;
        }
        try {
            long now = System.currentTimeMillis();
            List<ProactiveCandidateRecord> pending =
                    proactiveRepository.listPendingCandidates(now, 1000);
            snapshot.pendingCandidateCount = pending.size();
            snapshot.sentToday = proactiveRepository.countSentSince(null, startOfTodayMillis());
            snapshot.lastSentAt = proactiveRepository.findLastSentAt(null);
            List<ProactiveDecisionRecord> decisions = proactiveRepository.listRecentDecisions(20);
            if (!decisions.isEmpty()) {
                snapshot.schedulerRan = true;
                snapshot.lastDecision = decisions.get(0);
                snapshot.lastTickAt = Long.valueOf(snapshot.lastDecision.getCreatedAt());
                for (ProactiveDecisionRecord decision : decisions) {
                    if (isSkip(decision)) {
                        snapshot.lastSkipReason = decision.getReason();
                        break;
                    }
                }
                for (ProactiveDecisionRecord decision : decisions) {
                    if (isDeliveryFailed(decision)) {
                        snapshot.lastDeliveryFailed = true;
                        snapshot.lastDeliveryError = decision.getDeliveryError();
                        break;
                    }
                }
            } else if (!pending.isEmpty()) {
                snapshot.lastTickAt = Long.valueOf(pending.get(0).getCreatedAt());
            }
        } catch (Exception e) {
            snapshot.lastSkipReason = "主动协作诊断读取失败：" + e.getMessage();
        }
        return snapshot;
    }

    /**
     * 读取已配置 home channel，并按平台名输出安全摘要。
     *
     * @return home channel 摘要列表。
     */
    private List<Map<String, Object>> loadHomeChannels() {
        List<Map<String, Object>> channels = new ArrayList<Map<String, Object>>();
        if (gatewayPolicyRepository == null) {
            return channels;
        }
        for (PlatformType platform : PlatformType.DOMESTIC_PLATFORMS) {
            try {
                HomeChannelRecord record = gatewayPolicyRepository.getHomeChannel(platform);
                if (record == null || StrUtil.isBlank(record.getChatId())) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("platform", platform.name().toLowerCase(Locale.ROOT));
                item.put("chat_id", safeText(record.getChatId(), 160));
                item.put("thread_id", safeText(record.getThreadId(), 160));
                item.put("chat_name", safeText(record.getChatName(), 160));
                item.put("updated_at", millisOrNull(Long.valueOf(record.getUpdatedAt())));
                channels.add(item);
            } catch (Exception e) {
                log.debug(
                        "主动协作 home channel 诊断读取失败，已跳过该平台：platform={}, errorType={}",
                        platform.name().toLowerCase(Locale.ROOT),
                        e.getClass().getSimpleName());
            }
        }
        return channels;
    }

    /**
     * 解释当前没有发送主动联系的主要原因。
     *
     * @param snapshot 诊断快照。
     * @param quietHours 是否处于免打扰时段。
     * @param cooldownBlocked 是否被冷却窗口阻止。
     * @param dailyCapBlocked 是否达到每日上限。
     * @param deliveryFailed 最近投递是否失败。
     * @return 主要原因说明。
     */
    private String explainWhyNoneSent(
            Snapshot snapshot,
            boolean quietHours,
            boolean cooldownBlocked,
            boolean dailyCapBlocked,
            boolean deliveryFailed) {
        if (!config().isEnabled()) {
            return "主动协作当前已暂停。";
        }
        if (StrUtil.isNotBlank(snapshot.lastSkipReason)) {
            return snapshot.lastSkipReason;
        }
        if (!snapshot.homeChannelReady) {
            return "缺少 home channel，主动协作没有可投递的国内消息渠道。";
        }
        if (quietHours) {
            return "当前处于免打扰时段，主动协作暂不联系。";
        }
        if (cooldownBlocked) {
            return "距离上次主动联系仍在冷却窗口内。";
        }
        if (dailyCapBlocked) {
            return "今日主动联系次数已达到上限。";
        }
        if (deliveryFailed) {
            return "最近一次主动协作投递失败，需要检查渠道连接。";
        }
        if (snapshot.pendingCandidateCount <= 0) {
            return snapshot.schedulerRan ? "最近调度已运行，但没有生成可联系候选。" : "主动协作调度尚未产生记录。";
        }
        return "已有候选等待决策，下一次调度会继续应用门控。";
    }

    /**
     * 判断当前时间是否落在主动协作免打扰时段。
     *
     * @param nowMillis 当前毫秒时间。
     * @return 处于免打扰时段时返回 true。
     */
    private boolean isQuietHour(long nowMillis) {
        int hour = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).getHour();
        return ProactiveSupport.isQuietHour(config().getQuietStartHour(), config().getQuietEndHour(), hour);
    }

    /**
     * 计算本地时区当天零点的毫秒时间。
     *
     * @return 当天零点毫秒时间。
     */
    private long startOfTodayMillis() {
        return LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    /**
     * 判断决策是否代表跳过或抑制。
     *
     * @param decision 决策记录。
     * @return 跳过类决策返回 true。
     */
    private boolean isSkip(ProactiveDecisionRecord decision) {
        return decision != null
                && !"SEND".equalsIgnoreCase(StrUtil.nullToEmpty(decision.getDecision()));
    }

    /**
     * 判断决策是否记录了投递失败。
     *
     * @param decision 决策记录。
     * @return 投递失败返回 true。
     */
    private boolean isDeliveryFailed(ProactiveDecisionRecord decision) {
        if (decision == null) {
            return false;
        }
        String status = StrUtil.nullToEmpty(decision.getDeliveryStatus());
        return "FAILED".equalsIgnoreCase(status) || StrUtil.isNotBlank(decision.getDeliveryError());
    }

    /**
     * 输出安全的最近决策摘要。
     *
     * @param decision 最近决策记录。
     * @return 决策摘要；无记录时返回 null。
     */
    private Map<String, Object> safeDecision(ProactiveDecisionRecord decision) {
        if (decision == null) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("decision_id", safeText(decision.getDecisionId(), 160));
        item.put("tick_id", safeText(decision.getTickId(), 160));
        item.put("candidate_id", safeText(decision.getCandidateId(), 160));
        item.put("decision", safeText(decision.getDecision(), 80));
        item.put("reason", safeText(decision.getReason(), 500));
        item.put("delivery_status", safeText(decision.getDeliveryStatus(), 120));
        item.put("created_at", millisOrNull(Long.valueOf(decision.getCreatedAt())));
        return item;
    }

    /**
     * 获取主动协作配置对象，避免测试构造中配置为空。
     *
     * @return 主动协作配置。
     */
    private AppConfig.ProactiveConfig config() {
        return appConfig == null ? new AppConfig.ProactiveConfig() : appConfig.getProactive();
    }

    /**
     * 安全清理短文本，避免诊断输出泄漏 token 或密钥。
     *
     * @param value 原始文本。
     * @param limit 最大长度。
     * @return 清理后的文本。
     */
    private String safeText(String value, int limit) {
        if (StrUtil.isBlank(value)) {
            return value;
        }
        String redacted = SecretRedactor.redact(value, limit);
        return redacted == null ? null : redacted.trim();
    }

    /**
     * 统一处理毫秒时间输出。
     *
     * @param value 毫秒时间。
     * @return 有效时间返回 Long，否则返回 null。
     */
    private Long millisOrNull(Long value) {
        return value == null || value.longValue() <= 0L ? null : value;
    }

    /** 主动协作诊断内部只读快照。 */
    private static class Snapshot {
        /** 最近调度是否留下决策记录。 */
        private boolean schedulerRan;

        /** 最近 tick 的推断时间。 */
        private Long lastTickAt;

        /** 当前仍待处理的候选数量。 */
        private int pendingCandidateCount;

        /** 今日成功主动联系次数。 */
        private int sentToday;

        /** 最近成功投递时间。 */
        private Long lastSentAt;

        /** 最近跳过原因。 */
        private String lastSkipReason;

        /** 最近投递错误。 */
        private String lastDeliveryError;

        /** 最近投递是否失败。 */
        private boolean lastDeliveryFailed;

        /** 最近决策记录。 */
        private ProactiveDecisionRecord lastDecision;

        /** 是否存在可投递的 home channel。 */
        private boolean homeChannelReady;

        /** 已配置 home channel 摘要。 */
        private List<Map<String, Object>> homeChannels = new ArrayList<Map<String, Object>>();
    }
}
