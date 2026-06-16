package com.jimuqu.solon.claw.proactive;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecision;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.IdSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;

/** 主动协作决策服务，负责硬门控、频率控制、排序和可选模型判断。 */
public class ProactiveDecisionService {
    /** 模型决策系统提示词，强调只能判断是否联系用户，不能授权执行动作。 */
    private static final String LLM_DECISION_SYSTEM_PROMPT =
            "你是主动协作触达决策器。只判断是否值得联系用户，输出 JSON："
                    + "{\"send\":boolean,\"reason\":\"...\",\"message_intent\":\"...\",\"sensitivity\":\"low|normal|high\"}。"
                    + "不要承诺已执行任何代码、命令、外部投递或文件修改。";

    /** 主动协作仓储，用于保存决策和更新候选状态。 */
    private final ProactiveRepository repository;

    /** 可选模型决策客户端，为空时仅使用确定性策略。 */
    private final LlmDecisionClient llmDecisionClient;

    /**
     * 创建仅使用确定性策略的主动协作决策服务。
     *
     * @param repository 主动协作仓储。
     */
    public ProactiveDecisionService(ProactiveRepository repository) {
        this(repository, null);
    }

    /**
     * 创建支持可选模型判断的主动协作决策服务。
     *
     * @param repository 主动协作仓储。
     * @param llmDecisionClient 模型决策客户端，可为空。
     */
    public ProactiveDecisionService(
            ProactiveRepository repository, LlmDecisionClient llmDecisionClient) {
        this.repository = repository;
        this.llmDecisionClient = llmDecisionClient;
    }

    /**
     * 根据候选和门控观测生成决策，并把每个 send/skip 原因落库。
     *
     * @param context 当前 tick 上下文。
     * @param candidates 待决策候选列表。
     * @param observations 当前 tick 观测记录，用于读取 gate-only 上下文。
     * @return 返回按决策顺序排列的决策结果。
     * @throws Exception 仓储读写或模型调用失败时抛出异常。
     */
    public List<ProactiveDecision> decide(
            ProactiveTickContext context,
            List<ProactiveCandidateRecord> candidates,
            List<ProactiveObservationRecord> observations)
            throws Exception {
        List<ProactiveDecision> decisions = new ArrayList<ProactiveDecision>();
        if (context == null || candidates == null || candidates.isEmpty()) {
            return decisions;
        }
        List<ProactiveCandidateRecord> ranked = rankedCandidates(candidates);
        GateContext gateContext = GateContext.from(context, observations);
        int sentThisTick = 0;
        int maxContactsPerTick = maxContactsPerTick(context);
        for (ProactiveCandidateRecord candidate : ranked) {
            String hardGateReason = hardGateReason(context, candidate, gateContext);
            ProactiveDecision decision;
            if (hardGateReason != null) {
                decision = skip(context, candidate, hardGateReason, null, gateContext.metadata());
            } else if (sentThisTick >= maxContactsPerTick) {
                decision = skip(context, candidate, "contact_limit_reached", null, gateContext.metadata());
            } else {
                decision = softDecision(context, candidate, gateContext);
                if ("SEND".equals(decision.getDecision())) {
                    sentThisTick++;
                }
            }
            persistDecision(decision);
            decisions.add(decision);
        }
        return decisions;
    }

    /**
     * 应用确定性或模型软决策；硬门控已经在调用前完成。
     *
     * @param context 当前 tick 上下文。
     * @param candidate 待判断候选。
     * @param gateContext 门控上下文。
     * @return 返回 send 或 skip 决策。
     * @throws Exception 模型调用失败时抛出异常。
     */
    private ProactiveDecision softDecision(
            ProactiveTickContext context, ProactiveCandidateRecord candidate, GateContext gateContext)
            throws Exception {
        if (!llmDecisionEnabled(context) || llmDecisionClient == null) {
            return send(context, candidate, "deterministic_allow", "可以主动询问用户是否需要协作", "normal", gateContext.metadata());
        }
        LlmDecisionResult llmResult = llmDecisionClient.decide(context, candidate);
        if (llmResult == null) {
            return skip(context, candidate, "llm_skip: empty_result", "", gateContext.metadata());
        }
        String reason = StrUtil.blankToDefault(llmResult.getReason(), "模型未给出原因");
        String sensitivity = normalizeSensitivity(llmResult.getSensitivity());
        if (llmResult.isSend()) {
            return send(
                    context,
                    candidate,
                    "llm_allow: " + reason,
                    llmResult.getMessageIntent(),
                    sensitivity,
                    gateContext.metadata());
        }
        return skip(
                context,
                candidate,
                "llm_skip: " + reason,
                llmResult.getMessageIntent(),
                gateContext.metadataWithSensitivity(sensitivity));
    }

    /**
     * 返回第一个命中的硬门控原因。
     *
     * @param context 当前 tick 上下文。
     * @param candidate 待判断候选。
     * @param gateContext 门控上下文。
     * @return 未命中门控时返回 null。
     * @throws Exception 仓储统计失败时抛出异常。
     */
    private String hardGateReason(
            ProactiveTickContext context, ProactiveCandidateRecord candidate, GateContext gateContext)
            throws Exception {
        AppConfig.ProactiveConfig proactive = proactiveConfig(context);
        long now = nowMillis(context);
        if (proactive == null || !proactive.isEnabled()) {
            return "proactive_disabled";
        }
        if (!gateContext.homeChannelReady) {
            return "no_home_channel";
        }
        if (gateContext.quietHour) {
            return "quiet_hours";
        }
        if (candidate == null || isExpired(candidate, now)) {
            return "candidate_expired";
        }
        if (candidate.getConfidence() < proactive.getMinConfidenceToContact()) {
            return "confidence_below_threshold";
        }
        int dailyMax = proactive.getDailyMaxContacts();
        if (dailyMax > 0 && repository != null) {
            int sentToday = repository.countSentSince(null, startOfDayMillis(now));
            if (sentToday >= dailyMax) {
                return "daily_limit_reached";
            }
        }
        int cooldownMinutes = proactive.getCooldownMinutes();
        if (cooldownMinutes > 0 && repository != null) {
            Long lastSentAt = repository.findLastSentAt(null);
            if (lastSentAt != null
                    && now - lastSentAt.longValue() < cooldownMinutes * 60L * 1000L) {
                return "cooldown_active";
            }
        }
        if (gateContext.activeSources.contains(StrUtil.nullToEmpty(candidate.getSourceKey()))
                && !isRunRecoveryCandidate(candidate)) {
            return "active_run_for_source";
        }
        return null;
    }

    /**
     * 构造发送决策。
     *
     * @param context 当前 tick 上下文。
     * @param candidate 候选记录。
     * @param reason 决策原因。
     * @param messageIntent 消息意图。
     * @param sensitivity 敏感度。
     * @param metadata 附加元数据。
     * @return 返回发送决策。
     */
    private ProactiveDecision send(
            ProactiveTickContext context,
            ProactiveCandidateRecord candidate,
            String reason,
            String messageIntent,
            String sensitivity,
            Map<String, Object> metadata) {
        ProactiveDecision decision = baseDecision(context, candidate, "SEND", reason, messageIntent, metadata);
        decision.setSensitivity(normalizeSensitivity(sensitivity));
        return decision;
    }

    /**
     * 构造跳过决策。
     *
     * @param context 当前 tick 上下文。
     * @param candidate 候选记录。
     * @param reason 决策原因。
     * @param messageIntent 消息意图。
     * @param metadata 附加元数据。
     * @return 返回跳过决策。
     */
    private ProactiveDecision skip(
            ProactiveTickContext context,
            ProactiveCandidateRecord candidate,
            String reason,
            String messageIntent,
            Map<String, Object> metadata) {
        ProactiveDecision decision = baseDecision(context, candidate, "SKIP", reason, messageIntent, metadata);
        decision.setSensitivity("normal");
        return decision;
    }

    /**
     * 构造决策公共字段。
     *
     * @param context 当前 tick 上下文。
     * @param candidate 候选记录。
     * @param action 决策动作。
     * @param reason 决策原因。
     * @param messageIntent 消息意图。
     * @param metadata 附加元数据。
     * @return 返回决策对象。
     */
    private ProactiveDecision baseDecision(
            ProactiveTickContext context,
            ProactiveCandidateRecord candidate,
            String action,
            String reason,
            String messageIntent,
            Map<String, Object> metadata) {
        ProactiveDecision decision = new ProactiveDecision();
        decision.setDecisionId(IdSupport.newId());
        decision.setTickId(context.getTickId());
        decision.setCandidate(candidate);
        decision.setCandidateId(candidate == null ? null : candidate.getCandidateId());
        decision.setSourceKey(candidate == null ? null : candidate.getSourceKey());
        decision.setDecision(action);
        decision.setReason(StrUtil.blankToDefault(reason, "unspecified"));
        decision.setMessageIntent(StrUtil.nullToEmpty(messageIntent));
        decision.setMetadata(metadata == null ? new LinkedHashMap<String, Object>() : metadata);
        decision.setCreatedAt(nowMillis(context));
        return decision;
    }

    /**
     * 保存决策记录并同步候选状态。
     *
     * @param decision 决策结果。
     * @throws Exception 仓储写入失败时抛出异常。
     */
    private void persistDecision(ProactiveDecision decision) throws Exception {
        if (repository == null || decision == null) {
            return;
        }
        ProactiveDecisionRecord record = new ProactiveDecisionRecord();
        record.setDecisionId(decision.getDecisionId());
        record.setTickId(decision.getTickId());
        record.setCandidateId(decision.getCandidateId());
        record.setSourceKey(decision.getSourceKey());
        record.setDecision(decision.getDecision());
        record.setReason(decision.getReason());
        record.setMessage(decision.getMessageIntent());
        record.setMetadata(decision.getMetadata());
        record.setCreatedAt(decision.getCreatedAt());
        repository.saveDecision(record);
        if (StrUtil.isNotBlank(decision.getCandidateId())) {
            repository.markCandidateStatus(
                    decision.getCandidateId(),
                    "SEND".equals(decision.getDecision()) ? "APPROVED" : "SKIPPED",
                    decision.getDecisionId(),
                    decision.getCreatedAt());
        }
    }

    /**
     * 对候选进行确定性排序：优先级、置信度、创建时间、来源新鲜度。
     *
     * @param candidates 原始候选列表。
     * @return 返回排序后的候选列表。
     */
    private List<ProactiveCandidateRecord> rankedCandidates(List<ProactiveCandidateRecord> candidates) {
        List<ProactiveCandidateRecord> ranked = new ArrayList<ProactiveCandidateRecord>();
        for (ProactiveCandidateRecord candidate : candidates) {
            if (candidate != null) {
                ranked.add(candidate);
            }
        }
        Collections.sort(
                ranked,
                Comparator.comparingInt(ProactiveCandidateRecord::getPriority)
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(ProactiveCandidateRecord::getConfidence).reversed())
                        .thenComparingLong(ProactiveCandidateRecord::getCreatedAt)
                        .thenComparing(Comparator.comparingLong(ProactiveCandidateRecord::getUpdatedAt).reversed()));
        return ranked;
    }

    /**
     * 判断候选是否过期。
     *
     * @param candidate 候选记录。
     * @param nowMillis 当前时间。
     * @return 已过期返回 true。
     */
    private boolean isExpired(ProactiveCandidateRecord candidate, long nowMillis) {
        return candidate.getExpiresAt() > 0L && candidate.getExpiresAt() < nowMillis;
    }

    /**
     * 判断候选是否为同来源运行恢复候选，可越过活跃运行门控。
     *
     * @param candidate 候选记录。
     * @return 运行恢复候选返回 true。
     */
    @SuppressWarnings("unchecked")
    private boolean isRunRecoveryCandidate(ProactiveCandidateRecord candidate) {
        if (candidate == null) {
            return false;
        }
        if ("run".equalsIgnoreCase(candidate.getSourceType())
                && StrUtil.containsIgnoreCase(candidate.getReason(), "可恢复")) {
            return true;
        }
        Map<String, Object> evidence = candidate.getEvidence();
        Object payload = evidence == null ? null : evidence.get("payload");
        if (payload instanceof Map<?, ?>) {
            Object type = ((Map<String, Object>) payload).get("type");
            return "run_recoverable".equals(String.valueOf(type));
        }
        return false;
    }

    /**
     * 读取主动协作配置。
     *
     * @param context 当前 tick 上下文。
     * @return 返回主动协作配置。
     */
    private AppConfig.ProactiveConfig proactiveConfig(ProactiveTickContext context) {
        return context.getConfig() == null ? null : context.getConfig().getProactive();
    }

    /**
     * 读取最大单 tick 触达数。
     *
     * @param context 当前 tick 上下文。
     * @return 返回触达上限。
     */
    private int maxContactsPerTick(ProactiveTickContext context) {
        AppConfig.ProactiveConfig proactive = proactiveConfig(context);
        int value = proactive == null ? 1 : proactive.getMaxContactsPerTick();
        return Math.max(1, value);
    }

    /**
     * 判断是否启用模型软决策。
     *
     * @param context 当前 tick 上下文。
     * @return 启用模型判断返回 true。
     */
    private boolean llmDecisionEnabled(ProactiveTickContext context) {
        AppConfig.ProactiveConfig proactive = proactiveConfig(context);
        return proactive != null && proactive.isLlmDecisionEnabled();
    }

    /**
     * 读取当前 tick 时间。
     *
     * @param context 当前 tick 上下文。
     * @return 返回毫秒时间戳。
     */
    private long nowMillis(ProactiveTickContext context) {
        return context.getNowMillis() > 0L ? context.getNowMillis() : System.currentTimeMillis();
    }

    /**
     * 计算当前本地日期开始时间。
     *
     * @param nowMillis 当前时间。
     * @return 返回本地日期零点毫秒时间戳。
     */
    private long startOfDayMillis(long nowMillis) {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate date = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate();
        return date.atStartOfDay(zoneId).toInstant().toEpochMilli();
    }

    /**
     * 标准化敏感度标签。
     *
     * @param sensitivity 原始敏感度。
     * @return 返回 low、normal 或 high。
     */
    private String normalizeSensitivity(String sensitivity) {
        String value = StrUtil.nullToEmpty(sensitivity).trim().toLowerCase(java.util.Locale.ROOT);
        if ("low".equals(value) || "high".equals(value)) {
            return value;
        }
        return "normal";
    }

    /** 模型软决策客户端，便于测试注入和真实 LLM 适配解耦。 */
    public interface LlmDecisionClient {
        /**
         * 请求模型判断候选是否值得主动联系用户。
         *
         * @param context 当前 tick 上下文。
         * @param candidate 候选记录。
         * @return 返回结构化模型判断。
         * @throws Exception 模型调用或解析失败时抛出异常。
         */
        LlmDecisionResult decide(ProactiveTickContext context, ProactiveCandidateRecord candidate)
                throws Exception;
    }

    /** 模型软决策结构化结果。 */
    public static class LlmDecisionResult {
        /** 是否建议发送。 */
        private final boolean send;

        /** 模型给出的原因。 */
        private final String reason;

        /** 模型给出的消息意图。 */
        private final String messageIntent;

        /** 敏感度标签。 */
        private final String sensitivity;

        /**
         * 创建模型决策结果。
         *
         * @param send 是否建议发送。
         * @param reason 决策原因。
         * @param messageIntent 消息意图。
         * @param sensitivity 敏感度。
         */
        public LlmDecisionResult(
                boolean send, String reason, String messageIntent, String sensitivity) {
            this.send = send;
            this.reason = reason;
            this.messageIntent = messageIntent;
            this.sensitivity = sensitivity;
        }

        /** 返回是否建议发送。 */
        public boolean isSend() {
            return send;
        }

        /** 返回模型原因。 */
        public String getReason() {
            return reason;
        }

        /** 返回消息意图。 */
        public String getMessageIntent() {
            return messageIntent;
        }

        /** 返回敏感度标签。 */
        public String getSensitivity() {
            return sensitivity;
        }
    }

    /** 基于现有大模型网关的主动协作软决策客户端。 */
    public static class GatewayLlmDecisionClient implements LlmDecisionClient {
        /** 现有大模型网关。 */
        private final LlmGateway llmGateway;

        /**
         * 创建大模型网关适配器。
         *
         * @param llmGateway 大模型网关。
         */
        public GatewayLlmDecisionClient(LlmGateway llmGateway) {
            this.llmGateway = llmGateway;
        }

        /**
         * 调用模型并解析 JSON 决策。
         *
         * @param context 当前 tick 上下文。
         * @param candidate 候选记录。
         * @return 返回模型结构化判断。
         * @throws Exception 模型调用或 JSON 解析失败时抛出异常。
         */
        @Override
        public LlmDecisionResult decide(ProactiveTickContext context, ProactiveCandidateRecord candidate)
                throws Exception {
            if (llmGateway == null) {
                return new LlmDecisionResult(false, "llm_gateway_missing", "", "normal");
            }
            SessionRecord synthetic = new SessionRecord();
            synthetic.setSessionId("proactive-decision-" + IdSupport.newId());
            synthetic.setNdjson("");
            LlmResult result =
                    llmGateway.chat(
                            synthetic,
                            LLM_DECISION_SYSTEM_PROMPT,
                            decisionPrompt(candidate),
                            Collections.emptyList());
            String text = assistantText(result == null ? null : result.getAssistantMessage());
            return parseLlmDecision(text);
        }

        /**
         * 构造模型决策提示词，只包含候选摘要和约束，不包含敏感原始上下文全文。
         *
         * @param candidate 候选记录。
         * @return 返回用户提示词。
         */
        private String decisionPrompt(ProactiveCandidateRecord candidate) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("topic", candidate.getTopic());
            payload.put("title", candidate.getTitle());
            payload.put("summary", candidate.getSummary());
            payload.put("reason", candidate.getReason());
            payload.put("actionOffer", candidate.getActionOffer());
            payload.put("confidence", Double.valueOf(candidate.getConfidence()));
            payload.put("priority", Integer.valueOf(candidate.getPriority()));
            return "请判断这个主动协作候选是否值得现在联系用户。只输出 JSON。\n"
                    + ONode.serialize(payload);
        }

        /**
         * 从助手消息中提取文本。
         *
         * @param assistantMessage 助手消息。
         * @return 返回文本内容。
         */
        private String assistantText(AssistantMessage assistantMessage) {
            if (assistantMessage == null) {
                return "";
            }
            if (StrUtil.isNotBlank(assistantMessage.getResultContent())) {
                return assistantMessage.getResultContent().trim();
            }
            return StrUtil.nullToEmpty(assistantMessage.getContent()).trim();
        }

        /**
         * 解析模型输出 JSON。
         *
         * @param text 模型输出。
         * @return 返回结构化决策。
         */
        private LlmDecisionResult parseLlmDecision(String text) {
            if (StrUtil.isBlank(text)) {
                return new LlmDecisionResult(false, "empty_llm_response", "", "normal");
            }
            try {
                String json = extractJson(text);
                ONode node = ONode.ofJson(json);
                return new LlmDecisionResult(
                        node.get("send").getBoolean(),
                        node.get("reason").getString(),
                        node.get("message_intent").getString(),
                        node.get("sensitivity").getString());
            } catch (Exception ignored) {
                return new LlmDecisionResult(false, "invalid_llm_json", "", "normal");
            }
        }

        /**
         * 从可能带 Markdown 包裹的文本中提取 JSON 对象。
         *
         * @param text 原始文本。
         * @return 返回 JSON 对象文本。
         */
        private String extractJson(String text) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return text.substring(start, end + 1);
            }
            return text;
        }
    }

    /** 决策门控上下文。 */
    private static final class GateContext {
        /** 是否已有可用 home channel。 */
        private boolean homeChannelReady;

        /** 是否处于静默时间。 */
        private boolean quietHour;

        /** 当前仍有活跃运行占用的来源集合。 */
        private final Set<String> activeSources = new HashSet<String>();

        /**
         * 从 tick 上下文和 gate-only 观测构造门控上下文。
         *
         * @param context 当前 tick 上下文。
         * @param observations 当前观测。
         * @return 返回门控上下文。
         */
        @SuppressWarnings("unchecked")
        private static GateContext from(
                ProactiveTickContext context, List<ProactiveObservationRecord> observations) {
            GateContext gate = new GateContext();
            gate.homeChannelReady = hasHomeChannel(context);
            gate.quietHour = configuredQuietHour(context);
            if (observations == null) {
                return gate;
            }
            for (ProactiveObservationRecord observation : observations) {
                Map<String, Object> payload = observation == null ? null : observation.getPayload();
                if (payload == null || !"proactive_context".equals(String.valueOf(payload.get("type")))) {
                    continue;
                }
                Object homeReady = payload.get("homeChannelReady");
                if (homeReady instanceof Boolean) {
                    gate.homeChannelReady = ((Boolean) homeReady).booleanValue();
                }
                Object quiet = payload.get("quietHour");
                if (quiet instanceof Boolean) {
                    gate.quietHour = ((Boolean) quiet).booleanValue();
                }
                Object activeRuns = payload.get("activeRuns");
                if (activeRuns instanceof Iterable<?>) {
                    for (Object item : (Iterable<?>) activeRuns) {
                        if (item instanceof Map<?, ?>) {
                            Object sourceKey = ((Map<String, Object>) item).get("sourceKey");
                            if (sourceKey != null && StrUtil.isNotBlank(String.valueOf(sourceKey))) {
                                gate.activeSources.add(String.valueOf(sourceKey));
                            }
                        }
                    }
                }
            }
            return gate;
        }

        /**
         * 构造门控元数据。
         *
         * @return 返回元数据。
         */
        private Map<String, Object> metadata() {
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("homeChannelReady", Boolean.valueOf(homeChannelReady));
            metadata.put("quietHour", Boolean.valueOf(quietHour));
            metadata.put("activeSourceCount", Integer.valueOf(activeSources.size()));
            return metadata;
        }

        /**
         * 构造带敏感度的门控元数据。
         *
         * @param sensitivity 敏感度。
         * @return 返回元数据。
         */
        private Map<String, Object> metadataWithSensitivity(String sensitivity) {
            Map<String, Object> metadata = metadata();
            metadata.put("sensitivity", sensitivity);
            return metadata;
        }

        /**
         * 判断 tick 上下文是否配置了可用 home channel。
         *
         * @param context 当前 tick 上下文。
         * @return 有可用 home channel 返回 true。
         */
        private static boolean hasHomeChannel(ProactiveTickContext context) {
            if (context.getHomeChannels() == null) {
                return false;
            }
            for (HomeChannelRecord channel : context.getHomeChannels()) {
                if (channel != null && channel.getPlatform() != null && StrUtil.isNotBlank(channel.getChatId())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 仅基于配置计算静默时间，gate 观测存在时会覆盖该值。
         *
         * @param context 当前 tick 上下文。
         * @return 处于静默时间返回 true。
         */
        private static boolean configuredQuietHour(ProactiveTickContext context) {
            AppConfig.ProactiveConfig proactive =
                    context.getConfig() == null ? null : context.getConfig().getProactive();
            if (proactive == null) {
                return false;
            }
            int start = normalizeHour(proactive.getQuietStartHour());
            int end = normalizeHour(proactive.getQuietEndHour());
            if (start == end) {
                return false;
            }
            int hour =
                    LocalDateTime.ofInstant(
                                    Instant.ofEpochMilli(
                                            context.getNowMillis() > 0L
                                                    ? context.getNowMillis()
                                                    : System.currentTimeMillis()),
                                    ZoneId.systemDefault())
                            .getHour();
            if (start < end) {
                return hour >= start && hour < end;
            }
            return hour >= start || hour < end;
        }

        /**
         * 将小时配置夹紧到 0-23。
         *
         * @param hour 原始小时。
         * @return 返回合法小时。
         */
        private static int normalizeHour(int hour) {
            if (hour < 0) {
                return 0;
            }
            if (hour > 23) {
                return 23;
            }
            return hour;
        }
    }
}
