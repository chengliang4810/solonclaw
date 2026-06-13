package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.SessionSearchEntry;
import com.jimuqu.solon.claw.core.model.SessionSearchQuery;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** 默认会话搜索服务。 */
public class DefaultSessionSearchService implements SessionSearchService {
    /** 默认限制的统一常量值。 */
    private static final int DEFAULT_LIMIT = 3;

    /** 最大限制的统一常量值。 */
    private static final int MAX_LIMIT = 5;

    /** 摘要系统提示词的统一常量值。 */
    private static final String SUMMARY_SYSTEM_PROMPT =
            "你正在回顾历史会话，目标是帮助当前任务快速回忆相关内容。"
                    + "\n请围绕搜索主题总结：用户目标、采取的动作、关键结论/决策、重要命令或文件、未解决事项。"
                    + "\n只输出基于对话记录可确认的事实。";

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 记录默认会话搜索中的大模型消息网关。 */
    private final LlmGateway llmGateway;

    /** 保存Agent运行仓储依赖，用于访问持久化数据。 */
    private final AgentRunRepository agentRunRepository;

    /**
     * 创建默认会话搜索服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param llmGateway LLM网关参数。
     */
    public DefaultSessionSearchService(SessionRepository sessionRepository, LlmGateway llmGateway) {
        this(sessionRepository, llmGateway, null);
    }

    /**
     * 创建默认会话搜索服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param llmGateway LLM网关参数。
     * @param agentRunRepository Agent运行仓储依赖。
     */
    public DefaultSessionSearchService(
            SessionRepository sessionRepository,
            LlmGateway llmGateway,
            AgentRunRepository agentRunRepository) {
        this.sessionRepository = sessionRepository;
        this.llmGateway = llmGateway;
        this.agentRunRepository = agentRunRepository;
    }

    /**
     * 执行搜索相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param query 查询参数。
     * @param limit 最大返回数量。
     * @return 返回搜索结果。
     */
    @Override
    public List<SessionSearchEntry> search(String sourceKey, String query, int limit)
            throws Exception {
        return search(sourceKey, query, limit, false);
    }

    /**
     * 执行搜索相关逻辑，并按调用方要求决定是否额外调用模型生成聚焦总结。
     *
     * @param sourceKey 渠道来源键。
     * @param query 查询参数。
     * @param limit 最大返回数量。
     * @param summarize 是否调用模型生成搜索聚焦总结；默认工具路径关闭以保证长会话检索延迟稳定。
     * @return 返回搜索结果。
     */
    private List<SessionSearchEntry> search(
            String sourceKey, String query, int limit, boolean summarize) throws Exception {
        int resolvedLimit = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        SessionRecord currentSession =
                StrUtil.isBlank(sourceKey) ? null : sessionRepository.getBoundSession(sourceKey);
        String currentRootId = resolveRootId(currentSession);

        List<SessionRecord> raw =
                StrUtil.isBlank(query)
                        ? sessionRepository.listRecent(Math.max(10, resolvedLimit * 5))
                        : sessionRepository.search(query.trim(), Math.max(10, resolvedLimit * 5));

        Map<String, SearchCandidate> grouped = new LinkedHashMap<String, SearchCandidate>();
        String normalizedQuery = StrUtil.nullToEmpty(query).trim().toLowerCase(Locale.ROOT);
        // 当前绑定会话的压缩摘要可能尚未进入底层索引，仅对摘要命中走快速召回，避免后续复盘文本抢占原始目标会话。
        if (StrUtil.isNotBlank(query)
                && currentSession != null
                && compressedSummaryMatches(currentSession, normalizedQuery)) {
            grouped.put(currentRootId, new SearchCandidate(currentSession, currentSession));
        }
        for (SessionRecord candidate : raw) {
            if (candidate == null) {
                continue;
            }
            String rootId = resolveRootId(candidate);
            if (StrUtil.isNotBlank(currentRootId) && currentRootId.equals(rootId)) {
                if (StrUtil.isBlank(query) || !sessionMatchesQuery(candidate, query)) {
                    continue;
                }
            }
            if (!grouped.containsKey(rootId)) {
                SessionRecord display = candidate;
                if (StrUtil.isNotBlank(rootId) && !rootId.equals(candidate.getSessionId())) {
                    SessionRecord resolved = sessionRepository.findById(rootId);
                    if (resolved != null) {
                        display = resolved;
                    }
                }
                grouped.put(rootId, new SearchCandidate(display, candidate));
            }
            if (StrUtil.isBlank(query) && grouped.size() >= resolvedLimit) {
                break;
            }
        }

        List<SessionSearchEntry> results = new ArrayList<SessionSearchEntry>();
        for (SearchCandidate candidate : grouped.values()) {
            SessionSearchEntry entry = new SessionSearchEntry();
            SessionRecord display = candidate.display;
            SessionRecord representative = candidate.representative;
            entry.setSessionId(display.getSessionId());
            entry.setBranchName(
                    StrUtil.blankToDefault(
                            display.getBranchName(), representative.getBranchName()));
            entry.setTitle(resolveTitle(display, representative));
            entry.setUpdatedAt(Math.max(display.getUpdatedAt(), representative.getUpdatedAt()));
            entry.setPlatformMessageId(display.getPlatformMessageId());
            entry.setMode(StrUtil.isBlank(query) ? "browse" : "discovery");
            entry.setMatchPreview(buildPreview(representative, query));
            entry.setSnippet(entry.getMatchPreview());
            entry.setMessageId(findPreviewMessageId(representative, query));
            entry.setScore(scoreMatch(representative, query));
            if (StrUtil.isBlank(query)) {
                entry.setSummary(entry.getMatchPreview());
            } else if (summarize) {
                entry.setSummary(
                        buildSummary(
                                currentSession, representative, query, entry.getMatchPreview()));
            } else {
                entry.setSummary(buildDeterministicSummary(representative, query, entry));
            }
            results.add(entry);
        }
        if (StrUtil.isNotBlank(query)) {
            appendToolCallResults(sourceKey, query, resolvedLimit, results);
            results = retainConfirmedDiscoveryResults(results);
            sortDiscoveryResults(results);
        }
        return limitResults(results, resolvedLimit);
    }

    /**
     * 执行搜索相关逻辑。
     *
     * @param query 查询参数。
     * @return 返回搜索结果。
     */
    @Override
    public List<SessionSearchEntry> search(SessionSearchQuery query) throws Exception {
        if (query == null) {
            return search(null, null, DEFAULT_LIMIT);
        }
        if (shouldSearchRuns(query)) {
            return searchRunScope(query);
        }
        if (StrUtil.isNotBlank(query.getSessionId())
                && StrUtil.isNotBlank(query.getAroundMessageId())) {
            return scroll(query);
        }
        List<SessionSearchEntry> entries =
                search(query.getSourceKey(), query.getQuery(), query.getLimit(), query.isSummarize());
        List<SessionSearchEntry> filtered = new ArrayList<SessionSearchEntry>();
        for (SessionSearchEntry entry : entries) {
            if (StrUtil.isNotBlank(query.getSessionId())
                    && !query.getSessionId().equals(entry.getSessionId())) {
                continue;
            }
            if (query.getTimeFrom() > 0 && entry.getUpdatedAt() < query.getTimeFrom()) {
                continue;
            }
            if (query.getTimeTo() > 0 && entry.getUpdatedAt() > query.getTimeTo()) {
                continue;
            }
            filtered.add(entry);
        }
        return filtered;
    }

    /**
     * 判断是否需要搜索运行。
     *
     * @param query 查询参数。
     * @return 如果搜索运行满足条件则返回 true，否则返回 false。
     */
    private boolean shouldSearchRuns(SessionSearchQuery query) {
        return agentRunRepository != null
                && (StrUtil.isNotBlank(query.getRunId())
                        || StrUtil.isNotBlank(query.getToolName())
                        || StrUtil.isNotBlank(query.getChannel()));
    }

    /**
     * 搜索运行范围。
     *
     * @param query 查询参数。
     * @return 返回运行范围结果。
     */
    private List<SessionSearchEntry> searchRunScope(SessionSearchQuery query) throws Exception {
        int limit =
                Math.max(1, Math.min(query.getLimit() <= 0 ? DEFAULT_LIMIT : query.getLimit(), 50));
        Map<String, SessionSearchEntry> results = new LinkedHashMap<String, SessionSearchEntry>();
        if (StrUtil.isNotBlank(query.getToolName())) {
            for (ToolCallRecord toolCall :
                    agentRunRepository.searchToolCalls(
                            firstNonBlankValue(query.getChannel(), query.getSourceKey()),
                            query.getSessionId(),
                            query.getRunId(),
                            query.getToolName(),
                            query.getQuery(),
                            query.getTimeFrom(),
                            query.getTimeTo(),
                            limit)) {
                SessionSearchEntry entry = entryFromToolCall(toolCall, query.getQuery());
                results.put(entryKey(entry), entry);
                if (results.size() >= limit) {
                    break;
                }
            }
            return new ArrayList<SessionSearchEntry>(results.values());
        }
        for (AgentRunRecord run :
                agentRunRepository.searchRuns(
                        firstNonBlankValue(query.getChannel(), query.getSourceKey()),
                        query.getSessionId(),
                        query.getRunId(),
                        query.getQuery(),
                        query.getTimeFrom(),
                        query.getTimeTo(),
                        limit)) {
            SessionSearchEntry entry = entryFromRun(run);
            results.put(entryKey(entry), entry);
            if (results.size() >= limit) {
                break;
            }
        }
        return new ArrayList<SessionSearchEntry>(results.values());
    }

    /**
     * 执行entryFrom运行相关逻辑。
     *
     * @param run 运行参数。
     * @return 返回entry From运行结果。
     */
    private SessionSearchEntry entryFromRun(AgentRunRecord run) throws Exception {
        SessionRecord session =
                StrUtil.isBlank(run.getSessionId())
                        ? null
                        : sessionRepository.findById(run.getSessionId());
        SessionSearchEntry entry = new SessionSearchEntry();
        entry.setSessionId(run.getSessionId());
        entry.setBranchName(session == null ? null : session.getBranchName());
        entry.setTitle(
                StrUtil.blankToDefault(
                        session == null ? null : session.getTitle(), "run-" + run.getRunId()));
        entry.setUpdatedAt(Math.max(run.getLastActivityAt(), run.getStartedAt()));
        entry.setMode("discovery");
        entry.setPlatformMessageId(session == null ? null : session.getPlatformMessageId());
        entry.setMatchPreview(
                firstNonBlank(
                        run.getFinalReplyPreview(),
                        run.getInputPreview(),
                        run.getError(),
                        run.getStatus()));
        entry.setSummary(entry.getMatchPreview());
        entry.setSnippet(entry.getMatchPreview());
        entry.setRunId(run.getRunId());
        entry.setChannel(run.getSourceKey());
        return entry;
    }

    /**
     * 执行entryFrom工具Call相关逻辑。
     *
     * @param record 记录参数。
     * @return 返回entry From工具Call结果。
     */
    private SessionSearchEntry entryFromToolCall(ToolCallRecord record) throws Exception {
        return entryFromToolCall(record, null);
    }

    /**
     * 执行entryFrom工具Call相关逻辑，并在提供查询词时生成可解释命中片段与分数。
     *
     * @param record 记录参数。
     * @param query 查询参数。
     * @return 返回entry From工具Call结果。
     */
    private SessionSearchEntry entryFromToolCall(ToolCallRecord record, String query)
            throws Exception {
        SessionRecord session =
                StrUtil.isBlank(record.getSessionId())
                        ? null
                        : sessionRepository.findById(record.getSessionId());
        SessionSearchEntry entry = new SessionSearchEntry();
        entry.setSessionId(record.getSessionId());
        entry.setBranchName(session == null ? null : session.getBranchName());
        entry.setTitle(
                StrUtil.blankToDefault(
                        session == null ? null : session.getTitle(), "run-" + record.getRunId()));
        entry.setUpdatedAt(Math.max(record.getFinishedAt(), record.getStartedAt()));
        entry.setMode("discovery");
        entry.setPlatformMessageId(session == null ? null : session.getPlatformMessageId());
        entry.setMatchPreview(toolCallPreview(record, query));
        entry.setSummary(entry.getMatchPreview());
        entry.setSnippet(entry.getMatchPreview());
        entry.setRunId(record.getRunId());
        entry.setToolName(record.getToolName());
        entry.setChannel(record.getSourceKey());
        entry.setScore(scoreToolCall(record, query));
        return entry;
    }

    /**
     * 执行entry键相关逻辑。
     *
     * @param entry entry 参数。
     * @return 返回entry键结果。
     */
    private String entryKey(SessionSearchEntry entry) {
        return StrUtil.blankToDefault(entry.getRunId(), "")
                + ":"
                + StrUtil.blankToDefault(entry.getToolName(), "")
                + ":"
                + StrUtil.blankToDefault(entry.getSessionId(), "");
    }

    /**
     * 执行firstNon空白值相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回first Non Blank结果。
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return trim(value, 220);
            }
        }
        return "";
    }

    /**
     * 执行firstNon空白值值相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回first Non Blank Value结果。
     */
    private String firstNonBlankValue(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 构建Summary。
     *
     * @param currentSession current会话参数。
     * @param representative representative 参数。
     * @param query 查询参数。
     * @param fallback 兜底参数。
     * @return 返回创建好的Summary。
     */
    private String buildSummary(
            SessionRecord currentSession,
            SessionRecord representative,
            String query,
            String fallback) {
        try {
            String transcript = formatConversation(representative);
            if (StrUtil.isBlank(transcript)) {
                return StrUtil.blankToDefault(fallback, "No summary available");
            }

            SessionRecord synthetic = new SessionRecord();
            synthetic.setSessionId("session-search-" + IdSupport.newId());
            synthetic.setNdjson("");
            if (currentSession != null) {
                synthetic.setModelOverride(currentSession.getModelOverride());
            }

            String userPrompt =
                    "Search topic: "
                            + query.trim()
                            + "\nSession title: "
                            + resolveTitle(representative, representative)
                            + "\n\nConversation:\n"
                            + transcript
                            + "\n\n请围绕搜索主题给出简洁事实回顾。";
            LlmResult result =
                    llmGateway.chat(
                            synthetic, SUMMARY_SYSTEM_PROMPT, userPrompt, Collections.emptyList());
            String summary = extractText(result == null ? null : result.getAssistantMessage());
            return StrUtil.blankToDefault(
                    summary, StrUtil.blankToDefault(fallback, "No summary available"));
        } catch (Exception e) {
            return StrUtil.blankToDefault(fallback, "No summary available");
        }
    }

    /**
     * 构建无需模型调用的搜索摘要，避免会话搜索工具在长对话中因为额外总结模型调用而阻塞主循环。
     *
     * @param representative 命中的代表会话。
     * @param query 查询参数。
     * @param entry 已组装的搜索结果条目。
     * @return 返回确定性搜索摘要。
     */
    private String buildDeterministicSummary(
            SessionRecord representative, String query, SessionSearchEntry entry) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("搜索主题：").append(StrUtil.blankToDefault(query, "最近会话"));
        buffer.append("\n命中会话：").append(resolveTitle(representative, representative));
        if (StrUtil.isNotBlank(entry.getMessageId())) {
            buffer.append("\n命中位置：").append(entry.getMessageId());
        }
        buffer.append("\n匹配片段：")
                .append(StrUtil.blankToDefault(entry.getMatchPreview(), "未生成匹配片段"));
        return trim(buffer.toString(), 1200);
    }

    /**
     * 提取Text。
     *
     * @param assistantMessage assistant消息参数。
     * @return 返回Text结果。
     */
    private String extractText(AssistantMessage assistantMessage) {
        if (assistantMessage == null) {
            return "";
        }
        if (StrUtil.isNotBlank(assistantMessage.getResultContent())) {
            return assistantMessage.getResultContent().trim();
        }
        if (StrUtil.isNotBlank(assistantMessage.getContent())) {
            return assistantMessage.getContent().trim();
        }
        return "";
    }

    /**
     * 格式化对话。
     *
     * @param session 会话参数。
     * @return 返回对话结果。
     */
    private String formatConversation(SessionRecord session) throws Exception {
        List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
        StringBuilder buffer = new StringBuilder();
        if (StrUtil.isNotBlank(session.getCompressedSummary())) {
            buffer.append("Compressed summary: ")
                    .append(trim(session.getCompressedSummary(), 1200));
        }
        for (ChatMessage message : messages) {
            String content = StrUtil.nullToEmpty(message.getContent()).trim();
            if (content.length() == 0 || message.getRole() == ChatRole.SYSTEM) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(roleLabel(message.getRole())).append(": ").append(trim(content, 400));
        }
        return trim(buffer.toString(), 4000);
    }

    /**
     * 执行scroll相关逻辑。
     *
     * @param query 查询参数。
     * @return 返回scroll结果。
     */
    private List<SessionSearchEntry> scroll(SessionSearchQuery query) throws Exception {
        int limit =
                Math.max(1, Math.min(query.getLimit() <= 0 ? DEFAULT_LIMIT : query.getLimit(), 50));
        SessionRecord session = sessionRepository.findById(query.getSessionId());
        if (session == null) {
            return Collections.emptyList();
        }
        List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }
        int anchorIndex = findMessageIndex(messages, query.getAroundMessageId());
        if (anchorIndex < 0) {
            return Collections.emptyList();
        }
        int start = Math.max(0, anchorIndex - ((limit - 1) / 2));
        int end = Math.min(messages.size(), start + limit);
        start = Math.max(0, end - limit);
        List<SessionSearchEntry> results = new ArrayList<SessionSearchEntry>();
        for (int i = start; i < end; i++) {
            ChatMessage message = messages.get(i);
            String content = StrUtil.nullToEmpty(message.getContent()).trim();
            SessionSearchEntry entry = new SessionSearchEntry();
            entry.setMode("scroll");
            entry.setSessionId(session.getSessionId());
            entry.setBranchName(session.getBranchName());
            entry.setTitle(resolveTitle(session, session));
            entry.setUpdatedAt(session.getUpdatedAt());
            entry.setPlatformMessageId(session.getPlatformMessageId());
            entry.setMessageId(resolveMessageId(message, i));
            entry.setAnchor(i == anchorIndex);
            entry.setMatchPreview(trim(content, 220));
            entry.setSnippet(entry.getMatchPreview());
            entry.setSummary(entry.getMatchPreview());
            results.add(entry);
        }
        return results;
    }

    /**
     * 查找消息Index。
     *
     * @param messages messages 参数。
     * @param aroundMessageId around消息标识。
     * @return 返回消息Index结果。
     */
    private int findMessageIndex(List<ChatMessage> messages, String aroundMessageId) {
        for (int i = 0; i < messages.size(); i++) {
            if (aroundMessageId.equals(resolveMessageId(messages.get(i), i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 查找Preview消息标识。
     *
     * @param session 会话参数。
     * @param query 查询参数。
     * @return 返回Preview消息标识。
     */
    private String findPreviewMessageId(SessionRecord session, String query) throws Exception {
        List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
        String normalizedQuery = StrUtil.nullToEmpty(query).trim().toLowerCase(Locale.ROOT);
        if (compressedSummaryMatches(session, normalizedQuery)) {
            return "compressed_summary";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            String content = messageSearchText(message);
            if (content.length() == 0 || message.getRole() == ChatRole.SYSTEM) {
                continue;
            }
            if (normalizedQuery.length() == 0 || textMatchesQuery(content, normalizedQuery)) {
                return resolveMessageId(message, i);
            }
        }
        return null;
    }

    /**
     * 判断会话自身是否命中检索词；非空检索允许召回当前会话里被压缩摘要承载的历史 marker。
     *
     * @param session 会话记录。
     * @param query 查询参数。
     * @return 命中时返回 true。
     */
    private boolean sessionMatchesQuery(SessionRecord session, String query) {
        String normalizedQuery = StrUtil.nullToEmpty(query).trim().toLowerCase(Locale.ROOT);
        if (session == null || normalizedQuery.length() == 0) {
            return false;
        }
        return containsIgnoreCase(session.getNdjson(), normalizedQuery)
                || containsIgnoreCase(session.getCompressedSummary(), normalizedQuery)
                || containsIgnoreCase(session.getTitle(), normalizedQuery);
    }

    /**
     * 判断文本是否包含已归一化的检索词。
     *
     * @param value 待搜索文本。
     * @param normalizedQuery 已转小写的查询词。
     * @return 命中时返回 true。
     */
    private boolean containsIgnoreCase(String value, String normalizedQuery) {
        return StrUtil.isNotBlank(value)
                && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    /**
     * 解析消息标识。
     *
     * @param message 平台消息或错误消息。
     * @param index 索引参数。
     * @return 返回解析后的消息标识。
     */
    private String resolveMessageId(ChatMessage message, int index) {
        if (message != null && message.getMetadata() != null) {
            Object value = message.getMetadata().get("platformMessageId");
            if (value == null) {
                value = message.getMetadata().get("messageId");
            }
            if (value == null) {
                value = message.getMetadata().get("id");
            }
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return "message-" + index;
    }

    /**
     * 构建Preview。
     *
     * @param session 会话参数。
     * @param query 查询参数。
     * @return 返回创建好的Preview。
     */
    private String buildPreview(SessionRecord session, String query) throws Exception {
        List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
        String normalizedQuery = StrUtil.nullToEmpty(query).trim().toLowerCase(Locale.ROOT);
        if (compressedSummaryMatches(session, normalizedQuery)) {
            return trimAroundMatch(session.getCompressedSummary(), normalizedQuery);
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            String content = messageSearchText(message);
            if (content.length() == 0 || message.getRole() == ChatRole.SYSTEM) {
                continue;
            }
            if (normalizedQuery.length() == 0 || textMatchesQuery(content, normalizedQuery)) {
                return trimAroundMatch(content, normalizedQuery);
            }
        }
        if (StrUtil.isNotBlank(session.getTitle())
                && (normalizedQuery.length() == 0
                        || session.getTitle().toLowerCase(Locale.ROOT).contains(normalizedQuery))) {
            return trimAroundMatch(session.getTitle(), normalizedQuery);
        }
        if (normalizedQuery.length() > 0
                && containsIgnoreCase(session.getNdjson(), normalizedQuery)) {
            return trimAroundMatch(session.getNdjson(), normalizedQuery);
        }
        return "";
    }

    /**
     * 计算发现模式的可解释命中分值，避免压缩摘要召回只返回 score=0。
     *
     * @param session 会话记录。
     * @param query 查询参数。
     * @return 命中强度分值；0 表示未能确认直接命中。
     */
    private long scoreMatch(SessionRecord session, String query) throws Exception {
        String normalizedQuery = StrUtil.nullToEmpty(query).trim().toLowerCase(Locale.ROOT);
        if (session == null || normalizedQuery.length() == 0) {
            return 0L;
        }
        if (compressedSummaryMatches(session, normalizedQuery)) {
            return 100L;
        }
        if (containsIgnoreCase(session.getSessionId(), normalizedQuery)) {
            return 95L;
        }
        if (containsIgnoreCase(session.getTitle(), normalizedQuery)) {
            return 90L;
        }
        List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
        for (ChatMessage message : messages) {
            String content = messageSearchText(message);
            if (content.length() == 0 || message.getRole() == ChatRole.SYSTEM) {
                continue;
            }
            if (content.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                return 80L;
            }
        }
        if (containsIgnoreCase(session.getNdjson(), normalizedQuery)) {
            return 75L;
        }
        long partialScore = scorePartialText(sessionSearchText(session), normalizedQuery);
        if (partialScore > 0L) {
            return partialScore;
        }
        return 0L;
    }

    /**
     * 将当前来源下已落盘的工具调用加入普通发现搜索，覆盖同轮工具写入尚未进入会话索引的窗口。
     *
     * @param sourceKey 渠道来源键。
     * @param query 查询参数。
     * @param limit 最大返回数量。
     * @param results 待追加结果。
     */
    private void appendToolCallResults(
            String sourceKey, String query, int limit, List<SessionSearchEntry> results) {
        if (agentRunRepository == null || StrUtil.isBlank(query)) {
            return;
        }
        try {
            int searchLimit = Math.max(10, limit * 5);
            for (ToolCallRecord record :
                    agentRunRepository.searchToolCalls(
                            sourceKey, null, null, null, query, 0L, 0L, searchLimit)) {
                if (record == null || "session_search".equalsIgnoreCase(record.getToolName())) {
                    continue;
                }
                SessionSearchEntry entry = entryFromToolCall(record, query);
                if (entry.getScore() > 0L) {
                    results.add(entry);
                }
            }
        } catch (Exception ignored) {
            // 工具调用检索是会话搜索的增强来源，失败时不影响历史会话搜索主路径。
        }
    }

    /**
     * 过滤 discovery 中无法解释命中的候选，避免将 0 分的最近会话伪装成搜索结果。
     *
     * @param entries 原始结果。
     * @return 返回已确认命中的结果。
     */
    private List<SessionSearchEntry> retainConfirmedDiscoveryResults(
            List<SessionSearchEntry> entries) {
        List<SessionSearchEntry> confirmed = new ArrayList<SessionSearchEntry>();
        for (SessionSearchEntry entry : entries) {
            if (entry != null && entry.getScore() > 0L) {
                confirmed.add(entry);
            }
        }
        return confirmed;
    }

    /**
     * 对发现模式结果执行稳定排序，优先返回直接命中的原始会话，再用更新时间处理同分结果。
     *
     * @param results 待排序结果。
     */
    private void sortDiscoveryResults(List<SessionSearchEntry> results) {
        Collections.sort(
                results,
                new Comparator<SessionSearchEntry>() {
                    @Override
                    public int compare(SessionSearchEntry left, SessionSearchEntry right) {
                        int scoreCompare = Long.compare(right.getScore(), left.getScore());
                        if (scoreCompare != 0) {
                            return scoreCompare;
                        }
                        return Long.compare(right.getUpdatedAt(), left.getUpdatedAt());
                    }
                });
    }

    /**
     * 按调用方请求的数量裁剪结果，保证工具参数 limit 是硬上限。
     *
     * @param results 原始结果列表。
     * @param limit 最大返回数量。
     * @return 返回裁剪后的结果。
     */
    private List<SessionSearchEntry> limitResults(List<SessionSearchEntry> results, int limit) {
        if (results == null || results.size() <= limit) {
            return results;
        }
        return new ArrayList<SessionSearchEntry>(results.subList(0, limit));
    }

    /**
     * 计算工具调用与查询词的匹配分值，优先匹配参数和结果内容。
     *
     * @param record 工具调用记录。
     * @param query 查询参数。
     * @return 返回工具调用命中分值。
     */
    private long scoreToolCall(ToolCallRecord record, String query) {
        String normalizedQuery = StrUtil.nullToEmpty(query).trim().toLowerCase(Locale.ROOT);
        if (record == null || normalizedQuery.length() == 0) {
            return 0L;
        }
        String text = toolCallSearchText(record);
        if (text.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
            return 85L;
        }
        return scorePartialText(text, normalizedQuery);
    }

    /**
     * 构建工具调用的查询命中片段。
     *
     * @param record 工具调用记录。
     * @param query 查询参数。
     * @return 返回工具调用片段。
     */
    private String toolCallPreview(ToolCallRecord record, String query) {
        String normalizedQuery = StrUtil.nullToEmpty(query).trim().toLowerCase(Locale.ROOT);
        String[] values =
                new String[] {
                    record == null ? null : record.getArgsPreview(),
                    record == null ? null : record.getResultPreview(),
                    record == null ? null : record.getError(),
                    record == null ? null : record.getToolName()
                };
        for (String value : values) {
            if (StrUtil.isNotBlank(value) && textMatchesQuery(value, normalizedQuery)) {
                return trimAroundMatch(value, normalizedQuery);
            }
        }
        return firstNonBlank(values);
    }

    /**
     * 汇总工具调用可检索文本。
     *
     * @param record 工具调用记录。
     * @return 返回可检索文本。
     */
    private String toolCallSearchText(ToolCallRecord record) {
        if (record == null) {
            return "";
        }
        return StrUtil.nullToEmpty(record.getToolName())
                + "\n"
                + StrUtil.nullToEmpty(record.getArgsPreview())
                + "\n"
                + StrUtil.nullToEmpty(record.getResultPreview())
                + "\n"
                + StrUtil.nullToEmpty(record.getError());
    }

    /**
     * 汇总会话可检索文本，用于对拆词召回结果进行二次确认。
     *
     * @param session 会话记录。
     * @return 返回可检索文本。
     */
    private String sessionSearchText(SessionRecord session) throws Exception {
        if (session == null) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append(StrUtil.nullToEmpty(session.getSessionId())).append('\n');
        buffer.append(StrUtil.nullToEmpty(session.getTitle())).append('\n');
        buffer.append(StrUtil.nullToEmpty(session.getCompressedSummary())).append('\n');
        for (ChatMessage message : MessageSupport.loadMessages(session.getNdjson())) {
            buffer.append(messageSearchText(message)).append('\n');
        }
        return buffer.toString();
    }

    /**
     * 提取消息正文和工具调用参数，保证工具型历史也能被普通会话搜索解释命中。
     *
     * @param message 聊天消息。
     * @return 返回可检索文本。
     */
    private String messageSearchText(ChatMessage message) {
        if (message == null) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append(StrUtil.nullToEmpty(message.getContent()).trim());
        if (message instanceof AssistantMessage) {
            List<ToolCall> toolCalls = ((AssistantMessage) message).getToolCalls();
            if (toolCalls != null) {
                for (ToolCall toolCall : toolCalls) {
                    if (toolCall == null) {
                        continue;
                    }
                    buffer.append('\n')
                            .append(StrUtil.nullToEmpty(toolCall.getName()))
                            .append(' ')
                            .append(StrUtil.nullToEmpty(toolCall.getArgumentsStr()));
                }
            }
        }
        return buffer.toString().trim();
    }

    /**
     * 判断文本是否能用完整查询或足够多的拆分词元确认命中。
     *
     * @param value 待搜索文本。
     * @param normalizedQuery 已转小写的查询词。
     * @return 命中时返回 true。
     */
    private boolean textMatchesQuery(String value, String normalizedQuery) {
        String normalizedValue = StrUtil.nullToEmpty(value).toLowerCase(Locale.ROOT);
        return normalizedQuery.length() == 0
                || normalizedValue.contains(normalizedQuery)
                || scorePartialText(value, normalizedQuery) > 0L;
    }

    /**
     * 对拆词召回做二次评分，避免只命中 web/loop 这类弱词的结果混入精确 marker 查询。
     *
     * @param value 待搜索文本。
     * @param normalizedQuery 已转小写的查询词。
     * @return 返回部分命中分值。
     */
    private long scorePartialText(String value, String normalizedQuery) {
        List<String> terms = searchTerms(normalizedQuery);
        if (terms.size() < 2) {
            return 0L;
        }
        String normalizedValue = StrUtil.nullToEmpty(value).toLowerCase(Locale.ROOT);
        if (!strictMarkerFragmentsSatisfied(normalizedValue, normalizedQuery)) {
            return 0L;
        }
        int matched = 0;
        for (String term : terms) {
            if (normalizedValue.contains(term.toLowerCase(Locale.ROOT))) {
                matched++;
            }
        }
        if (requiresStrictFragmentMatch(normalizedQuery, terms) && matched < terms.size()) {
            return 0L;
        }
        double ratio = matched / (double) terms.size();
        if (matched >= 2 && ratio >= 0.5d) {
            return 50L + Math.min(25L, matched * 3L);
        }
        return 0L;
    }

    /**
     * 判断查询是否像单个历史 marker；这类查询不能只靠 web/loop/search 等弱片段命中。
     *
     * @param normalizedQuery 已转小写的查询词。
     * @return 需要严格片段命中的查询返回 true。
     */
    private boolean isStrictFragmentQuery(String normalizedQuery) {
        String query = StrUtil.nullToEmpty(normalizedQuery).trim();
        return query.indexOf('-') >= 0
                && query.indexOf(' ') < 0
                && query.indexOf('\t') < 0
                && query.indexOf('\n') < 0;
    }

    /**
     * 判断查询是否包含需要完整命中的历史标识；这类查询常由 marker 加说明词组成，不能按公共词宽松召回。
     *
     * @param normalizedQuery 已转小写的查询词。
     * @param terms 查询拆分出的词元。
     * @return 需要所有词元全部命中时返回 true。
     */
    private boolean requiresStrictFragmentMatch(String normalizedQuery, List<String> terms) {
        if (isStrictFragmentQuery(normalizedQuery)) {
            return true;
        }
        if (terms == null) {
            return false;
        }
        for (String term : terms) {
            if (looksLikeHistoryMarkerTerm(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断词元是否像一次长期回归或任务历史 marker 的主体片段。
     *
     * @param term 查询词元。
     * @return 像历史 marker 时返回 true。
     */
    private boolean looksLikeHistoryMarkerTerm(String term) {
        String value = StrUtil.nullToEmpty(term).trim();
        return value.indexOf('-') >= 0 && value.length() >= 24;
    }

    /**
     * 长历史 marker 必须按原片段命中，避免 FTS 拆词召回的公共词污染搜索结果。
     *
     * @param normalizedValue 候选文本的小写形式。
     * @param normalizedQuery 查询文本的小写形式。
     * @return 所有长 marker 片段均满足命中要求时返回 true。
     */
    private boolean strictMarkerFragmentsSatisfied(String normalizedValue, String normalizedQuery) {
        List<String> fragments = strictMarkerFragments(normalizedQuery);
        if (fragments.isEmpty()) {
            return true;
        }
        for (String fragment : fragments) {
            if (!normalizedValue.contains(fragment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从查询中提取较长的连字符历史 marker 片段。
     *
     * @param normalizedQuery 查询文本的小写形式。
     * @return 返回需要精确命中的 marker 片段。
     */
    private List<String> strictMarkerFragments(String normalizedQuery) {
        List<String> fragments = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        String query = StrUtil.nullToEmpty(normalizedQuery).trim();
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (ch == '-' || ch == '_' || Character.isLetterOrDigit(ch)) {
                current.append(ch);
            } else {
                addStrictMarkerFragment(fragments, current);
            }
        }
        addStrictMarkerFragment(fragments, current);
        return fragments;
    }

    /**
     * 追加需要严格匹配的 marker 片段。
     *
     * @param fragments 已收集片段。
     * @param current 当前缓冲。
     */
    private void addStrictMarkerFragment(List<String> fragments, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        String fragment = current.toString();
        current.setLength(0);
        if (fragment.indexOf('-') >= 0 && fragment.length() >= 24) {
            fragments.add(fragment);
        }
    }

    /**
     * 提取查询词元，和 SQLite FTS 兜底拆词保持同一语义。
     *
     * @param query 查询参数。
     * @return 返回查询词元。
     */
    private List<String> searchTerms(String query) {
        List<String> terms = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        int currentKind = 0;
        String value = StrUtil.nullToEmpty(query).trim();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            int kind = searchCharKind(ch);
            if (kind == 0) {
                addSearchTerm(terms, current);
                currentKind = 0;
                continue;
            }
            if (current.length() > 0 && currentKind != 0 && currentKind != kind) {
                addSearchTerm(terms, current);
            }
            current.append(ch);
            currentKind = kind;
        }
        addSearchTerm(terms, current);
        return terms;
    }

    /**
     * 区分 ASCII、中文和其他字母数字，便于拆分中英文混合查询。
     *
     * @param ch 待分类字符。
     * @return 返回字符类别。
     */
    private int searchCharKind(char ch) {
        if ((ch >= 'A' && ch <= 'Z')
                || (ch >= 'a' && ch <= 'z')
                || (ch >= '0' && ch <= '9')
                || ch == '_') {
            return 1;
        }
        if (ch >= '\u4e00' && ch <= '\u9fff') {
            return 2;
        }
        if (Character.isLetterOrDigit(ch)) {
            return 3;
        }
        return 0;
    }

    /**
     * 追加有效查询词元。
     *
     * @param terms 词元集合。
     * @param current 当前缓冲。
     */
    private void addSearchTerm(List<String> terms, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        String term = current.toString().trim();
        current.setLength(0);
        if (term.length() == 0 || terms.contains(term)) {
            return;
        }
        terms.add(term.length() > 80 ? term.substring(0, 80) : term);
    }

    /**
     * 判断压缩摘要是否直接承载检索词；命中时优先作为历史上下文锚点。
     *
     * @param session 会话记录。
     * @param normalizedQuery 已转小写的查询词。
     * @return 压缩摘要命中时返回 true。
     */
    private boolean compressedSummaryMatches(SessionRecord session, String normalizedQuery) {
        return session != null
                && normalizedQuery.length() > 0
                && containsIgnoreCase(session.getCompressedSummary(), normalizedQuery);
    }

    /**
     * 执行trimAroundMatch相关逻辑。
     *
     * @param content 待处理内容。
     * @param query 查询参数。
     * @return 返回trim Around Match结果。
     */
    private String trimAroundMatch(String content, String query) {
        String normalized = content.replace('\r', ' ').replace('\n', ' ').trim();
        if (query.length() == 0) {
            return trim(normalized, 220);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        int index = lower.indexOf(query);
        if (index < 0) {
            return trim(normalized, 220);
        }
        int start = Math.max(0, index - 80);
        int end = Math.min(normalized.length(), index + query.length() + 80);
        String prefix = start > 0 ? "..." : "";
        String suffix = end < normalized.length() ? "..." : "";
        return prefix + normalized.substring(start, end) + suffix;
    }

    /**
     * 解析根用户标识。
     *
     * @param session 会话参数。
     * @return 返回解析后的根用户标识。
     */
    private String resolveRootId(SessionRecord session) throws Exception {
        if (session == null || StrUtil.isBlank(session.getSessionId())) {
            return null;
        }
        return sessionRepository.resolveRootSessionId(session.getSessionId());
    }

    /**
     * 解析标题。
     *
     * @param display 展示参数。
     * @param representative representative 参数。
     * @return 返回解析后的标题。
     */
    private String resolveTitle(SessionRecord display, SessionRecord representative) {
        String title = display == null ? "" : display.getTitle();
        if (StrUtil.isNotBlank(title)) {
            return title;
        }
        if (representative != null && StrUtil.isNotBlank(representative.getTitle())) {
            return representative.getTitle();
        }
        return "session-"
                + (display != null ? display.getSessionId() : representative.getSessionId());
    }

    /**
     * 执行roleLabel相关逻辑。
     *
     * @param role role 参数。
     * @return 返回role Label结果。
     */
    private String roleLabel(ChatRole role) {
        if (role == ChatRole.USER) {
            return "User";
        }
        if (role == ChatRole.ASSISTANT) {
            return "Assistant";
        }
        if (role == ChatRole.TOOL) {
            return "Tool";
        }
        return String.valueOf(role);
    }

    /**
     * 执行trim相关逻辑。
     *
     * @param content 待处理内容。
     * @param maxLength 最大保留字符数。
     * @return 返回trim结果。
     */
    private String trim(String content, int maxLength) {
        String normalized =
                StrUtil.nullToEmpty(content).replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /** 承载搜索Candidate相关状态和辅助逻辑。 */
    private static class SearchCandidate {
        /** 记录搜索Candidate中的展示。 */
        private final SessionRecord display;

        /** 记录搜索Candidate中的representative。 */
        private final SessionRecord representative;

        /**
         * 创建搜索Candidate实例，并注入运行所需依赖。
         *
         * @param display 展示参数。
         * @param representative representative 参数。
         */
        private SearchCandidate(SessionRecord display, SessionRecord representative) {
            this.display = display;
            this.representative = representative;
        }
    }
}
