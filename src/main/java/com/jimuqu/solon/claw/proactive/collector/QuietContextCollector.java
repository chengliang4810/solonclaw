package com.jimuqu.solon.claw.proactive.collector;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.proactive.ProactiveObservationCollector;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 静默上下文观测采集器，用于为主动协作决策提供 home channel、静默时间和活跃运行门控信号。 */
public class QuietContextCollector implements ProactiveObservationCollector {
    /** 采集器稳定名称，用于观测来源、排障和后续硬门控识别。 */
    public static final String COLLECTOR_NAME = "quiet_context";

    /** gate 观测的稳定类型，后续候选生成会忽略该类型，不直接生成用户触达候选。 */
    private static final String OBSERVATION_TYPE = "proactive_context";

    /** gate 观测固定来源键，表示该观测描述当前 tick 的全局上下文。 */
    private static final String SOURCE_KEY = "proactive_context:global";

    /** 活跃运行最多纳入数量，避免一次 gate 观测携带过多运行明细。 */
    private static final int ACTIVE_RUN_LIMIT = 20;

    /** 从仓储检索活跃运行的回看窗口，避免读取过旧运行态。 */
    private static final long ACTIVE_RUN_LOOKBACK_MILLIS = 24L * 60L * 60L * 1000L;

    /** 文本字段最大长度，避免载荷过大或泄露长上下文。 */
    private static final int TEXT_MAX_LENGTH = 220;

    /** Agent 运行仓储，用于查询当前仍可能占用来源的前台运行。 */
    private final AgentRunRepository agentRunRepository;

    /**
     * 创建静默上下文采集器。
     *
     * @param agentRunRepository Agent 运行仓储，可为空；为空时仅输出 home channel 和静默时间。
     */
    public QuietContextCollector(AgentRunRepository agentRunRepository) {
        this.agentRunRepository = agentRunRepository;
    }

    /** 返回静默上下文采集器名称。 */
    @Override
    public String name() {
        return COLLECTOR_NAME;
    }

    /** 主动协作开启时才输出门控上下文。 */
    @Override
    public boolean enabled(AppConfig config) {
        AppConfig.ProactiveConfig proactive = config == null ? null : config.getProactive();
        return proactive != null && proactive.isEnabled();
    }

    /** 采集当前 tick 的全局门控上下文。 */
    @Override
    public List<ProactiveObservation> collect(ProactiveTickContext context) throws Exception {
        List<ProactiveObservation> observations = new ArrayList<ProactiveObservation>();
        if (context == null || !enabled(context.getConfig())) {
            return observations;
        }
        observations.add(buildObservation(context, activeRuns(context)));
        return observations;
    }

    /**
     * 查询近期仍处于活跃状态的运行记录。
     *
     * @param context 当前 tick 上下文。
     * @return 返回活跃运行列表。
     */
    private List<AgentRunRecord> activeRuns(ProactiveTickContext context) {
        List<AgentRunRecord> result = new ArrayList<AgentRunRecord>();
        if (agentRunRepository == null) {
            return result;
        }
        try {
            List<AgentRunRecord> runs =
                    agentRunRepository.searchRuns(
                            null,
                            null,
                            null,
                            null,
                            Math.max(0L, context.getNowMillis() - ACTIVE_RUN_LOOKBACK_MILLIS),
                            context.getNowMillis(),
                            ACTIVE_RUN_LIMIT);
            if (runs == null) {
                return result;
            }
            for (AgentRunRecord run : runs) {
                if (run == null || !isActiveRun(run)) {
                    continue;
                }
                result.add(run);
                if (result.size() >= ACTIVE_RUN_LIMIT) {
                    break;
                }
            }
        } catch (Exception ignored) {
            return result;
        }
        return result;
    }

    /**
     * 判断运行是否仍可能占用来源或前台上下文。
     *
     * @param run Agent 运行记录。
     * @return 运行中、排队、暂停或后台运行返回 true。
     */
    private boolean isActiveRun(AgentRunRecord run) {
        if (run.getFinishedAt() > 0L) {
            return false;
        }
        String status = normalize(run.getStatus());
        String phase = normalize(run.getPhase());
        return run.isBackgrounded()
                || status.contains("running")
                || status.contains("queued")
                || status.contains("waiting")
                || status.contains("paused")
                || status.contains("interrupting")
                || phase.contains("running")
                || phase.contains("queue")
                || phase.contains("waiting");
    }

    /**
     * 构造全局门控观测。
     *
     * @param context 当前 tick 上下文。
     * @param activeRuns 活跃运行列表。
     * @return 返回主动协作观测。
     */
    private ProactiveObservation buildObservation(
            ProactiveTickContext context, List<AgentRunRecord> activeRuns) {
        List<HomeChannelRecord> homeChannels = safeHomeChannels(context);
        boolean homeReady = !homeChannels.isEmpty();
        boolean quietHour = isQuietHour(context);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", OBSERVATION_TYPE);
        payload.put("gateOnly", Boolean.TRUE);
        payload.put("homeChannelReady", Boolean.valueOf(homeReady));
        payload.put("homeChannelCount", Integer.valueOf(homeChannels.size()));
        payload.put("missingHomeChannel", Boolean.valueOf(!homeReady));
        payload.put("quietHour", Boolean.valueOf(quietHour));
        payload.put("quietStartHour", Integer.valueOf(context.getConfig().getProactive().getQuietStartHour()));
        payload.put("quietEndHour", Integer.valueOf(context.getConfig().getProactive().getQuietEndHour()));
        payload.put("lastSentAt", lastSentAt(context));
        payload.put("activeRunCount", Integer.valueOf(activeRuns.size()));
        payload.put("homeChannels", homeChannelPayload(homeChannels));
        payload.put("activeRuns", activeRunPayload(activeRuns));

        ProactiveObservation observation = new ProactiveObservation();
        observation.setCollector(COLLECTOR_NAME);
        observation.setSourceKey(SOURCE_KEY);
        observation.setStatus("COLLECTED");
        observation.setSummary(summary(homeReady, quietHour, activeRuns));
        observation.setPayload(payload);
        return observation;
    }

    /**
     * 读取上下文中的 home channel 列表并过滤无效项。
     *
     * @param context 当前 tick 上下文。
     * @return 返回有效 home channel。
     */
    private List<HomeChannelRecord> safeHomeChannels(ProactiveTickContext context) {
        List<HomeChannelRecord> result = new ArrayList<HomeChannelRecord>();
        if (context.getHomeChannels() == null) {
            return result;
        }
        for (HomeChannelRecord home : context.getHomeChannels()) {
            if (home == null || home.getPlatform() == null || StrUtil.isBlank(home.getChatId())) {
                continue;
            }
            result.add(home);
        }
        return result;
    }

    /**
     * 判断当前 tick 是否处于静默时段，支持跨午夜窗口。
     *
     * @param context 当前 tick 上下文。
     * @return 处于静默时段返回 true。
     */
    private boolean isQuietHour(ProactiveTickContext context) {
        int start = normalizeHour(context.getConfig().getProactive().getQuietStartHour());
        int end = normalizeHour(context.getConfig().getProactive().getQuietEndHour());
        int hour =
                LocalDateTime.ofInstant(
                                Instant.ofEpochMilli(context.getNowMillis()), ZoneId.systemDefault())
                        .getHour();
        if (start == end) {
            return false;
        }
        if (start < end) {
            return hour >= start && hour < end;
        }
        return hour >= start || hour < end;
    }

    /**
     * 将配置小时夹紧到 0-23。
     *
     * @param hour 原始小时。
     * @return 返回合法小时。
     */
    private int normalizeHour(int hour) {
        if (hour < 0) {
            return 0;
        }
        if (hour > 23) {
            return 23;
        }
        return hour;
    }

    /**
     * 从最近决策摘要中读取最近一次成功发送时间。
     *
     * @param context 当前 tick 上下文。
     * @return 返回最近发送时间；没有时返回 null。
     */
    private Long lastSentAt(ProactiveTickContext context) {
        if (context.getLastDecisionSummaries() == null) {
            return null;
        }
        Long latest = null;
        for (ProactiveDecisionRecord decision : context.getLastDecisionSummaries()) {
            if (decision == null || !isSentDecision(decision)) {
                continue;
            }
            long createdAt = decision.getCreatedAt();
            if (createdAt > 0L && (latest == null || createdAt > latest.longValue())) {
                latest = Long.valueOf(createdAt);
            }
        }
        return latest;
    }

    /**
     * 判断决策是否代表成功发送，空投递状态按仓储口径视为成功发送。
     *
     * @param decision 决策记录。
     * @return 发送成功返回 true。
     */
    private boolean isSentDecision(ProactiveDecisionRecord decision) {
        if (!"send".equals(normalize(decision.getDecision()))) {
            return false;
        }
        String deliveryStatus = normalize(decision.getDeliveryStatus());
        return StrUtil.isBlank(deliveryStatus) || "success".equals(deliveryStatus);
    }

    /**
     * 构造 home channel 载荷。
     *
     * @param homeChannels home channel 列表。
     * @return 返回结构化列表。
     */
    private List<Map<String, Object>> homeChannelPayload(List<HomeChannelRecord> homeChannels) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (HomeChannelRecord home : homeChannels) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("platform", home.getPlatform().name());
            item.put("chatId", safe(home.getChatId(), TEXT_MAX_LENGTH));
            item.put("threadId", safe(home.getThreadId(), TEXT_MAX_LENGTH));
            item.put("chatName", safe(home.getChatName(), TEXT_MAX_LENGTH));
            item.put("updatedAt", Long.valueOf(home.getUpdatedAt()));
            result.add(item);
        }
        return result;
    }

    /**
     * 构造活跃运行门控载荷。
     *
     * @param activeRuns 活跃运行列表。
     * @return 返回结构化列表。
     */
    private List<Map<String, Object>> activeRunPayload(List<AgentRunRecord> activeRuns) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (AgentRunRecord run : activeRuns) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("runId", safe(run.getRunId(), TEXT_MAX_LENGTH));
            item.put("sessionId", safe(run.getSessionId(), TEXT_MAX_LENGTH));
            item.put("sourceKey", safe(run.getSourceKey(), TEXT_MAX_LENGTH));
            item.put("status", safe(run.getStatus(), 80));
            item.put("phase", safe(run.getPhase(), 120));
            item.put("backgrounded", Boolean.valueOf(run.isBackgrounded()));
            item.put("lastActivityAt", Long.valueOf(run.getLastActivityAt()));
            result.add(item);
        }
        return result;
    }

    /**
     * 生成门控观测摘要。
     *
     * @param homeReady 是否已有 home channel。
     * @param quietHour 是否处于静默时段。
     * @param activeRuns 活跃运行列表。
     * @return 返回摘要。
     */
    private String summary(boolean homeReady, boolean quietHour, List<AgentRunRecord> activeRuns) {
        List<String> reasons = new ArrayList<String>();
        if (!homeReady) {
            reasons.add("home channel 未配置");
        }
        if (quietHour) {
            reasons.add("处于静默时段");
        }
        if (!activeRuns.isEmpty()) {
            reasons.add("存在活跃运行 " + activeRuns.size() + " 个");
        }
        if (reasons.isEmpty()) {
            reasons.add("主动协作门控可用");
        }
        return "proactive_context: " + String.join("，", reasons);
    }

    /**
     * 规范化文本用于状态判断。
     *
     * @param value 原始文本。
     * @return 返回小写文本。
     */
    private String normalize(String value) {
        return StrUtil.nullToEmpty(value).toLowerCase(Locale.ROOT);
    }

    /**
     * 对载荷文本做脱敏和长度限制。
     *
     * @param value 原始文本。
     * @param maxLength 最大保留长度。
     * @return 返回安全文本。
     */
    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), maxLength);
    }
}
