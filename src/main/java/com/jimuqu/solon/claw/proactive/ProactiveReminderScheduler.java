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
import java.time.LocalTime;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 按工作区规则和主会话记忆生成主动提醒，不维护独立候选或决策流水线。 */
public class ProactiveReminderScheduler {
    /** 主动提醒运行状态在全局设置表中的键。 */
    private static final String STATE_KEY = "proactive.reminder.state";

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

    /** 轻量运行状态仓储。 */
    private final GlobalSettingRepository globalSettingRepository;

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
        this.globalSettingRepository = globalSettingRepository;
    }

    /** 按配置启动固定间隔检查。 */
    public void start() {
        AppConfig.ProactiveConfig config = appConfig.getProactive();
        if (config == null || !config.isEnabled() || config.getIntervalHours() <= 0D) {
            return;
        }
        long intervalSeconds = Math.max(60L, Math.round(config.getIntervalHours() * 3600D));
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleWithFixedDelay(
                this::tickSafe, Math.min(60L, intervalSeconds), intervalSeconds, TimeUnit.SECONDS);
    }

    /** 停止主动提醒调度器。 */
    public void shutdown() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }

    /** 执行一次主动提醒检查并隔离异常，避免定时线程退出。 */
    public void tickSafe() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("Proactive reminder tick failed: error={}", safeError(e));
        }
    }

    /** 读取主对话和记忆，根据两个工作区 MD 生成并投递一次提醒。 */
    public void tick() throws Exception {
        AppConfig.ProactiveConfig config = appConfig.getProactive();
        if (config == null || !config.isEnabled() || inQuietHours(config)) {
            return;
        }
        if (!"main".equalsIgnoreCase(StrUtil.nullToEmpty(config.getDeliveryTarget()))) {
            throw new IllegalStateException("主动提醒发送目标只支持 main。");
        }
        SessionRecord session = mainSession();
        if (session == null) {
            log.info(
                    "Notification skipped: component=proactive, strategy=PRIMARY_CHANNEL, reason=CHANNEL_MISSING_OR_ADMIN_UNBOUND");
            return;
        }
        ReminderState state = loadState();
        if (session.getUpdatedAt() > state.lastSentAt) {
            state.unansweredCount = 0;
        }
        MemorySnapshot memory = memoryService.loadSnapshot();
        state.activityLevel = analyzeActivity(session, memory, state);
        state.activityCredit = Math.min(1D, state.activityCredit + state.activityLevel);
        if (state.activityCredit < 1D) {
            saveState(state);
            return;
        }
        String message = generateMessage(session, memory, state);
        if (StrUtil.isBlank(message) || "[SILENT]".equals(message.trim())) {
            saveState(state);
            return;
        }
        if (isSameTopicTooSoon(message, state, config)) {
            saveState(state);
            return;
        }
        DeliveryRequest request =
                SourceKeySupport.toDeliveryRequest(session.getSourceKey(), message);
        deliveryService.deliver(request);
        state.lastSentAt = System.currentTimeMillis();
        state.lastMessage = SecretRedactor.redact(message, 500);
        state.unansweredCount++;
        state.activityCredit = Math.max(0D, state.activityCredit - 1D);
        saveState(state);
    }

    /** 使用活跃度分析 MD 调整后续检查的发送概率额度。 */
    private double analyzeActivity(
            SessionRecord session, MemorySnapshot memory, ReminderState state) throws Exception {
        String template =
                personaWorkspaceService.readPromptBody(
                        ContextFileConstants.KEY_PROACTIVITY_ANALYSIS);
        String prompt =
                StrUtil.nullToEmpty(template)
                        .replace("{current_level}", String.valueOf(state.activityLevel))
                        .replace("{unanswered_count}", String.valueOf(state.unansweredCount))
                        .replace("{memory_content}", memoryText(memory));
        LlmResult result =
                llmGateway.chat(session, "只输出要求的 JSON，不执行工具。", prompt, Collections.emptyList());
        String text =
                MessageSupport.assistantText(result == null ? null : result.getAssistantMessage());
        try {
            AnalysisResult analysis = ONode.deserialize(stripCodeFence(text), AnalysisResult.class);
            double value = analysis.newLevel;
            return Math.max(0D, Math.min(1D, value));
        } catch (Exception e) {
            log.debug("Proactive activity analysis invalid; keeping previous level");
            return state.activityLevel;
        }
    }

    /** 使用主动消息 MD 和主会话历史生成最终提醒。 */
    private String generateMessage(
            SessionRecord session, MemorySnapshot memory, ReminderState state) throws Exception {
        String systemPrompt =
                personaWorkspaceService.readPromptBody(ContextFileConstants.KEY_PROACTIVE);
        String userPrompt =
                "请结合当前主对话历史和以下记忆生成主动提醒。\n"
                        + "记忆内容已经直接提供，不要调用任何工具。\n"
                        + "最近一次主动提醒："
                        + StrUtil.blankToDefault(state.lastMessage, "无")
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
        String[] source = SourceKeySupport.split(session.getSourceKey());
        PlatformType platform = PlatformType.fromName(source[0]);
        return PlatformType.DOMESTIC_PLATFORMS.contains(platform)
                && platform == home.getPlatform()
                && StrUtil.equals(source[1], home.getChatId())
                && StrUtil.equals(
                        StrUtil.nullToEmpty(source[3]), StrUtil.nullToEmpty(home.getThreadId()));
    }

    /** 判断当前时间是否位于启用的跨日或同日免打扰区间。 */
    private boolean inQuietHours(AppConfig.ProactiveConfig config) {
        if (!config.isQuietHoursEnabled()) {
            return false;
        }
        LocalTime now = LocalTime.now();
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
            String message, ReminderState state, AppConfig.ProactiveConfig config) {
        if (StrUtil.isBlank(state.lastMessage) || state.lastSentAt <= 0L) {
            return false;
        }
        long cooldownMillis = Math.round(Math.max(0D, config.getTopicCooldownHours()) * 3600000D);
        if (System.currentTimeMillis() - state.lastSentAt >= cooldownMillis) {
            return false;
        }
        String current = normalizeTopic(message);
        String previous = normalizeTopic(state.lastMessage);
        return current.equals(previous) || current.contains(previous) || previous.contains(current);
    }

    /** 去除标点和空白，得到轻量话题比较文本。 */
    private String normalizeTopic(String value) {
        return StrUtil.nullToEmpty(value).replaceAll("[\\p{P}\\p{Z}\\s]+", "").toLowerCase();
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

    /** 读取轻量主动提醒状态，缺失或损坏时返回默认状态。 */
    private ReminderState loadState() {
        try {
            String value = globalSettingRepository.get(STATE_KEY);
            ReminderState state = ONode.deserialize(value, ReminderState.class);
            return state == null ? new ReminderState() : state;
        } catch (Exception e) {
            return new ReminderState();
        }
    }

    /** 保存轻量主动提醒状态。 */
    private void saveState(ReminderState state) throws Exception {
        globalSettingRepository.set(STATE_KEY, ONode.serialize(state));
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
        return SecretRedactor.redact(error == null ? "unknown" : error.getMessage(), 300);
    }

    /** 活跃度分析模型的最小响应结构。 */
    public static class AnalysisResult {
        /** 新活跃度。 */
        public double newLevel;
    }

    /** 主动提醒持久化状态。 */
    public static class ReminderState {
        /** 当前活跃度。 */
        public double activityLevel = 1D;

        /** 跨 tick 累计的发送额度。 */
        public double activityCredit;

        /** 连续未回应次数。 */
        public int unansweredCount;

        /** 最近一次成功发送时间。 */
        public long lastSentAt;

        /** 最近一次主动提醒，用于避免重复话题。 */
        public String lastMessage;
    }
}
