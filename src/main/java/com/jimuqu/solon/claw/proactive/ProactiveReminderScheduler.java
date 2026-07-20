package com.jimuqu.solon.claw.proactive;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.MemorySnapshot;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.support.constants.ContextFileConstants;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 按工作区规则和主会话记忆生成主动提醒，不维护独立候选或决策流水线。 */
public class ProactiveReminderScheduler {
    /** 主动提醒调度日志。 */
    private static final Logger log = LoggerFactory.getLogger(ProactiveReminderScheduler.class);

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 会话仓储，用于定位主对话和提供历史。 */
    private final SessionRepository sessionRepository;

    /** 网关策略仓储，用于限定提醒只能发送到显式配置的主对话。 */
    private final GatewayPolicyRepository gatewayPolicyRepository;

    /** 记忆服务。 */
    private final MemoryService memoryService;

    /** 模型网关。 */
    private final LlmGateway llmGateway;

    /** 国内渠道统一投递服务。 */
    private final DeliveryService deliveryService;

    /** 工作区 MD 文件服务。 */
    private final PersonaWorkspaceService personaWorkspaceService;

    /** 主动提醒轻量状态仓储。 */
    private final ProactiveReminderStateStore stateStore;

    /** 单线程定时执行器。 */
    private ScheduledExecutorService executorService;

    /** 创建主动提醒调度器。 */
    public ProactiveReminderScheduler(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            GatewayPolicyRepository gatewayPolicyRepository,
            MemoryService memoryService,
            LlmGateway llmGateway,
            DeliveryService deliveryService,
            PersonaWorkspaceService personaWorkspaceService,
            GlobalSettingRepository globalSettingRepository) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
        this.gatewayPolicyRepository = gatewayPolicyRepository;
        this.memoryService = memoryService;
        this.llmGateway = llmGateway;
        this.deliveryService = deliveryService;
        this.personaWorkspaceService = personaWorkspaceService;
        this.stateStore = new ProactiveReminderStateStore(globalSettingRepository);
    }

    /** 按配置启动固定间隔检查；暂停状态仍保留调度线程，以便运行时恢复无需重启。 */
    public synchronized void start() {
        AppConfig.ProactiveConfig config = appConfig.getProactive();
        if (config == null || config.getIntervalHours() <= 0D || isRunning()) {
            return;
        }
        long intervalSeconds = Math.max(60L, Math.round(config.getIntervalHours() * 3600D));
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleWithFixedDelay(
                this::tickSafe, Math.min(60L, intervalSeconds), intervalSeconds, TimeUnit.SECONDS);
    }

    /** 停止主动提醒调度器。 */
    public synchronized void shutdown() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }

    /** 返回固定间隔检查线程是否已经启动。 */
    public synchronized boolean isRunning() {
        return executorService != null && !executorService.isShutdown();
    }

    /** 执行一次主动提醒检查并隔离异常，避免定时线程退出。 */
    public void tickSafe() {
        try {
            tick();
        } catch (Exception e) {
            recordUnexpectedFailure(e);
            log.warn("Proactive reminder tick failed: error={}", safeError(e));
        }
    }

    /** 读取主对话和记忆，根据两个工作区 MD 生成并投递一次提醒。 */
    public void tick() throws Exception {
        ProactiveReminderState state = stateStore.load();
        state.setLastTickAt(System.currentTimeMillis());
        AppConfig.ProactiveConfig config = appConfig.getProactive();
        if (config == null || !config.isEnabled()) {
            finish(
                    state,
                    ProactiveReminderState.OUTCOME_DISABLED,
                    "主动提醒当前已暂停。使用 /proactive resume 后会在下一次调度检查生效。");
            return;
        }
        if (inQuietHours(config)) {
            finish(state, ProactiveReminderState.OUTCOME_QUIET_HOURS, "当前处于免打扰时段，本次未发送。");
            return;
        }
        if (isDailyLimitReached(state)) {
            finish(state, ProactiveReminderState.OUTCOME_MODEL_SILENT, "今天的主动联系次数已达上限，等待下一日重置。");
            return;
        }
        if (!"main".equalsIgnoreCase(StrUtil.nullToEmpty(config.getDeliveryTarget()))) {
            finish(state, ProactiveReminderState.OUTCOME_CONFIG_INVALID, "发送目标配置无效；当前仅支持 main。");
            throw new IllegalStateException("主动提醒发送目标只支持 main。");
        }
        SessionRecord session = mainSession();
        if (session == null) {
            finish(
                    state,
                    ProactiveReminderState.OUTCOME_NO_MAIN_CONVERSATION,
                    "没有找到与主渠道绑定完全匹配的可用会话。");
            log.info(
                    "Notification skipped: component=proactive, strategy=PRIMARY_CHANNEL, reason=CHANNEL_MISSING_OR_ADMIN_UNBOUND");
            return;
        }
        int userMessageCount = userMessageCount(session);
        if (userMessageCount > state.getObservedUserMessageCount()) {
            state.setUnansweredCount(0);
            state.setLastUserActivityAt(session.getUpdatedAt());
        } else if (state.getLastUserActivityAt() <= 0L) {
            state.setLastUserActivityAt(session.getUpdatedAt());
        }
        state.setObservedUserMessageCount(userMessageCount);
        MemorySnapshot memory = memoryService.loadSnapshot();
        analyzeActivity(session, memory, state);
        state.setActivityCredit(Math.min(1D, state.getActivityCredit() + state.getActivityLevel()));
        if (state.getActivityCredit() < 1D) {
            finish(
                    state,
                    ProactiveReminderState.OUTCOME_ACTIVITY_CREDIT_LOW,
                    "当前活跃度累计额度不足，已保留额度供后续检查继续累计。");
            return;
        }
        String message = generateMessage(session, memory, state);
        if (StrUtil.isBlank(message) || MessageSupport.isSilentResponse(message)) {
            finish(state, ProactiveReminderState.OUTCOME_MODEL_SILENT, "模型判断本次没有适合发送的内容。");
            return;
        }
        if (isSameTopicTooSoon(message, state, config)) {
            finish(
                    state,
                    ProactiveReminderState.OUTCOME_TOPIC_COOLDOWN,
                    "生成内容与最近提醒话题重复，仍处于同话题冷却期。");
            return;
        }
        DeliveryRequest request =
                SourceKeySupport.toDeliveryRequest(session.getSourceKey(), message);
        request.setRecordInConversation(true);
        try {
            deliveryService.deliver(request);
        } catch (Exception e) {
            finish(state, ProactiveReminderState.OUTCOME_DELIVERY_FAILED, "渠道投递失败：" + safeError(e));
            return;
        }
        state.setLastSentAt(System.currentTimeMillis());
        state.setLastMessage(SecretRedactor.redact(message, 500));
        state.setUnansweredCount(state.getUnansweredCount() + 1);
        state.setActivityCredit(Math.max(0D, state.getActivityCredit() - 1D));
        state.setDailyContactCount(
                currentDateKey().equals(state.getLastContactDate())
                        ? state.getDailyContactCount() + 1
                        : 1);
        state.setLastContactDate(currentDateKey());
        finish(state, ProactiveReminderState.OUTCOME_DELIVERED, "主动提醒已成功投递到主对话。");
    }

    /** 使用活跃度分析 MD 调整后续检查的发送概率额度，并保存模型给出的审计理由。 */
    private void analyzeActivity(
            SessionRecord session, MemorySnapshot memory, ProactiveReminderState state)
            throws Exception {
        String template =
                personaWorkspaceService.readPromptBody(
                        ContextFileConstants.KEY_PROACTIVITY_ANALYSIS);
        String prompt =
                StrUtil.nullToEmpty(template)
                        .replace("{current_level}", String.valueOf(state.getActivityLevel()))
                        .replace("{unanswered_count}", String.valueOf(state.getUnansweredCount()))
                        .replace("{current_time}", timestamp(System.currentTimeMillis()))
                        .replace(
                                "{last_user_activity_at}", timestamp(state.getLastUserActivityAt()))
                        .replace("{last_sent_at}", timestamp(state.getLastSentAt()))
                        .replace("{memory_content}", memoryText(memory));
        LlmResult result =
                llmGateway.chat(session, "只输出要求的 JSON，不执行工具。", prompt, Collections.emptyList());
        String text =
                MessageSupport.assistantText(result == null ? null : result.getAssistantMessage());
        try {
            ONode analysis = ONode.ofJson(stripCodeFence(text));
            ONode levelNode = analysis.get("new_level");
            if (levelNode == null || !levelNode.isValue()) {
                levelNode = analysis.get("newLevel");
            }
            if (levelNode == null || !levelNode.isValue()) {
                throw new IllegalArgumentException("missing new_level");
            }
            double newLevel = levelNode.getDouble();
            if (Double.isNaN(newLevel) || Double.isInfinite(newLevel)) {
                throw new IllegalArgumentException("invalid new_level");
            }
            state.setActivityLevel(Math.max(0D, Math.min(1D, newLevel)));
            state.setAnalysisReason(
                    SecretRedactor.redact(
                            StrUtil.blankToDefault(
                                    analysis.get("reason").getString(), "模型未提供分析理由。"),
                            500));
        } catch (Exception e) {
            log.debug("Proactive activity analysis invalid; keeping previous level");
            state.setAnalysisReason("活跃度分析输出无效，已保留上一次活跃度。");
        }
        state.setLastAnalysisAt(System.currentTimeMillis());
    }

    /** 使用主动消息 MD 和主会话历史生成最终提醒。 */
    private String generateMessage(
            SessionRecord session, MemorySnapshot memory, ProactiveReminderState state)
            throws Exception {
        String systemPrompt =
                personaWorkspaceService.readPromptBody(ContextFileConstants.KEY_PROACTIVE);
        String userPrompt =
                "请结合当前主对话历史和以下记忆生成主动提醒。\n"
                        + "记忆内容已经直接提供，不要调用任何工具。\n"
                        + "最近一次主动提醒："
                        + StrUtil.blankToDefault(state.getLastMessage(), "无")
                        + "\n记忆摘要：\n"
                        + memoryText(memory);
        LlmResult result =
                llmGateway.chat(session, systemPrompt, userPrompt, Collections.emptyList());
        return StrUtil.nullToEmpty(
                        MessageSupport.assistantText(
                                result == null ? null : result.getAssistantMessage()))
                .trim();
    }

    /** 选择最近且与显式 home channel 绑定匹配的会话作为主对话。 */
    private SessionRecord mainSession() throws Exception {
        HomeChannelRecord home = gatewayPolicyRepository.getPrimaryHomeChannel();
        if (home == null) {
            return null;
        }
        for (SessionRecord session : sessionRepository.listRecent(50)) {
            if (matchesHome(session, home)) {
                return session;
            }
        }
        return null;
    }

    /** 判断会话来源是否与同平台、同聊天和同线程的主对话绑定一致。 */
    static boolean matchesHome(SessionRecord session, HomeChannelRecord home) {
        if (session == null || home == null || home.getPlatform() == null) {
            return false;
        }
        if (SourceKeySupport.isHeartbeatSource(session.getSourceKey())) {
            return false;
        }
        String[] source = SourceKeySupport.split(session.getSourceKey());
        PlatformType platform = PlatformType.fromName(source[0]);
        return PlatformType.DOMESTIC_PLATFORMS.contains(platform)
                && platform == home.getPlatform()
                && StrUtil.equals(source[1], home.getChatId())
                && StrUtil.equals(
                        StrUtil.nullToEmpty(source[3]), StrUtil.nullToEmpty(home.getThreadId()));
    }

    /** 统计会话中的用户消息，仅真实用户新增消息可以重置未回应次数。 */
    private int userMessageCount(SessionRecord session) throws IOException {
        int count = 0;
        for (ChatMessage message :
                MessageSupport.loadMessages(session == null ? null : session.getNdjson())) {
            if (message != null && message.getRole() == ChatRole.USER) {
                count++;
            }
        }
        return count;
    }

    /** 判断当前时间是否位于启用的跨日或同日免打扰区间。 */
    private boolean inQuietHours(AppConfig.ProactiveConfig config) {
        if (!config.isQuietHoursEnabled()) {
            return false;
        }
        LocalTime now = currentLocalTime();
        LocalTime start = parseTime(config.getQuietStart(), LocalTime.of(22, 0));
        LocalTime end = parseTime(config.getQuietEnd(), LocalTime.of(8, 0));
        return start.equals(end)
                || (start.isBefore(end)
                        ? !now.isBefore(start) && now.isBefore(end)
                        : !now.isBefore(start) || now.isBefore(end));
    }

    /** 按配置解析时分，非法值使用安全默认值。 */
    private LocalTime parseTime(String value, LocalTime fallback) {
        try {
            return LocalTime.parse(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 在同话题冷却期内拦截完全相同或高度重合的提醒。 */
    private boolean isSameTopicTooSoon(
            String message, ProactiveReminderState state, AppConfig.ProactiveConfig config) {
        if (StrUtil.isBlank(state.getLastMessage()) || state.getLastSentAt() <= 0L) {
            return false;
        }
        long cooldownMillis = Math.round(Math.max(0D, config.getTopicCooldownHours()) * 3600000D);
        if (System.currentTimeMillis() - state.getLastSentAt() >= cooldownMillis) {
            return false;
        }
        String current = normalizeTopic(message);
        String previous = normalizeTopic(state.getLastMessage());
        return current.equals(previous) || current.contains(previous) || previous.contains(current);
    }

    /** 去除标点和空白，得到轻量话题比较文本。 */
    private String normalizeTopic(String value) {
        return StrUtil.nullToEmpty(value).replaceAll("[\\p{P}\\p{Z}\\s]+", "").toLowerCase();
    }

    /** 判断今天是否已达到主动联系上限。 */
    private boolean isDailyLimitReached(ProactiveReminderState state) {
        String today = currentDateKey();
        if (!today.equals(state.getLastContactDate())) {
            state.setLastContactDate(today);
            state.setDailyContactCount(0);
            return false;
        }
        return state.getDailyContactCount() >= 3;
    }

    /** 当前日期键。 */
    private String currentDateKey() {
        return LocalDate.now(ZoneId.systemDefault()).toString();
    }

    /** 当前本地时间，测试可覆盖。 */
    protected LocalTime currentLocalTime() {
        return LocalTime.now();
    }

    /** 合并并裁剪三层记忆，避免主动提醒输入无限增长。 */
    private String memoryText(MemorySnapshot memory) {
        if (memory == null) {
            return "";
        }
        String text =
                StrUtil.nullToEmpty(memory.getMemoryText())
                        + "\n"
                        + StrUtil.nullToEmpty(memory.getUserText())
                        + "\n"
                        + StrUtil.nullToEmpty(memory.getDailyMemoryText());
        return SecretRedactor.redact(text, 12000);
    }

    /** 保存本次调度的稳定结果代码和人类可读原因。 */
    private void finish(ProactiveReminderState state, String outcome, String reason)
            throws Exception {
        state.setLastOutcome(outcome);
        state.setLastReason(SecretRedactor.redact(reason, 500));
        stateStore.save(state);
    }

    /** 调度线程捕获未分类异常时记录失败原因，且不覆盖已分类的配置错误。 */
    private void recordUnexpectedFailure(Exception error) {
        try {
            ProactiveReminderState state = stateStore.load();
            if (ProactiveReminderState.OUTCOME_CONFIG_INVALID.equals(state.getLastOutcome())) {
                return;
            }
            state.setLastTickAt(System.currentTimeMillis());
            finish(state, ProactiveReminderState.OUTCOME_TICK_FAILED, "调度检查失败：" + safeError(error));
        } catch (Exception persistenceError) {
            log.warn(
                    "Proactive reminder failure state could not be persisted: error={}",
                    safeError(persistenceError));
        }
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

    /** 去掉模型可能附加的 Markdown JSON 代码围栏。 */
    private String stripCodeFence(String value) {
        return StrUtil.nullToEmpty(value)
                .trim()
                .replaceFirst("^```(?:json)?\\s*", "")
                .replaceFirst("\\s*```$", "");
    }

    /** 返回脱敏且限长的异常摘要。 */
    private String safeError(Exception error) {
        if (error == null) {
            return "unknown";
        }
        String message =
                error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getClass().getSimpleName() + ": " + error.getMessage();
        return SecretRedactor.redact(message, 300);
    }
}
