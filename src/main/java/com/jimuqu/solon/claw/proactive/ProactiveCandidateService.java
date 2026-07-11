package com.jimuqu.solon.claw.proactive;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.support.IdSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 主动协作候选生成服务，负责把结构化观测转换为可审计、可去重的候选事项。 */
public class ProactiveCandidateService {
    /** 高优先级，用于投递失败和可恢复失败运行。 */
    private static final int PRIORITY_HIGH = 90;

    /** 中高优先级，用于用户近期工作相关的仓库更新。 */
    private static final int PRIORITY_MEDIUM_HIGH = 75;

    /** 中优先级，用于会话续接。 */
    private static final int PRIORITY_MEDIUM = 60;

    /** 中低优先级，用于长期记忆中的知识跟进。 */
    private static final int PRIORITY_MEDIUM_LOW = 45;

    /** 低优先级，用于无具体事项时的关怀式询问。 */
    private static final int PRIORITY_LOW = 20;

    /** 主动协作仓储，用于保存候选并查询去重记录。 */
    private final ProactiveRepository repository;

    /**
     * 创建主动协作候选生成服务。
     *
     * @param repository 主动协作仓储。
     */
    public ProactiveCandidateService(ProactiveRepository repository) {
        this.repository = repository;
    }

    /**
     * 将观测记录转换为候选记录并持久化。
     *
     * @param context 当前 tick 上下文。
     * @param observations 已落库的观测记录。
     * @return 返回本次新生成并保存的候选列表。
     * @throws Exception 仓储读写失败时抛出异常。
     */
    public List<ProactiveCandidateRecord> generate(
            ProactiveTickContext context, List<ProactiveObservationRecord> observations)
            throws Exception {
        List<ProactiveCandidateRecord> candidates = new ArrayList<ProactiveCandidateRecord>();
        if (context == null
                || repository == null
                || observations == null
                || observations.isEmpty()) {
            return candidates;
        }
        int limit = maxCandidatesPerTick(context);
        for (ProactiveObservationRecord observation : observations) {
            if (candidates.size() >= limit) {
                break;
            }
            ProactiveCandidateRecord candidate = toCandidate(context, observation);
            if (candidate == null || isDuplicate(candidate, context.getNowMillis())) {
                continue;
            }
            repository.saveCandidate(candidate);
            candidates.add(candidate);
        }
        return candidates;
    }

    /**
     * 根据观测类型构造候选记录。
     *
     * @param context 当前 tick 上下文。
     * @param observation 观测记录。
     * @return 命中支持类型时返回候选，否则返回 null。
     */
    private ProactiveCandidateRecord toCandidate(
            ProactiveTickContext context, ProactiveObservationRecord observation) {
        if (observation == null || isFailedObservationStatus(observation.getStatus())) {
            return null;
        }
        Map<String, Object> payload = safePayload(observation.getPayload());
        if (isGateOnly(payload)) {
            return null;
        }
        String type = text(payload, "type");
        if (StrUtil.isBlank(type)) {
            type = StrUtil.nullToEmpty(observation.getCollector());
        }
        if (isRunObservation(type)) {
            return runCandidate(context, observation, payload, type);
        }
        if (isCronObservation(type)) {
            return cronCandidate(context, observation, payload, type);
        }
        if ("project_update_opportunity".equals(type)) {
            return repositoryCandidate(context, observation, payload);
        }
        if ("session_continuation".equals(type)) {
            return sessionCandidate(context, observation, payload);
        }
        if ("knowledge_followup".equals(type)) {
            return memoryCandidate(context, observation, payload);
        }
        if ("care_checkin".equals(type)) {
            return careCandidate(context, observation, payload);
        }
        return null;
    }

    /**
     * 构造运行状态候选。
     *
     * @param context 当前 tick 上下文。
     * @param observation 观测记录。
     * @param payload 观测载荷。
     * @param type 观测类型。
     * @return 返回运行状态候选。
     */
    private ProactiveCandidateRecord runCandidate(
            ProactiveTickContext context,
            ProactiveObservationRecord observation,
            Map<String, Object> payload,
            String type) {
        String runId = text(payload, "runId");
        String status = text(payload, "status");
        if (StrUtil.isBlank(runId)) {
            return null;
        }
        String stateHash = sourceStateHash(payload, runId + ":" + status + ":" + type);
        ProactiveCandidateRecord candidate =
                baseCandidate(context, observation, "run", runId, "run", runId, stateHash);
        candidate.setTopic("work_continuation");
        candidate.setTitle("运行需要续接处理");
        candidate.setSummary(summary(observation, "运行 " + runId + " 需要继续评估"));
        candidate.setReason(runReason(type, status));
        candidate.setActionOffer("询问用户是否需要我继续排查或恢复这次任务");
        candidate.setConfidence("run_recoverable".equals(type) ? 0.86D : 0.78D);
        candidate.setPriority("run_recoverable".equals(type) ? PRIORITY_HIGH : PRIORITY_MEDIUM);
        candidate.setDedupKey(ProactiveDedupSupport.runKey(runId, status));
        candidate.setEvidence(evidence(observation, payload));
        return candidate;
    }

    /**
     * 构造定时任务跟进候选。
     *
     * @param context 当前 tick 上下文。
     * @param observation 观测记录。
     * @param payload 观测载荷。
     * @param type 观测类型。
     * @return 返回定时任务候选。
     */
    private ProactiveCandidateRecord cronCandidate(
            ProactiveTickContext context,
            ProactiveObservationRecord observation,
            Map<String, Object> payload,
            String type) {
        String jobId = text(payload, "jobId");
        String lastStatus =
                firstNonBlank(text(payload, "lastStatus"), text(payload, "status"), type);
        long lastRunAt = number(nestedMap(payload, "evidence").get("lastRunAt"), 0L);
        if (StrUtil.isBlank(jobId)) {
            return null;
        }
        String stateSeed = jobId + ":" + lastStatus + ":" + lastRunAt + ":" + type;
        ProactiveCandidateRecord candidate =
                baseCandidate(
                        context,
                        observation,
                        "cron",
                        jobId,
                        "cron_job",
                        jobId,
                        sourceStateHash(payload, stateSeed));
        candidate.setTopic("cron_followup");
        candidate.setTitle("定时任务需要跟进");
        candidate.setSummary(summary(observation, "定时任务 " + jobId + " 需要确认"));
        candidate.setReason(cronReason(type, lastStatus));
        candidate.setActionOffer("向用户说明任务状态，并询问是否需要协助处理");
        candidate.setConfidence(isCronFailure(type) ? 0.84D : 0.70D);
        candidate.setPriority(isCronFailure(type) ? PRIORITY_HIGH : PRIORITY_MEDIUM);
        candidate.setDedupKey(ProactiveDedupSupport.cronKey(jobId, lastStatus, lastRunAt));
        candidate.setEvidence(evidence(observation, payload));
        return candidate;
    }

    /**
     * 构造仓库更新候选。
     *
     * @param context 当前 tick 上下文。
     * @param observation 观测记录。
     * @param payload 观测载荷。
     * @return 返回仓库更新候选。
     */
    private ProactiveCandidateRecord repositoryCandidate(
            ProactiveTickContext context,
            ProactiveObservationRecord observation,
            Map<String, Object> payload) {
        String sourceRef = text(payload, "sourceRef");
        String branch = firstNonBlank(text(payload, "branch"), "HEAD");
        String stateHash = text(payload, "stateHash");
        if (StrUtil.isBlank(sourceRef) || StrUtil.isBlank(stateHash)) {
            return null;
        }
        ProactiveCandidateRecord candidate =
                baseCandidate(
                        context,
                        observation,
                        "repository",
                        sourceRef,
                        "repository",
                        sourceRef,
                        stateHash);
        candidate.setTopic("project_update_opportunity");
        candidate.setTitle("关注的项目有更新");
        candidate.setSummary(summary(observation, "仓库 " + sourceRef + " 有新的状态变化"));
        candidate.setReason("用户之前处理过该项目，当前仓库状态已变化");
        candidate.setActionOffer("询问用户是否需要我查看更新内容并判断与之前工作的关系");
        candidate.setConfidence(0.76D);
        candidate.setPriority(PRIORITY_MEDIUM_HIGH);
        candidate.setDedupKey(ProactiveDedupSupport.repositoryKey(sourceRef, branch, stateHash));
        candidate.setEvidence(evidence(observation, payload));
        return candidate;
    }

    /**
     * 构造会话续接候选。
     *
     * @param context 当前 tick 上下文。
     * @param observation 观测记录。
     * @param payload 观测载荷。
     * @return 返回会话续接候选。
     */
    private ProactiveCandidateRecord sessionCandidate(
            ProactiveTickContext context,
            ProactiveObservationRecord observation,
            Map<String, Object> payload) {
        String sessionId = text(payload, "sessionId");
        long updatedAt = number(payload.get("updatedAt"), observation.getCreatedAt());
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        ProactiveCandidateRecord candidate =
                baseCandidate(
                        context,
                        observation,
                        "session",
                        sessionId,
                        "session",
                        sessionId,
                        sourceStateHash(payload, sessionId + ":" + updatedAt));
        candidate.setTopic("work_continuation");
        candidate.setTitle("之前的会话可以继续推进");
        candidate.setSummary(summary(observation, "会话 " + sessionId + " 有续接信号"));
        candidate.setReason("近期会话中存在未完成目标、等待确认或继续处理线索");
        candidate.setActionOffer("询问用户是否要从该会话继续协作");
        candidate.setConfidence(0.72D);
        candidate.setPriority(PRIORITY_MEDIUM);
        candidate.setDedupKey(ProactiveDedupSupport.sessionKey(sessionId, updatedAt));
        candidate.setEvidence(evidence(observation, payload));
        return candidate;
    }

    /**
     * 构造记忆知识跟进候选。
     *
     * @param context 当前 tick 上下文。
     * @param observation 观测记录。
     * @param payload 观测载荷。
     * @return 返回知识跟进候选。
     */
    private ProactiveCandidateRecord memoryCandidate(
            ProactiveTickContext context,
            ProactiveObservationRecord observation,
            Map<String, Object> payload) {
        Map<String, Object> evidence = nestedMap(payload, "evidence");
        if (evidence.isEmpty()) {
            return null;
        }
        String evidenceHash = ProactiveDedupSupport.evidenceHash(evidence);
        String topic = firstNonBlank(text(payload, "topic"), "知识跟进");
        String sourceRef = firstNonBlank(text(payload, "sourceRef"), observation.getSourceKey());
        ProactiveCandidateRecord candidate =
                baseCandidate(
                        context,
                        observation,
                        "memory",
                        sourceRef,
                        "memory",
                        sourceRef,
                        evidenceHash);
        candidate.setTopic("knowledge_followup");
        candidate.setTitle("可以基于已知信息主动确认");
        candidate.setSummary(summary(observation, topic));
        candidate.setReason("长期记忆中存在可跟进的工作线索");
        candidate.setActionOffer("询问用户最近是否有相关工作需要协作");
        candidate.setConfidence(
                "high".equalsIgnoreCase(text(payload, "confidenceHint")) ? 0.74D : 0.66D);
        candidate.setPriority(PRIORITY_MEDIUM_LOW);
        candidate.setDedupKey(ProactiveDedupSupport.memoryKey(evidence));
        candidate.setEvidence(evidence(observation, payload));
        return candidate;
    }

    /**
     * 构造关怀式确认候选。
     *
     * @param context 当前 tick 上下文。
     * @param observation 观测记录。
     * @param payload 观测载荷。
     * @return 返回关怀候选。
     */
    private ProactiveCandidateRecord careCandidate(
            ProactiveTickContext context,
            ProactiveObservationRecord observation,
            Map<String, Object> payload) {
        String sourceRef =
                firstNonBlank(text(payload, "sourceRef"), observation.getSourceKey(), "care");
        String stateHash = sourceStateHash(payload, sourceRef + ":" + context.getNowMillis());
        ProactiveCandidateRecord candidate =
                baseCandidate(
                        context, observation, "care", sourceRef, "user", sourceRef, stateHash);
        candidate.setTopic("care_checkin");
        candidate.setTitle("主动询问是否需要协作");
        candidate.setSummary(summary(observation, "询问用户最近是否有工作需要协作"));
        candidate.setReason("近期没有更高优先级候选，可低频询问用户当前需要");
        candidate.setActionOffer("询问用户是否有任务、项目或资料需要一起处理");
        candidate.setConfidence(0.62D);
        candidate.setPriority(PRIORITY_LOW);
        candidate.setDedupKey("care:" + sourceRef + ":" + stateHash);
        candidate.setEvidence(evidence(observation, payload));
        return candidate;
    }

    /**
     * 构造候选记录公共字段。
     *
     * @param context 当前 tick 上下文。
     * @param observation 来源观测。
     * @param sourceType 来源类型。
     * @param sourceRef 来源引用。
     * @param subjectType 主体类型。
     * @param subjectRef 主体引用。
     * @param stateHash 源状态哈希。
     * @return 返回带公共字段的候选记录。
     */
    private ProactiveCandidateRecord baseCandidate(
            ProactiveTickContext context,
            ProactiveObservationRecord observation,
            String sourceType,
            String sourceRef,
            String subjectType,
            String subjectRef,
            String stateHash) {
        long now =
                context.getNowMillis() > 0L ? context.getNowMillis() : System.currentTimeMillis();
        ProactiveCandidateRecord candidate = new ProactiveCandidateRecord();
        candidate.setCandidateId(IdSupport.newId());
        candidate.setSourceType(sourceType);
        candidate.setSourceRef(sourceRef);
        candidate.setSourceKey(firstNonBlank(observation.getSourceKey(), sourceRef));
        candidate.setSubjectType(subjectType);
        candidate.setSubjectRef(subjectRef);
        candidate.setStateHash(stateHash);
        candidate.setCreatedAt(now);
        candidate.setExpiresAt(expiresAt(context, now));
        candidate.setStatus("PENDING");
        candidate.setUpdatedAt(now);
        return candidate;
    }

    /**
     * 判断候选是否已有未过期同状态记录。
     *
     * @param candidate 待保存候选。
     * @param nowMillis 当前时间。
     * @return 已存在同去重键与状态哈希时返回 true。
     * @throws Exception 仓储读取失败时抛出异常。
     */
    private boolean isDuplicate(ProactiveCandidateRecord candidate, long nowMillis)
            throws Exception {
        if (StrUtil.isBlank(candidate.getDedupKey()) || StrUtil.isBlank(candidate.getStateHash())) {
            return false;
        }
        return repository.findRecentCandidateByDedup(
                        candidate.getDedupKey(), candidate.getStateHash(), nowMillis)
                != null;
    }

    /**
     * 构造候选证据，保留观测 ID、采集器、摘要和原始结构化载荷。
     *
     * @param observation 来源观测。
     * @param payload 观测载荷。
     * @return 返回候选证据。
     */
    private Map<String, Object> evidence(
            ProactiveObservationRecord observation, Map<String, Object> payload) {
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("observationId", observation.getObservationId());
        evidence.put("collector", observation.getCollector());
        evidence.put("summary", observation.getSummary());
        evidence.put("payload", payload);
        return evidence;
    }

    /**
     * 从观测载荷读取源状态哈希；没有显式状态时基于源字段生成兜底哈希。
     *
     * @param payload 观测载荷。
     * @param fallbackSeed 兜底状态种子。
     * @return 返回状态哈希。
     */
    private String sourceStateHash(Map<String, Object> payload, String fallbackSeed) {
        String stateHash = text(payload, "stateHash");
        if (StrUtil.isNotBlank(stateHash)) {
            return stateHash;
        }
        return ProactiveDedupSupport.sha256Hex(fallbackSeed);
    }

    /**
     * 根据配置计算候选过期时间。
     *
     * @param context 当前 tick 上下文。
     * @param nowMillis 当前时间。
     * @return 返回过期时间戳，0 表示不过期。
     */
    private long expiresAt(ProactiveTickContext context, long nowMillis) {
        AppConfig.ProactiveConfig proactive =
                context.getConfig() == null ? null : context.getConfig().getProactive();
        int ttlHours = proactive == null ? 72 : proactive.getCandidateTtlHours();
        if (ttlHours <= 0) {
            return 0L;
        }
        long safeHours = Math.min((long) ttlHours, 24L * 3650L);
        return nowMillis + safeHours * 60L * 60L * 1000L;
    }

    /**
     * 读取单次 tick 的最大候选数量。
     *
     * @param context 当前 tick 上下文。
     * @return 返回至少为 1 的候选上限。
     */
    private int maxCandidatesPerTick(ProactiveTickContext context) {
        AppConfig.ProactiveConfig proactive =
                context.getConfig() == null ? null : context.getConfig().getProactive();
        int limit = proactive == null ? 20 : proactive.getMaxCandidatesPerTick();
        return Math.max(1, limit);
    }

    /**
     * 判断观测是否只是门控上下文，不应生成面向用户的候选。
     *
     * @param payload 观测载荷。
     * @return gateOnly 为 true 或类型为 proactive_context 时返回 true。
     */
    private boolean isGateOnly(Map<String, Object> payload) {
        return bool(payload.get("gateOnly")) || "proactive_context".equals(text(payload, "type"));
    }

    /**
     * 判断观测状态是否表示失败，失败观测只进入审计记录，不生成用户可见候选。
     *
     * @param status 观测状态。
     * @return 明确失败或错误状态返回 true。
     */
    private boolean isFailedObservationStatus(String status) {
        String value = StrUtil.nullToEmpty(status).trim();
        return "FAILED".equalsIgnoreCase(value) || "ERROR".equalsIgnoreCase(value);
    }

    /**
     * 判断是否为运行状态观测。
     *
     * @param type 观测类型。
     * @return 命中运行状态类型时返回 true。
     */
    private boolean isRunObservation(String type) {
        return "run_recoverable".equals(type)
                || "verification_failed".equals(type)
                || "run_failed_needs_followup".equals(type)
                || "queued_work_waiting".equals(type);
    }

    /**
     * 判断是否为定时任务观测。
     *
     * @param type 观测类型。
     * @return 命中定时任务类型时返回 true。
     */
    private boolean isCronObservation(String type) {
        return "cron_delivery_error".equals(type)
                || "cron_repeated_failure".equals(type)
                || "cron_due_not_run".equals(type)
                || "cron_actionable_output".equals(type)
                || "cron_paused_visible_reason".equals(type);
    }

    /**
     * 判断定时任务观测是否属于失败类高优先级信号。
     *
     * @param type 观测类型。
     * @return 失败类信号返回 true。
     */
    private boolean isCronFailure(String type) {
        return "cron_delivery_error".equals(type) || "cron_repeated_failure".equals(type);
    }

    /**
     * 生成人类可读的运行候选原因。
     *
     * @param type 运行观测类型。
     * @param status 运行状态。
     * @return 返回原因文本。
     */
    private String runReason(String type, String status) {
        if ("run_recoverable".equals(type)) {
            return "运行处于可恢复状态，适合询问用户是否继续";
        }
        if ("verification_failed".equals(type)) {
            return "运行中出现测试、构建或验证失败，需要确认是否继续处理";
        }
        if ("queued_work_waiting".equals(type)) {
            return "已有排队消息或等待状态，可能需要用户确认下一步";
        }
        return "运行状态为 " + StrUtil.blankToDefault(status, "未知") + "，存在跟进信号";
    }

    /**
     * 生成人类可读的定时任务候选原因。
     *
     * @param type 定时任务观测类型。
     * @param lastStatus 最近状态。
     * @return 返回原因文本。
     */
    private String cronReason(String type, String lastStatus) {
        if ("cron_delivery_error".equals(type)) {
            return "定时任务结果投递失败，需要确认是否补发或排查渠道";
        }
        if ("cron_repeated_failure".equals(type)) {
            return "定时任务连续失败，需要用户决定是否继续修复";
        }
        if ("cron_due_not_run".equals(type)) {
            return "定时任务已到期但没有新的运行记录";
        }
        if ("cron_actionable_output".equals(type)) {
            return "定时任务输出中包含需要人工确认的事项";
        }
        if ("cron_paused_visible_reason".equals(type)) {
            return "定时任务暂停且有明确原因，可询问是否恢复";
        }
        return "定时任务最近状态为 " + StrUtil.blankToDefault(lastStatus, "未知");
    }

    /**
     * 优先使用观测摘要，缺失时使用兜底摘要。
     *
     * @param observation 来源观测。
     * @param fallback 兜底摘要。
     * @return 返回候选摘要。
     */
    private String summary(ProactiveObservationRecord observation, String fallback) {
        return firstNonBlank(observation.getSummary(), fallback);
    }

    /**
     * 安全读取载荷 Map。
     *
     * @param payload 原始载荷。
     * @return 返回非 null 载荷。
     */
    private Map<String, Object> safePayload(Map<String, Object> payload) {
        return payload == null ? new LinkedHashMap<String, Object>() : payload;
    }

    /**
     * 读取嵌套 Map 字段。
     *
     * @param payload 父载荷。
     * @param key 字段名。
     * @return 字段不是 Map 时返回空 Map。
     */
    private Map<String, Object> nestedMap(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Map<?, ?>)) {
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    /**
     * 从 Map 中读取文本字段。
     *
     * @param payload 载荷。
     * @param key 字段名。
     * @return 返回字段文本。
     */
    private String text(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 读取布尔字段。
     *
     * @param value 原始字段值。
     * @return 返回布尔值。
     */
    private boolean bool(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return "true".equalsIgnoreCase(StrUtil.nullToEmpty(String.valueOf(value)));
    }

    /**
     * 读取 long 数值字段。
     *
     * @param value 原始字段值。
     * @param fallback 解析失败时的兜底值。
     * @return 返回 long 数值。
     */
    private long number(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(StrUtil.nullToEmpty(String.valueOf(value)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 读取第一个非空文本。
     *
     * @param values 候选文本列表。
     * @return 返回第一个非空文本。
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }
}
