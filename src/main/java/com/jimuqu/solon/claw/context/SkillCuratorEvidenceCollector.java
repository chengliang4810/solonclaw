package com.jimuqu.solon.claw.context;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 从技能使用记录指向的真实会话中采集可审计、已脱敏的整理证据。 */
public class SkillCuratorEvidenceCollector {
    /** 单个会话最多提供给整理器的可见字符数。 */
    private static final int MAX_SESSION_CHARS = 1200;

    /** 会话仓储；为空时整理器仅使用确定性证据。 */
    private final SessionRepository sessionRepository;

    /**
     * 创建技能整理证据采集器。
     *
     * @param sessionRepository 会话仓储。
     */
    public SkillCuratorEvidenceCollector(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * 按最近使用顺序采集指定技能的真实会话证据。
     *
     * @param skillName 技能规范名称。
     * @param skillState 技能用量状态。
     * @param limit 单技能最多读取的会话数。
     * @return 已脱敏证据窗口。
     */
    public EvidenceWindow collect(String skillName, Map<String, Object> skillState, int limit) {
        EvidenceWindow window = new EvidenceWindow();
        if (sessionRepository == null || skillState == null) {
            return window;
        }
        List<EvidenceAnchor> anchors = evidenceAnchors(skillState, Math.max(1, limit));
        for (EvidenceAnchor anchor : anchors) {
            try {
                SessionRecord session = sessionRepository.findById(anchor.sessionId);
                if (session == null || StrUtil.isBlank(session.getNdjson())) {
                    continue;
                }
                List<VisibleMessage> selected =
                        anchoredConversation(
                                MessageSupport.loadMessages(session.getNdjson()),
                                anchor.messageCount);
                if (!hasUserFollowedByAssistant(selected)) {
                    continue;
                }
                StringBuilder sessionText = new StringBuilder();
                List<String> sessionRefs = new ArrayList<String>();
                int messageIndex = 1;
                for (VisibleMessage message : selected) {
                    String ref = "C" + (window.sessionCount + 1) + "M" + messageIndex++;
                    sessionText
                            .append('[')
                            .append(ref)
                            .append("] ")
                            .append(message.user ? "USER" : "ASSISTANT")
                            .append(": ")
                            .append(message.text)
                            .append('\n');
                    sessionRefs.add(ref);
                }
                window.sessionCount++;
                window.validRefs.addAll(sessionRefs);
                window.sessionIds.add(SecretRedactor.redact(anchor.sessionId, 120));
                window.prompt
                        .append("\n<conversation skill=\"")
                        .append(escape(skillName))
                        .append("\" session=\"")
                        .append(escape(SecretRedactor.redact(anchor.sessionId, 120)))
                        .append("\">\n")
                        .append(sessionText)
                        .append("</conversation>\n");
            } catch (Exception ignored) {
                window.failedSessionCount++;
            }
        }
        return window;
    }

    /** 只提取工具执行边界前最近用户消息及其后的首个可见助手回复。 */
    private List<VisibleMessage> anchoredConversation(
            List<ChatMessage> messages, int messageCount) {
        if (messages == null || messages.isEmpty() || messageCount <= 0) {
            return Collections.emptyList();
        }
        int boundary = Math.min(messageCount, messages.size());
        int userIndex = -1;
        String userText = null;
        for (int index = boundary - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (message != null && message.getRole() == ChatRole.USER) {
                userText = visibleMessageText(message);
                if (StrUtil.isNotBlank(userText)) {
                    userIndex = index;
                    break;
                }
            }
        }
        if (userIndex < 0) {
            return Collections.emptyList();
        }
        String assistantText = null;
        for (int index = Math.max(userIndex + 1, boundary); index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message == null) {
                continue;
            }
            if (message.getRole() == ChatRole.USER) {
                break;
            }
            if (message.getRole() == ChatRole.ASSISTANT) {
                assistantText = visibleMessageText(message);
                if (StrUtil.isNotBlank(assistantText)) {
                    break;
                }
            }
        }
        if (StrUtil.isBlank(assistantText)) {
            return Collections.emptyList();
        }
        int userBudget = Math.min(400, MAX_SESSION_CHARS / 2);
        int assistantBudget = Math.min(400, MAX_SESSION_CHARS - userText.length() - 48);
        List<VisibleMessage> selected = new ArrayList<VisibleMessage>();
        selected.add(new VisibleMessage(true, StrUtil.subPre(userText, userBudget)));
        selected.add(
                new VisibleMessage(
                        false, StrUtil.subPre(assistantText, Math.max(1, assistantBudget))));
        return selected;
    }

    /** 将用户或助手消息转换为已清理的可见文本。 */
    private String visibleMessageText(ChatMessage message) {
        String raw =
                message instanceof AssistantMessage
                        ? MessageSupport.assistantText((AssistantMessage) message)
                        : MessageSupport.visibleText(message.getContent());
        return sanitize(raw, 400);
    }

    /** 只接受至少包含一条用户消息及其后续助手回复的真实对话窗口。 */
    private boolean hasUserFollowedByAssistant(List<VisibleMessage> messages) {
        boolean seenUser = false;
        for (VisibleMessage message : messages) {
            if (message.user) {
                seenUser = true;
            } else if (seenUser) {
                return true;
            }
        }
        return false;
    }

    /** 从状态中的有界引用提取去重且带消息边界的会话锚点。 */
    @SuppressWarnings("unchecked")
    private List<EvidenceAnchor> evidenceAnchors(Map<String, Object> state, int limit) {
        Object raw = state.get("recentSessionEvidence");
        if (!(raw instanceof List)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        List<EvidenceAnchor> anchors = new ArrayList<EvidenceAnchor>();
        List<?> rows = (List<?>) raw;
        for (int index = rows.size() - 1; index >= 0 && anchors.size() < limit; index--) {
            Object row = rows.get(index);
            if (!(row instanceof Map)) {
                continue;
            }
            String sessionId =
                    StrUtil.nullToEmpty(
                                    String.valueOf(((Map<String, Object>) row).get("sessionId")))
                            .trim();
            int messageCount =
                    (int) Math.max(0L, asLong(((Map<String, Object>) row).get("messageCount")));
            if (StrUtil.isNotBlank(sessionId) && messageCount > 0 && ids.add(sessionId)) {
                anchors.add(new EvidenceAnchor(sessionId, messageCount));
            }
        }
        return anchors;
    }

    /** 将状态字段安全转换为长整数。 */
    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** 清除不可见内容、控制字符和密钥，并压缩为单行证据。 */
    private String sanitize(String value, int limit) {
        String clean = MessageSupport.visibleText(MemoryContextBoundary.scrubVisibleText(value));
        clean = clean.replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", " ").replaceAll("\\s+", " ").trim();
        return escape(SecretRedactor.redact(clean, limit));
    }

    /** 转义不可信证据中的提示边界字符。 */
    private String escape(String value) {
        return StrUtil.nullToEmpty(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 保存一条已脱敏的用户或助手可见消息。 */
    private static final class VisibleMessage {
        /** 是否为用户消息。 */
        private final boolean user;

        /** 已脱敏并转义的单行正文。 */
        private final String text;

        /** 创建一条可见消息。 */
        private VisibleMessage(boolean user, String text) {
            this.user = user;
            this.text = text;
        }
    }

    /** 保存一条与技能执行消息边界绑定的会话引用。 */
    private static final class EvidenceAnchor {
        /** 真实会话标识。 */
        private final String sessionId;

        /** 工具执行时已持久化的消息数量。 */
        private final int messageCount;

        /** 创建会话证据锚点。 */
        private EvidenceAnchor(String sessionId, int messageCount) {
            this.sessionId = sessionId;
            this.messageCount = messageCount;
        }
    }

    /** 保存单技能的真实会话证据及其允许引用集合。 */
    public static class EvidenceWindow {
        /** 提供给模型的不可信证据正文。 */
        private final StringBuilder prompt = new StringBuilder();

        /** 模型允许引用的证据编号。 */
        private final Set<String> validRefs = new LinkedHashSet<String>();

        /** 已采用的脱敏会话标识。 */
        private final List<String> sessionIds = new ArrayList<String>();

        /** 成功采用的真实会话数。 */
        private int sessionCount;

        /** 读取或解析失败的会话数。 */
        private int failedSessionCount;

        /**
         * @return 提供给模型的证据正文。
         */
        public String getPrompt() {
            return prompt.toString();
        }

        /**
         * @return 模型允许引用的证据编号。
         */
        public Set<String> getValidRefs() {
            return Collections.unmodifiableSet(validRefs);
        }

        /**
         * @return 已采用的脱敏会话标识。
         */
        public List<String> getSessionIds() {
            return Collections.unmodifiableList(sessionIds);
        }

        /**
         * @return 成功采用的真实会话数。
         */
        public int getSessionCount() {
            return sessionCount;
        }

        /**
         * @return 读取或解析失败的会话数。
         */
        public int getFailedSessionCount() {
            return failedSessionCount;
        }
    }
}
