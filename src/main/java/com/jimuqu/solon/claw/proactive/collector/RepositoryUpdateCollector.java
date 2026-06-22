package com.jimuqu.solon.claw.proactive.collector;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.MemorySnapshot;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveSourceSnapshotRecord;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.proactive.ProactiveObservationCollector;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import com.jimuqu.solon.claw.proactive.RepositoryProbeService;
import com.jimuqu.solon.claw.proactive.RepositoryReferenceExtractor;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 仓库更新观测采集器，用于发现用户已关注仓库的首次状态或后续变化。 */
public class RepositoryUpdateCollector implements ProactiveObservationCollector {
    /** 采集器内部日志，仅记录输入源和异常类型，避免暴露记忆、会话或定时任务正文。 */
    private static final Logger log = LoggerFactory.getLogger(RepositoryUpdateCollector.class);

    /** 采集器稳定名称，用于观测来源、排障和候选生成识别。 */
    public static final String COLLECTOR_NAME = "repository_update";

    /** 仓库来源快照类型，和主动协作仓储中的 source_type 对齐。 */
    private static final String SNAPSHOT_SOURCE_TYPE = "repository";

    /** 输出给候选生成阶段的观测类型。 */
    private static final String OBSERVATION_TYPE = "project_update_opportunity";

    /** 单次 tick 最多探测仓库数，避免用户记忆或会话中引用过多时造成网络或进程压力。 */
    private static final int MAX_REPOSITORIES_PER_TICK = 20;

    /** 单次 tick 最多读取近期会话数。 */
    private static final int RECENT_SESSION_LIMIT = 120;

    /** 单次 tick 最多读取定时任务数。 */
    private static final int CRON_JOB_LIMIT = 120;

    /** 一天对应的毫秒数，用于按配置限制会话回看窗口。 */
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    /** 摘要最大长度，保持诊断和候选生成可读。 */
    private static final int SUMMARY_MAX_LENGTH = 240;

    /** 短文本字段最大长度，避免把长消息完整带入载荷。 */
    private static final int TEXT_MAX_LENGTH = 420;

    /** 主动协作仓储，用于读取和更新仓库状态快照。 */
    private final ProactiveRepository proactiveRepository;

    /** 仓库只读探测服务，用于读取当前 HEAD 或远程状态。 */
    private final RepositoryProbeService repositoryProbeService;

    /** 仓库引用提取器，用于从上下文文本中识别显式仓库引用。 */
    private final RepositoryReferenceExtractor referenceExtractor;

    /** 会话仓储，用于从近期会话中提取用户曾处理过的仓库。 */
    private final SessionRepository sessionRepository;

    /** 长期记忆服务，用于读取用户明确关注的仓库。 */
    private final MemoryService memoryService;

    /** 定时任务仓储，用于从计划任务提示词中提取仓库引用。 */
    private final CronJobRepository cronJobRepository;

    /**
     * 创建仓库更新观测采集器。
     *
     * @param proactiveRepository 主动协作仓储。
     * @param repositoryProbeService 仓库只读探测服务。
     * @param referenceExtractor 仓库引用提取器。
     * @param sessionRepository 会话仓储，可为空。
     * @param memoryService 记忆服务，可为空。
     * @param cronJobRepository 定时任务仓储，可为空。
     */
    public RepositoryUpdateCollector(
            ProactiveRepository proactiveRepository,
            RepositoryProbeService repositoryProbeService,
            RepositoryReferenceExtractor referenceExtractor,
            SessionRepository sessionRepository,
            MemoryService memoryService,
            CronJobRepository cronJobRepository) {
        this.proactiveRepository = proactiveRepository;
        this.repositoryProbeService = repositoryProbeService;
        this.referenceExtractor =
                referenceExtractor == null ? new RepositoryReferenceExtractor() : referenceExtractor;
        this.sessionRepository = sessionRepository;
        this.memoryService = memoryService;
        this.cronJobRepository = cronJobRepository;
    }

    /** 返回仓库更新采集器的稳定名称。 */
    @Override
    public String name() {
        return COLLECTOR_NAME;
    }

    /** 主动协作和仓库检查同时开启时才运行本采集器。 */
    @Override
    public boolean enabled(AppConfig config) {
        AppConfig.ProactiveConfig proactive = config == null ? null : config.getProactive();
        return proactive != null
                && proactive.isEnabled()
                && proactive.isRepositoryCheckEnabled();
    }

    /** 收集仓库引用，按快照判断是否产生项目更新机会观测。 */
    @Override
    public List<ProactiveObservation> collect(ProactiveTickContext context) throws Exception {
        List<ProactiveObservation> observations = new ArrayList<ProactiveObservation>();
        if (context == null
                || !enabled(context.getConfig())
                || proactiveRepository == null
                || repositoryProbeService == null) {
            return observations;
        }
        List<RepositoryReferenceExtractor.RepositoryReference> references = collectReferences(context);
        int inspected = 0;
        for (RepositoryReferenceExtractor.RepositoryReference reference : references) {
            if (reference == null || StrUtil.isBlank(reference.getRef())) {
                continue;
            }
            if (inspected >= MAX_REPOSITORIES_PER_TICK) {
                break;
            }
            ProactiveSourceSnapshotRecord previous =
                    proactiveRepository.findSnapshot(SNAPSHOT_SOURCE_TYPE, reference.getRef());
            if (shouldSkipByInterval(previous, context)) {
                continue;
            }
            inspected++;
            RepositoryProbeService.RepositoryState state = repositoryProbeService.probe(reference);
            if (state == null || StrUtil.isBlank(state.getStateHash())) {
                continue;
            }
            ProactiveObservation observation =
                    inspectState(reference, state, previous, context.getNowMillis());
            if (observation != null) {
                observations.add(observation);
            }
        }
        return observations;
    }

    /**
     * 汇总所有可用于仓库更新检测的显式引用。
     *
     * @param context 当前 tick 上下文。
     * @return 返回按仓库引用去重后的引用列表。
     */
    private List<RepositoryReferenceExtractor.RepositoryReference> collectReferences(
            ProactiveTickContext context) {
        Map<String, RepositoryReferenceExtractor.RepositoryReference> references =
                new LinkedHashMap<String, RepositoryReferenceExtractor.RepositoryReference>();
        addReferences(references, memoryReferences());
        addReferences(references, sessionReferences(context));
        addReferences(references, cronReferences());
        return new ArrayList<RepositoryReferenceExtractor.RepositoryReference>(references.values());
    }

    /**
     * 从长期记忆和当日记忆中提取仓库引用。
     *
     * @return 返回记忆仓库引用。
     */
    private List<RepositoryReferenceExtractor.RepositoryReference> memoryReferences() {
        List<RepositoryReferenceExtractor.RepositoryReference> references =
                new ArrayList<RepositoryReferenceExtractor.RepositoryReference>();
        if (memoryService == null) {
            return references;
        }
        try {
            MemorySnapshot snapshot = memoryService.loadSnapshot();
            if (snapshot == null) {
                return references;
            }
            references.addAll(referenceExtractor.extract("memory", "MEMORY.md", snapshot.getMemoryText()));
            references.addAll(referenceExtractor.extract("memory", "USER.md", snapshot.getUserText()));
            references.addAll(
                    referenceExtractor.extract("memory", "TODAY_MEMORY", snapshot.getDailyMemoryText()));
        } catch (Exception e) {
            logSourceReadFailure("memory", e);
        }
        return references;
    }

    /**
     * 从近期会话文本中提取仓库引用。
     *
     * @param context 当前 tick 上下文。
     * @return 返回会话仓库引用。
     */
    private List<RepositoryReferenceExtractor.RepositoryReference> sessionReferences(
            ProactiveTickContext context) {
        List<RepositoryReferenceExtractor.RepositoryReference> references =
                new ArrayList<RepositoryReferenceExtractor.RepositoryReference>();
        if (sessionRepository == null) {
            return references;
        }
        try {
            long cutoff = sessionCutoffMillis(context);
            List<SessionRecord> sessions = sessionRepository.listRecent(RECENT_SESSION_LIMIT);
            if (sessions == null) {
                return references;
            }
            for (SessionRecord session : sessions) {
                if (session == null || session.getUpdatedAt() < cutoff) {
                    continue;
                }
                references.addAll(
                        referenceExtractor.extract(
                                "session",
                                StrUtil.blankToDefault(session.getSessionId(), "unknown"),
                                sessionText(session)));
            }
        } catch (Exception e) {
            logSourceReadFailure("session", e);
        }
        return references;
    }

    /**
     * 从定时任务提示词和工作目录中提取仓库引用。
     *
     * @return 返回定时任务仓库引用。
     */
    private List<RepositoryReferenceExtractor.RepositoryReference> cronReferences() {
        List<RepositoryReferenceExtractor.RepositoryReference> references =
                new ArrayList<RepositoryReferenceExtractor.RepositoryReference>();
        if (cronJobRepository == null) {
            return references;
        }
        try {
            List<CronJobRecord> jobs = cronJobRepository.listAll();
            if (jobs == null) {
                return references;
            }
            int count = 0;
            for (CronJobRecord job : jobs) {
                if (job == null || count >= CRON_JOB_LIMIT) {
                    break;
                }
                count++;
                references.addAll(
                        referenceExtractor.extract(
                                "cron",
                                StrUtil.blankToDefault(job.getJobId(), "unknown"),
                                cronText(job)));
            }
        } catch (Exception e) {
            logSourceReadFailure("cron", e);
        }
        return references;
    }

    /**
     * 按配置计算会话回看起点，避免旧会话中的仓库长期重复参与探测。
     *
     * @param context 当前 tick 上下文。
     * @return 返回会话更新时间下限。
     */
    private long sessionCutoffMillis(ProactiveTickContext context) {
        int lookbackDays = context.getConfig().getProactive().getSessionLookbackDays();
        long safeDays = Math.max(1L, Math.min((long) lookbackDays, 3650L));
        long windowMillis = safeDays * DAY_MILLIS;
        long nowMillis = context.getNowMillis();
        return nowMillis < windowMillis ? 0L : nowMillis - windowMillis;
    }

    /**
     * 读取会话标题、摘要和消息文本。
     *
     * @param session 会话记录。
     * @return 返回可提取仓库引用的组合文本。
     */
    private String sessionText(SessionRecord session) {
        StringBuilder builder = new StringBuilder();
        append(builder, session.getTitle());
        append(builder, session.getCompressedSummary());
        try {
            List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
            for (ChatMessage message : messages) {
                if (message != null) {
                    append(builder, message.getContent());
                }
            }
        } catch (Exception e) {
            logSourceReadFailure("session_message", e);
            append(builder, session.getNdjson());
        }
        return builder.toString();
    }

    /**
     * 读取定时任务中可能包含仓库引用的字段。
     *
     * @param job 定时任务记录。
     * @return 返回组合文本。
     */
    private String cronText(CronJobRecord job) {
        StringBuilder builder = new StringBuilder();
        append(builder, job.getName());
        append(builder, job.getPrompt());
        append(builder, job.getWorkdir());
        append(builder, job.getScript());
        append(builder, job.getOriginJson());
        return builder.toString();
    }

    /**
     * 追加非空文本并换行。
     *
     * @param builder 文本构造器。
     * @param value 待追加文本。
     */
    private void append(StringBuilder builder, String value) {
        if (StrUtil.isNotBlank(value)) {
            builder.append(value).append('\n');
        }
    }

    /**
     * 将引用按仓库引用去重追加。
     *
     * @param target 目标去重映射。
     * @param references 新引用列表。
     */
    private void addReferences(
            Map<String, RepositoryReferenceExtractor.RepositoryReference> target,
            List<RepositoryReferenceExtractor.RepositoryReference> references) {
        if (references == null) {
            return;
        }
        for (RepositoryReferenceExtractor.RepositoryReference reference : references) {
            if (reference != null && StrUtil.isNotBlank(reference.getRef()) && !target.containsKey(reference.getRef())) {
                target.put(reference.getRef(), reference);
            }
        }
    }

    /**
     * 判断是否因为检查间隔未到而跳过探测。
     *
     * @param previous 既有快照。
     * @param context 当前 tick 上下文。
     * @return 未到检查间隔时返回 true。
     */
    private boolean shouldSkipByInterval(
            ProactiveSourceSnapshotRecord previous, ProactiveTickContext context) {
        if (previous == null) {
            return false;
        }
        int intervalMinutes = context.getConfig().getProactive().getRepositoryCheckIntervalMinutes();
        if (intervalMinutes <= 0) {
            return false;
        }
        long intervalMillis = Math.min((long) intervalMinutes, 24L * 60L) * 60L * 1000L;
        return context.getNowMillis() - previous.getCheckedAt() < intervalMillis;
    }

    /**
     * 比较探测结果与旧快照，保存新快照并在首次发现或状态变化时输出观测。
     *
     * @param reference 仓库引用。
     * @param state 当前仓库状态。
     * @param previous 旧快照。
     * @param nowMillis 当前时间。
     * @return 需要后续候选生成时返回观测，否则返回 null。
     */
    private ProactiveObservation inspectState(
            RepositoryReferenceExtractor.RepositoryReference reference,
            RepositoryProbeService.RepositoryState state,
            ProactiveSourceSnapshotRecord previous,
            long nowMillis)
            throws Exception {
        String previousHash = previous == null ? null : previous.getStateHash();
        boolean changed = previous == null || !StrUtil.equals(previousHash, state.getStateHash());
        proactiveRepository.saveSnapshot(snapshot(reference, state, nowMillis));
        if (!changed) {
            return null;
        }
        return buildObservation(reference, state, previousHash);
    }

    /**
     * 构造仓库状态快照。
     *
     * @param reference 仓库引用。
     * @param state 当前仓库状态。
     * @param nowMillis 当前检查时间。
     * @return 返回可保存的来源快照。
     */
    private ProactiveSourceSnapshotRecord snapshot(
            RepositoryReferenceExtractor.RepositoryReference reference,
            RepositoryProbeService.RepositoryState state,
            long nowMillis) {
        ProactiveSourceSnapshotRecord snapshot = new ProactiveSourceSnapshotRecord();
        snapshot.setSourceType(SNAPSHOT_SOURCE_TYPE);
        snapshot.setSourceRef(reference.getRef());
        snapshot.setStateHash(state.getStateHash());
        snapshot.setCheckedAt(nowMillis);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("displayName", safe(state.getDisplayName(), 160));
        payload.put("branch", safe(state.getBranch(), 120));
        payload.put("commitHash", safe(state.getCommitHash(), 120));
        payload.put("releaseId", safe(state.getReleaseId(), 120));
        payload.put("referenceSourceType", safe(reference.getSourceType(), 80));
        payload.put("referenceSourceRef", safe(reference.getSourceRef(), 160));
        snapshot.setPayload(payload);
        return snapshot;
    }

    /**
     * 构造项目更新机会观测。
     *
     * @param reference 仓库引用。
     * @param state 当前仓库状态。
     * @param previousHash 旧状态哈希，可为空。
     * @return 返回主动协作观测。
     */
    private ProactiveObservation buildObservation(
            RepositoryReferenceExtractor.RepositoryReference reference,
            RepositoryProbeService.RepositoryState state,
            String previousHash) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", OBSERVATION_TYPE);
        payload.put("sourceRef", safe(reference.getRef(), 220));
        payload.put("branch", safe(state.getBranch(), 120));
        payload.put("stateHash", safe(state.getStateHash(), 160));
        payload.put("commitHash", safe(state.getCommitHash(), 160));
        payload.put("releaseId", safe(state.getReleaseId(), 160));

        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("previousHash", previousHash == null ? null : safe(previousHash, 160));
        evidence.put("displayName", safe(state.getDisplayName(), 160));
        evidence.put("referenceSourceType", safe(reference.getSourceType(), 80));
        evidence.put("referenceSourceRef", safe(reference.getSourceRef(), 160));
        evidence.put("referenceEvidence", safe(reference.getEvidence(), TEXT_MAX_LENGTH));
        payload.put("evidence", evidence);

        ProactiveObservation observation = new ProactiveObservation();
        observation.setCollector(COLLECTOR_NAME);
        observation.setSourceKey(sourceKey(reference));
        observation.setSummary(summary(reference, state, previousHash));
        observation.setPayload(payload);
        observation.setStatus("COLLECTED");
        return observation;
    }

    /**
     * 生成观测摘要。
     *
     * @param reference 仓库引用。
     * @param state 当前仓库状态。
     * @param previousHash 旧状态哈希，可为空。
     * @return 返回脱敏后的短摘要。
     */
    private String summary(
            RepositoryReferenceExtractor.RepositoryReference reference,
            RepositoryProbeService.RepositoryState state,
            String previousHash) {
        String change = previousHash == null ? "首次记录" : "状态变化";
        return safe(
                "project_update_opportunity: 仓库「"
                        + StrUtil.blankToDefault(state.getDisplayName(), reference.getRef())
                        + "」"
                        + change
                        + "，分支 "
                        + StrUtil.blankToDefault(state.getBranch(), "HEAD"),
                SUMMARY_MAX_LENGTH);
    }

    /**
     * 构造稳定来源键。
     *
     * @param reference 仓库引用。
     * @return 返回来源键。
     */
    private String sourceKey(RepositoryReferenceExtractor.RepositoryReference reference) {
        return COLLECTOR_NAME + ":" + reference.getRef();
    }

    /**
     * 对文本做脱敏和长度限制。
     *
     * @param value 原始文本。
     * @param maxLength 最大保留长度。
     * @return 返回安全文本。
     */
    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), maxLength);
    }

    /**
     * 记录输入源读取失败的安全摘要，主动收集继续使用其它来源或降级文本。
     *
     * @param sourceType 输入源类型。
     * @param error 读取失败异常。
     */
    private void logSourceReadFailure(String sourceType, Exception error) {
        log.debug(
                "仓库更新采集输入源读取失败，跳过该来源或使用降级文本继续: source={}, error={}",
                StrUtil.blankToDefault(sourceType, "unknown"),
                exceptionSummary(error));
    }

    /**
     * 生成不包含异常消息正文的摘要，避免日志暴露仓库路径之外的上下文内容。
     *
     * @param error 读取失败异常。
     * @return 返回异常类型名称。
     */
    private String exceptionSummary(Exception error) {
        if (error == null) {
            return "unknown";
        }
        String simpleName = error.getClass().getSimpleName();
        return StrUtil.blankToDefault(simpleName, error.getClass().getName());
    }
}
