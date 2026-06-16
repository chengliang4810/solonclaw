package com.jimuqu.solon.claw.proactive.collector;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.goal.GoalState;
import com.jimuqu.solon.claw.proactive.ProactiveObservationCollector;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 会话续接观测采集器，用于从近期会话中发现值得主动恢复处理的目标和未完成事项。 */
public class SessionContinuationCollector implements ProactiveObservationCollector {
    /** 采集器稳定名称，同时作为观测类型写入结构化载荷。 */
    public static final String COLLECTOR_NAME = "session_continuation";

    /** 单次扫描最多读取的近期会话数，避免主动协作 tick 扫全库。 */
    private static final int RECENT_SESSION_LIMIT = 200;

    /** 一天对应的毫秒数，用于把配置中的天数转换为时间窗口。 */
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    /** 最终回复预览最大长度，避免把长消息完整带入观测载荷。 */
    private static final int PREVIEW_MAX_LENGTH = 360;

    /** 参与关键词匹配的文本最大长度，避免异常大摘要影响 tick 性能。 */
    private static final int SIGNAL_TEXT_MAX_LENGTH = 4000;

    /** 表示会话仍可能需要确认、续接或人工决策的关键词。 */
    private static final List<String> CONTINUATION_KEYWORDS =
            Arrays.asList(
                    "verification",
                    "verify",
                    "deploy",
                    "deployment",
                    "merge",
                    "push",
                    "review",
                    "follow-up",
                    "follow up",
                    "continue",
                    "waiting confirmation",
                    "confirm",
                    "approval",
                    "blocked",
                    "paused",
                    "验证",
                    "校验",
                    "部署",
                    "发布",
                    "合并",
                    "推送",
                    "提交",
                    "复查",
                    "审查",
                    "评审",
                    "跟进",
                    "继续",
                    "待确认",
                    "等待确认",
                    "需要确认",
                    "确认后",
                    "阻塞",
                    "暂停");

    /** 表示最后一条助手消息在询问或等待用户确认的关键词。 */
    private static final List<String> WAITING_ASSISTANT_KEYWORDS =
            Arrays.asList(
                    "?",
                    "？",
                    "should i",
                    "do you want",
                    "please confirm",
                    "waiting",
                    "confirm",
                    "是否",
                    "要不要",
                    "需要你",
                    "请确认",
                    "等待你",
                    "确认后",
                    "可以继续",
                    "我继续");

    /** 表示会话已经明确收尾的关键词。 */
    private static final List<String> COMPLETION_KEYWORDS =
            Arrays.asList(
                    "done",
                    "completed",
                    "finished",
                    "pushed",
                    "merged",
                    "已完成",
                    "完成了",
                    "已提交",
                    "已推送",
                    "已合并",
                    "已经完成",
                    "处理完成",
                    "验证通过");

    /** 表示会话确有执行、验证或交付类工作痕迹的关键词，用于降低普通问答误报。 */
    private static final List<String> WORK_TRACE_KEYWORDS =
            Arrays.asList(
                    "tool",
                    "test",
                    "build",
                    "commit",
                    "branch",
                    "pull request",
                    "验证",
                    "测试",
                    "构建",
                    "分支",
                    "修复",
                    "实现",
                    "改动",
                    "部署",
                    "发布");

    /** 表示用户是在做概念解释型提问，不应仅因术语命中而触发主动续接。 */
    private static final List<String> EXPLANATION_QUESTION_KEYWORDS =
            Arrays.asList(
                    "what is",
                    "what are",
                    "explain",
                    "meaning of",
                    "什么是",
                    "是什么",
                    "解释一下",
                    "说明一下",
                    "介绍一下",
                    "是什么意思");

    /** 会话仓储，用于读取最近会话记录。 */
    private final SessionRepository sessionRepository;

    /**
     * 创建会话续接观测采集器。
     *
     * @param sessionRepository 会话仓储，必须支持按更新时间读取最近会话。
     */
    public SessionContinuationCollector(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /** 返回会话续接采集器的稳定名称，供观测落库、排障和后续候选生成识别来源。 */
    @Override
    public String name() {
        return COLLECTOR_NAME;
    }

    /** 根据主动协作总开关和会话回看窗口判断本采集器是否参与当前 tick。 */
    @Override
    public boolean enabled(AppConfig config) {
        AppConfig.ProactiveConfig proactive = config == null ? null : config.getProactive();
        return proactive != null && proactive.isEnabled() && proactive.getSessionLookbackDays() > 0;
    }

    /** 从最近会话中收集续接观测，严格限制读取数量和配置回看窗口，避免扩大扫描范围。 */
    @Override
    public List<ProactiveObservation> collect(ProactiveTickContext context) throws Exception {
        List<ProactiveObservation> observations = new ArrayList<ProactiveObservation>();
        if (context == null || !enabled(context.getConfig()) || sessionRepository == null) {
            return observations;
        }
        long cutoffMillis = cutoffMillis(context);
        List<SessionRecord> recentSessions = sessionRepository.listRecent(RECENT_SESSION_LIMIT);
        if (recentSessions == null || recentSessions.isEmpty()) {
            return observations;
        }
        for (SessionRecord session : recentSessions) {
            if (session == null || session.getUpdatedAt() < cutoffMillis) {
                continue;
            }
            ProactiveObservation observation = inspectSession(session);
            if (observation != null) {
                observations.add(observation);
            }
        }
        return observations;
    }

    /**
     * 根据配置计算回看窗口起点，异常大天数会被夹紧，避免乘法溢出导致窗口反转。
     *
     * @param context 当前 tick 上下文。
     * @return 返回可用于比较更新时间的毫秒时间戳。
     */
    private long cutoffMillis(ProactiveTickContext context) {
        int lookbackDays = context.getConfig().getProactive().getSessionLookbackDays();
        long safeDays = Math.max(1L, Math.min((long) lookbackDays, 3650L));
        long windowMillis = safeDays * DAY_MILLIS;
        long nowMillis = context.getNowMillis();
        return nowMillis < windowMillis ? 0L : nowMillis - windowMillis;
    }

    /**
     * 检查单个会话是否具备续接价值，并在证据足够时构造观测。
     *
     * @param session 待检查的会话记录。
     * @return 返回观测；没有足够证据时返回 null。
     */
    private ProactiveObservation inspectSession(SessionRecord session) {
        SessionSignals signals = readSignals(session);
        if (signals == null) {
            return null;
        }

        List<String> reasons = new ArrayList<String>();
        if (isActiveGoal(signals.goalState)) {
            reasons.add("goal_active");
        } else if (isBlockedOrPausedGoal(signals.goalState)) {
            reasons.add("goal_needs_continuation");
        }
        boolean waitingForUser = looksWaitingForUser(signals.finalAssistantReply);
        boolean continuationSignal = hasContinuationSignal(signals);
        if (waitingForUser) {
            reasons.add("assistant_waiting_confirmation");
        }
        if (continuationSignal) {
            reasons.add("session_contains_continuation_keyword");
        }
        if (hasRecentWorkWithoutFinalSignal(signals)) {
            reasons.add("recent_work_without_final_signal");
        }

        if (reasons.isEmpty() || isClearlyCompleted(signals, reasons)) {
            return null;
        }
        return buildObservation(session, signals, reasons);
    }

    /**
     * 读取会话消息和目标状态；消息解析失败时跳过该会话，避免单条坏记录中断整体采集。
     *
     * @param session 待读取的会话记录。
     * @return 返回可供规则判断的信号快照；解析失败返回 null。
     */
    private SessionSignals readSignals(SessionRecord session) {
        try {
            List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
            SessionSignals signals = new SessionSignals();
            signals.goalState = parseGoalState(session.getGoalStateJson());
            signals.finalAssistantReply = lastMessageByRole(messages, ChatRole.ASSISTANT);
            signals.lastUserMessage = lastMessageByRole(messages, ChatRole.USER);
            signals.messageCount = messages.size();
            signals.searchText = searchableText(session, signals);
            return signals;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 安全解析目标状态；目标 JSON 异常时按无目标处理，不影响消息侧观测。
     *
     * @param goalStateJson 会话中保存的目标状态 JSON。
     * @return 返回目标状态，解析失败或为空时返回 null。
     */
    private GoalState parseGoalState(String goalStateJson) {
        try {
            return GoalState.fromJson(goalStateJson);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 查找指定角色的最后一条文本消息。
     *
     * @param messages 会话消息列表。
     * @param role 需要查找的消息角色。
     * @return 返回最后一条文本内容；不存在时返回空字符串。
     */
    private String lastMessageByRole(List<ChatMessage> messages, ChatRole role) {
        if (messages == null || role == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message != null && message.getRole() == role) {
                return StrUtil.nullToEmpty(message.getContent());
            }
        }
        return "";
    }

    /**
     * 组合用于关键词判断的有限长度文本，包含标题、摘要、最后用户消息与最后助手回复。
     *
     * @param session 会话记录。
     * @param signals 已解析的会话信号。
     * @return 返回规范化后的搜索文本。
     */
    private String searchableText(SessionRecord session, SessionSignals signals) {
        StringBuilder text = new StringBuilder();
        appendText(text, session.getTitle());
        appendText(text, session.getCompressedSummary());
        appendText(text, signals.lastUserMessage);
        appendText(text, signals.finalAssistantReply);
        String value = text.toString();
        return value.length() > SIGNAL_TEXT_MAX_LENGTH
                ? value.substring(0, SIGNAL_TEXT_MAX_LENGTH)
                : value;
    }

    /**
     * 追加一段非空文本到匹配缓冲区。
     *
     * @param builder 文本缓冲区。
     * @param value 候选文本。
     */
    private void appendText(StringBuilder builder, String value) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(value);
    }

    /**
     * 判断目标是否仍处于 active 状态。
     *
     * @param goalState 目标状态。
     * @return active 目标返回 true。
     */
    private boolean isActiveGoal(GoalState goalState) {
        return goalState != null && GoalState.STATUS_ACTIVE.equalsIgnoreCase(goalState.getStatus());
    }

    /**
     * 判断目标是否处于暂停或阻塞语义，通常需要用户或后续 tick 继续处理。
     *
     * @param goalState 目标状态。
     * @return 暂停或阻塞语义返回 true。
     */
    private boolean isBlockedOrPausedGoal(GoalState goalState) {
        if (goalState == null) {
            return false;
        }
        String status = normalize(goalState.getStatus());
        if (GoalState.STATUS_DONE.equals(status) || GoalState.STATUS_CLEARED.equals(status)) {
            return false;
        }
        String reason = normalize(goalState.getPausedReason() + "\n" + goalState.getLastReason());
        return GoalState.STATUS_PAUSED.equals(status)
                || status.contains("blocked")
                || reason.contains("blocked")
                || reason.contains("阻塞")
                || reason.contains("暂停");
    }

    /**
     * 判断最后助手答复是否像一个未解决的问题或待确认提示。
     *
     * @param finalAssistantReply 最后一条助手文本。
     * @return 命中待确认语义返回 true。
     */
    private boolean looksWaitingForUser(String finalAssistantReply) {
        return containsKeyword(finalAssistantReply, WAITING_ASSISTANT_KEYWORDS);
    }

    /**
     * 判断关键词命中是否足够强，避免把普通概念问答中的 review、push 等词误当成续接任务。
     *
     * @param signals 会话信号快照。
     * @return 同时具备续接语义和工作痕迹，或最后助手正在等待确认时返回 true。
     */
    private boolean hasContinuationSignal(SessionSignals signals) {
        if (!containsKeyword(signals.searchText, CONTINUATION_KEYWORDS)) {
            return false;
        }
        if (isExplanationOnlyQuestion(signals)) {
            return false;
        }
        return looksWaitingForUser(signals.finalAssistantReply)
                || containsKeyword(signals.searchText, WORK_TRACE_KEYWORDS);
    }

    /**
     * 判断是否只是概念解释型问答，避免技术术语本身触发主动协作。
     *
     * @param signals 会话信号快照。
     * @return 用户在询问概念且助手没有等待确认时返回 true。
     */
    private boolean isExplanationOnlyQuestion(SessionSignals signals) {
        return containsKeyword(signals.lastUserMessage, EXPLANATION_QUESTION_KEYWORDS)
                && !looksWaitingForUser(signals.finalAssistantReply);
    }

    /**
     * 判断会话是否存在近期工作痕迹但缺少收尾信号。
     *
     * @param signals 会话信号快照。
     * @return 有工作痕迹且未明确完成时返回 true。
     */
    private boolean hasRecentWorkWithoutFinalSignal(SessionSignals signals) {
        if (signals.messageCount < 2 || containsKeyword(signals.finalAssistantReply, COMPLETION_KEYWORDS)) {
            return false;
        }
        if (isExplanationOnlyQuestion(signals)) {
            return false;
        }
        return containsKeyword(signals.searchText, CONTINUATION_KEYWORDS)
                && containsKeyword(signals.searchText, WORK_TRACE_KEYWORDS);
    }

    /**
     * 判断一个会话是否已明确完成，避免把普通完成会话提升为高价值观测。
     *
     * @param signals 会话信号快照。
     * @param reasons 当前已命中的续接原因。
     * @return 已完成且没有活跃目标或待确认提示时返回 true。
     */
    private boolean isClearlyCompleted(SessionSignals signals, List<String> reasons) {
        GoalState goalState = signals.goalState;
        boolean goalCompleted =
                goalState != null
                        && (GoalState.STATUS_DONE.equalsIgnoreCase(goalState.getStatus())
                                || GoalState.STATUS_CLEARED.equalsIgnoreCase(goalState.getStatus()));
        boolean finalCompleted = containsKeyword(signals.finalAssistantReply, COMPLETION_KEYWORDS);
        boolean hasOpenGoal =
                reasons.contains("goal_active") || reasons.contains("goal_needs_continuation");
        boolean awaitingUser = reasons.contains("assistant_waiting_confirmation");
        return (goalCompleted || finalCompleted) && !hasOpenGoal && !awaitingUser;
    }

    /**
     * 构造结构化观测，所有可能展示给用户或持久化的文本值都先做脱敏。
     *
     * @param session 会话记录。
     * @param signals 会话信号快照。
     * @param reasons 命中的续接原因。
     * @return 返回主动协作观测。
     */
    private ProactiveObservation buildObservation(
            SessionRecord session, SessionSignals signals, List<String> reasons) {
        String sourceKey = sourceKey(session);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", COLLECTOR_NAME);
        payload.put("sessionId", safe(session.getSessionId(), 120));
        payload.put("title", safe(session.getTitle(), 160));
        payload.put("branchName", safe(session.getBranchName(), 160));
        payload.put("sourceKey", safe(sourceKey, 160));
        payload.put("updatedAt", Long.valueOf(session.getUpdatedAt()));
        payload.put("finalReplyPreview", safe(signals.finalAssistantReply, PREVIEW_MAX_LENGTH));
        payload.put("reasons", new ArrayList<String>(reasons));

        ProactiveObservation observation = new ProactiveObservation();
        observation.setCollector(COLLECTOR_NAME);
        observation.setSourceKey(safe(sourceKey, 160));
        observation.setSummary(summary(session, reasons));
        observation.setPayload(payload);
        observation.setStatus("COLLECTED");
        return observation;
    }

    /**
     * 生成面向后续决策层的短摘要。
     *
     * @param session 会话记录。
     * @param reasons 命中的续接原因。
     * @return 返回简短摘要。
     */
    private String summary(SessionRecord session, List<String> reasons) {
        String title = StrUtil.blankToDefault(session.getTitle(), session.getSessionId());
        return safe(
                "session_continuation: 会话「"
                        + title
                        + "」可能需要续接，原因 "
                        + String.join(",", reasons),
                240);
    }

    /**
     * 解析观测来源键，优先使用会话绑定来源，缺失时回退到会话 ID。
     *
     * @param session 会话记录。
     * @return 返回稳定来源键。
     */
    private String sourceKey(SessionRecord session) {
        if (StrUtil.isNotBlank(session.getSourceKey())) {
            return session.getSourceKey();
        }
        return "session:" + StrUtil.blankToDefault(session.getSessionId(), "unknown");
    }

    /**
     * 判断文本是否包含任一关键词，英文统一按小写匹配，中文保持原文匹配。
     *
     * @param text 候选文本。
     * @param keywords 关键词列表。
     * @return 命中任一关键词返回 true。
     */
    private boolean containsKeyword(String text, List<String> keywords) {
        String value = normalize(text);
        if (StrUtil.isBlank(value) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (StrUtil.isNotBlank(keyword)
                    && value.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 规范化文本用于关键词匹配。
     *
     * @param value 原始文本。
     * @return 返回小写且非 null 的文本。
     */
    private String normalize(String value) {
        return StrUtil.nullToEmpty(value).toLowerCase(Locale.ROOT);
    }

    /**
     * 对载荷和摘要文本做统一脱敏与长度限制。
     *
     * @param value 原始文本。
     * @param maxLength 最大保留长度。
     * @return 返回安全文本。
     */
    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), maxLength);
    }

    /** 单个会话解析后的规则判断信号。 */
    private static final class SessionSignals {
        /** 目标状态快照。 */
        private GoalState goalState;

        /** 最后一条助手消息文本。 */
        private String finalAssistantReply;

        /** 最后一条用户消息文本。 */
        private String lastUserMessage;

        /** 可参与关键词匹配的组合文本。 */
        private String searchText;

        /** 会话消息数量，用于判断是否存在实际工作往返。 */
        private int messageCount;
    }
}
