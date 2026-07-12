package com.jimuqu.solon.claw.proactive;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecision;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 主动协作固定间隔调度器，只负责 tick 边界和服务编排，不承载业务决策。 */
public class ProactiveScheduler {
    /** 日志对象。 */
    private static final Logger log = LoggerFactory.getLogger(ProactiveScheduler.class);

    /** 应用配置快照来源。 */
    private final AppConfig appConfig;

    /** 网关策略仓储，用于读取当前可用 home channel。 */
    private final GatewayPolicyRepository gatewayPolicyRepository;

    /** 观测采集服务。 */
    private final ProactiveObservationService observationService;

    /** 候选生成服务。 */
    private final ProactiveCandidateService candidateService;

    /** 决策服务。 */
    private final ProactiveDecisionService decisionService;

    /** 文案生成服务。 */
    private final ProactiveMessageComposer messageComposer;

    /** 投递服务。 */
    private final ProactiveDispatchService dispatchService;

    /** 主动协作仓储，用于记录 tick 级失败。 */
    private final ProactiveRepository repository;

    /** 单线程调度执行器。 */
    private ScheduledExecutorService executorService;

    /**
     * 创建主动协作调度器。
     *
     * @param appConfig 应用配置。
     * @param observationService 观测服务。
     * @param candidateService 候选服务。
     * @param decisionService 决策服务。
     * @param messageComposer 文案生成服务。
     * @param dispatchService 投递服务。
     * @param repository 主动协作仓储。
     */
    public ProactiveScheduler(
            AppConfig appConfig,
            ProactiveObservationService observationService,
            ProactiveCandidateService candidateService,
            ProactiveDecisionService decisionService,
            ProactiveMessageComposer messageComposer,
            ProactiveDispatchService dispatchService,
            ProactiveRepository repository) {
        this(
                appConfig,
                null,
                observationService,
                candidateService,
                decisionService,
                messageComposer,
                dispatchService,
                repository);
    }

    /**
     * 创建主动协作调度器。
     *
     * @param appConfig 应用配置。
     * @param gatewayPolicyRepository 网关策略仓储。
     * @param observationService 观测服务。
     * @param candidateService 候选服务。
     * @param decisionService 决策服务。
     * @param messageComposer 文案生成服务。
     * @param dispatchService 投递服务。
     * @param repository 主动协作仓储。
     */
    public ProactiveScheduler(
            AppConfig appConfig,
            GatewayPolicyRepository gatewayPolicyRepository,
            ProactiveObservationService observationService,
            ProactiveCandidateService candidateService,
            ProactiveDecisionService decisionService,
            ProactiveMessageComposer messageComposer,
            ProactiveDispatchService dispatchService,
            ProactiveRepository repository) {
        this.appConfig = appConfig;
        this.gatewayPolicyRepository = gatewayPolicyRepository;
        this.observationService = observationService;
        this.candidateService = candidateService;
        this.decisionService = decisionService;
        this.messageComposer = messageComposer;
        this.dispatchService = dispatchService;
        this.repository = repository;
    }

    /** 启动固定间隔调度；配置关闭或间隔无效时不创建执行器。 */
    public void start() {
        AppConfig.ProactiveConfig proactive = proactiveConfig();
        if (proactive == null || !proactive.isEnabled() || proactive.getIntervalMinutes() <= 0) {
            log.info("Proactive scheduler skipped: disabled or interval invalid");
            return;
        }
        if (executorService != null) {
            return;
        }
        executorService = Executors.newSingleThreadScheduledExecutor();
        long initialDelay = Math.max(0, proactive.getInitialDelaySeconds());
        long intervalSeconds = Math.max(60L, proactive.getIntervalMinutes() * 60L);
        executorService.scheduleWithFixedDelay(
                this::tickSafe, initialDelay, intervalSeconds, TimeUnit.SECONDS);
        log.info(
                "Proactive scheduler started: initialDelaySeconds={}, intervalMinutes={}",
                initialDelay,
                proactive.getIntervalMinutes());
    }

    /** 停止调度执行器。 */
    public void stop() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }

    /** Solon 销毁 Bean 时释放执行器。 */
    public void shutdown() {
        stop();
    }

    /** 捕获 tick 异常并尽量写入失败观测，避免调度线程静默退出。 */
    public void tickSafe() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("Proactive tick failed: error={}", safeError(e));
            persistTickFailure(e);
        }
    }

    /**
     * 执行一次主动协作 tick。
     *
     * @throws Exception 子服务执行失败时抛出异常，由 tickSafe 捕获并审计。
     */
    public void tick() throws Exception {
        AppConfig.ProactiveConfig proactive = proactiveConfig();
        if (proactive == null || !proactive.isEnabled() || proactive.getIntervalMinutes() <= 0) {
            log.debug("Proactive tick skipped: disabled");
            return;
        }
        if (repository != null) {
            repository.recoverInterruptedDeliveries(System.currentTimeMillis());
        }
        ProactiveTickContext context = context();
        log.info("Proactive tick started: tickId={}", context.getTickId());
        List<ProactiveObservationRecord> observations = observationService.collectAll(context);
        List<ProactiveCandidateRecord> generated = candidateService.generate(context, observations);
        List<ProactiveCandidateRecord> candidates = candidatesForDecision(context, generated);
        List<ProactiveDecision> decisions =
                decisionService.decide(context, candidates, observations);
        for (ProactiveDecision decision : decisions) {
            if (decision == null
                    || !"SEND".equalsIgnoreCase(StrUtil.nullToEmpty(decision.getDecision()))) {
                continue;
            }
            String message = messageComposer.compose(context, decision);
            dispatchService.dispatch(decision, message);
        }
        log.info(
                "Proactive tick finished: tickId={}, observations={}, candidates={}, decisions={}",
                context.getTickId(),
                observations == null ? 0 : observations.size(),
                candidates == null ? 0 : candidates.size(),
                decisions == null ? 0 : decisions.size());
    }

    /**
     * 构造 tick 上下文。
     *
     * @return 返回当前 tick 上下文。
     */
    private ProactiveTickContext context() {
        ProactiveTickContext context = new ProactiveTickContext();
        context.setTickId("proactive-" + IdSupport.newId());
        context.setNowMillis(System.currentTimeMillis());
        context.setConfig(appConfig);
        context.setHomeChannels(loadHomeChannels());
        try {
            context.setLastDecisionSummaries(repository.listRecentDecisions(20));
        } catch (Exception e) {
            log.debug("Proactive recent decisions unavailable: error={}", safeError(e));
        }
        return context;
    }

    /**
     * 读取当前已配置的国内 home channel，供门控和后续采集器判断投递前置条件。
     *
     * @return 返回有效 home channel 列表。
     */
    private List<HomeChannelRecord> loadHomeChannels() {
        List<HomeChannelRecord> homes = new ArrayList<HomeChannelRecord>();
        if (gatewayPolicyRepository == null) {
            return homes;
        }
        for (PlatformType platform : PlatformType.DOMESTIC_PLATFORMS) {
            try {
                HomeChannelRecord home = gatewayPolicyRepository.getHomeChannel(platform);
                if (home != null
                        && home.getPlatform() != null
                        && StrUtil.isNotBlank(home.getChatId())) {
                    homes.add(home);
                }
            } catch (Exception e) {
                log.debug(
                        "Proactive home channel unavailable: platform={}, error={}",
                        platform,
                        safeError(e));
            }
        }
        return homes;
    }

    /**
     * 合并本 tick 新候选与仍有效的待处理候选，避免历史候选被持续产生的新候选饿死。
     *
     * @param context 当前 tick 上下文。
     * @param generated 本 tick 新生成候选。
     * @return 返回待决策候选。
     * @throws Exception 仓储查询失败时抛出异常。
     */
    private List<ProactiveCandidateRecord> candidatesForDecision(
            ProactiveTickContext context, List<ProactiveCandidateRecord> generated)
            throws Exception {
        int limit =
                appConfig == null || appConfig.getProactive() == null
                        ? 20
                        : Math.max(1, appConfig.getProactive().getMaxCandidatesPerTick());
        List<ProactiveCandidateRecord> pending =
                repository.listPendingCandidates(context.getNowMillis(), limit);
        Map<String, ProactiveCandidateRecord> merged =
                new LinkedHashMap<String, ProactiveCandidateRecord>();
        mergeCandidates(merged, generated);
        mergeCandidates(merged, pending);
        return new ArrayList<ProactiveCandidateRecord>(merged.values());
    }

    /** 按候选 ID 合并列表，保留本 tick 生成的较新对象。 */
    private void mergeCandidates(
            Map<String, ProactiveCandidateRecord> merged,
            List<ProactiveCandidateRecord> candidates) {
        if (candidates == null) {
            return;
        }
        for (ProactiveCandidateRecord candidate : candidates) {
            if (candidate == null || StrUtil.isBlank(candidate.getCandidateId())) {
                continue;
            }
            if (!merged.containsKey(candidate.getCandidateId())) {
                merged.put(candidate.getCandidateId(), candidate);
            }
        }
    }

    /**
     * 读取主动协作配置。
     *
     * @return 返回配置对象。
     */
    private AppConfig.ProactiveConfig proactiveConfig() {
        return appConfig == null ? null : appConfig.getProactive();
    }

    /**
     * 尽量保存 tick 级失败观测。
     *
     * @param error tick 异常。
     */
    private void persistTickFailure(Exception error) {
        if (repository == null) {
            return;
        }
        ProactiveObservationRecord observation = new ProactiveObservationRecord();
        observation.setObservationId(IdSupport.newId());
        observation.setTickId("proactive-failed-" + IdSupport.newId());
        observation.setCollector("proactive_scheduler");
        observation.setSourceKey("proactive_scheduler");
        observation.setSummary("主动协作 tick 执行失败");
        observation.setStatus("FAILED");
        observation.setError(safeError(error));
        observation.setCreatedAt(System.currentTimeMillis());
        try {
            repository.saveObservation(observation);
        } catch (Exception e) {
            log.warn("Proactive tick failure could not be persisted: error={}", safeError(e));
        }
    }

    /**
     * 生成脱敏错误摘要。
     *
     * @param error 异常。
     * @return 返回安全错误文本。
     */
    private String safeError(Exception error) {
        if (error == null) {
            return "";
        }
        return SecretRedactor.redact(
                error.getClass().getSimpleName() + ": " + StrUtil.nullToEmpty(error.getMessage()),
                500);
    }
}
