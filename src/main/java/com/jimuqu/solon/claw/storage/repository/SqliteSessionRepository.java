package com.jimuqu.solon.claw.storage.repository;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SearchTextSupport;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SQLite 会话仓储实现。 */
@RequiredArgsConstructor
public class SqliteSessionRepository implements SessionRepository {
    /** 会话仓储日志，仅输出脱敏异常摘要，避免泄露消息正文或完整 NDJSON。 */
    private static final Logger log = LoggerFactory.getLogger(SqliteSessionRepository.class);

    /** SELECTCOLUMNS的统一常量值。 */
    private static final String SELECT_COLUMNS =
            "session_id, source_key, branch_name, parent_session_id, model_override, service_tier_override, reasoning_effort_override, "
                    + "active_agent_name, platform_message_id, metadata_json, ndjson, title, compressed_summary, system_prompt_snapshot, "
                    + "agent_snapshot_json, goal_state_json, last_learning_at, last_compression_at, "
                    + "last_compression_input_tokens, compression_failure_count, "
                    + "last_compression_failed_at, last_input_tokens, last_output_tokens, "
                    + "last_reasoning_tokens, last_cache_read_tokens, last_cache_write_tokens, "
                    + "last_total_tokens, cumulative_input_tokens, cumulative_output_tokens, "
                    + "cumulative_reasoning_tokens, cumulative_cache_read_tokens, "
                    + "cumulative_cache_write_tokens, cumulative_total_tokens, last_usage_at, "
                    + "last_resolved_provider, last_resolved_model, created_at, updated_at";

    /** SELECTCOLUMNSWITHALIAS的统一常量值。 */
    private static final String SELECT_COLUMNS_WITH_ALIAS =
            "s.session_id, s.source_key, s.branch_name, s.parent_session_id, s.model_override, s.service_tier_override, s.reasoning_effort_override, "
                    + "s.active_agent_name, s.platform_message_id, s.metadata_json, s.ndjson, s.title, s.compressed_summary, "
                    + "s.system_prompt_snapshot, s.agent_snapshot_json, s.goal_state_json, "
                    + "s.last_learning_at, s.last_compression_at, s.last_compression_input_tokens, "
                    + "s.compression_failure_count, s.last_compression_failed_at, s.last_input_tokens, "
                    + "s.last_output_tokens, s.last_reasoning_tokens, s.last_cache_read_tokens, "
                    + "s.last_cache_write_tokens, s.last_total_tokens, s.cumulative_input_tokens, "
                    + "s.cumulative_output_tokens, s.cumulative_reasoning_tokens, "
                    + "s.cumulative_cache_read_tokens, s.cumulative_cache_write_tokens, "
                    + "s.cumulative_total_tokens, s.last_usage_at, s.last_resolved_provider, "
                    + "s.last_resolved_model, s.created_at, s.updated_at";

    /** 上下文待恢复审批队列的统一常量值。 */
    private static final String CONTEXT_PENDING_APPROVAL_QUEUE =
            "_dangerous_command_pending_queue_";

    /** 上下文会话APPROVALS的统一常量值。 */
    private static final String CONTEXT_SESSION_APPROVALS = "_dangerous_command_session_approvals_";

    /** 会话级自动审批状态键，分支复制时必须清理以避免安全状态串联。 */
    private static final String CONTEXT_SESSION_AUTO_APPROVAL =
            "_dangerous_command_session_auto_approval_";

    /** 数据库访问对象。 */
    private final SqliteDatabase database;

    /**
     * 读取绑定会话。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回读取到的绑定会话。
     */
    @Override
    public SessionRecord getBoundSession(String sourceKey) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select session_id from bindings where source_key = ?");
            statement.setString(1, sourceKey);
            ResultSet resultSet = statement.executeQuery();
            try {
                if (resultSet.next()) {
                    return findById(resultSet.getString(1));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return null;
    }

    /**
     * 执行bindNew会话相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回bind New会话结果。
     */
    @Override
    public SessionRecord bindNewSession(String sourceKey) throws Exception {
        long now = System.currentTimeMillis();
        SessionRecord record = new SessionRecord();
        record.setSessionId(IdSupport.newId());
        record.setSourceKey(sourceKey);
        record.setBranchName("main");
        record.setNdjson("");
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        save(record);
        bindSource(sourceKey, record.getSessionId());
        return record;
    }

    /**
     * 执行bind来源相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     */
    @Override
    public void bindSource(String sourceKey, String sessionId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into bindings (source_key, session_id) values (?, ?)");
            statement.setString(1, sourceKey);
            statement.setString(2, sessionId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 克隆会话。
     *
     * @param sourceKey 渠道来源键。
     * @param sourceSessionId 来源会话标识。
     * @param branchName branch名称参数。
     * @return 返回clone会话结果。
     */
    @Override
    public SessionRecord cloneSession(String sourceKey, String sourceSessionId, String branchName)
            throws Exception {
        SessionRecord source = findById(sourceSessionId);
        if (source == null) {
            return bindNewSession(sourceKey);
        }

        long now = System.currentTimeMillis();
        SessionRecord clone = new SessionRecord();
        clone.setSessionId(IdSupport.newId());
        clone.setSourceKey(sourceKey);
        clone.setParentSessionId(source.getSessionId());
        clone.setBranchName(branchName);
        clone.setModelOverride(source.getModelOverride());
        clone.setServiceTierOverride(source.getServiceTierOverride());
        clone.setReasoningEffortOverride(source.getReasoningEffortOverride());
        clone.setActiveAgentName(source.getActiveAgentName());
        clone.setPlatformMessageId(source.getPlatformMessageId());
        clone.setMetadataJson(source.getMetadataJson());
        clone.setNdjson(source.getNdjson());
        clone.setTitle(source.getTitle());
        clone.setCompressedSummary(source.getCompressedSummary());
        clone.setSystemPromptSnapshot(source.getSystemPromptSnapshot());
        clone.setAgentSnapshotJson(sanitizeAgentSnapshotForBranch(source.getAgentSnapshotJson()));
        clone.setGoalStateJson(source.getGoalStateJson());
        clone.setLastCompressionAt(source.getLastCompressionAt());
        clone.setLastCompressionInputTokens(source.getLastCompressionInputTokens());
        clone.setCompressionFailureCount(source.getCompressionFailureCount());
        clone.setLastCompressionFailedAt(source.getLastCompressionFailedAt());
        clone.setLastResolvedProvider(source.getLastResolvedProvider());
        clone.setLastResolvedModel(source.getLastResolvedModel());
        clone.setCreatedAt(now);
        clone.setUpdatedAt(now);
        save(clone);
        bindSource(sourceKey, clone.getSessionId());
        return clone;
    }

    /**
     * 清理Agent Snapshot For Branch。
     *
     * @param snapshotJson snapshotJSON参数。
     * @return 返回Agent Snapshot For Branch结果。
     */
    private String sanitizeAgentSnapshotForBranch(String snapshotJson) {
        if (StrUtil.isBlank(snapshotJson)) {
            return snapshotJson;
        }
        try {
            FlowContext context = FlowContext.fromJson(snapshotJson);
            context.remove(CONTEXT_SESSION_APPROVALS);
            context.remove(CONTEXT_PENDING_APPROVAL_QUEUE);
            context.remove(CONTEXT_SESSION_AUTO_APPROVAL);
            return context.toJson();
        } catch (RuntimeException e) {
            log.warn(
                    "SQLite session branch snapshot cleanup failed; clone continues with empty snapshot: error={}",
                    exceptionSummary(e));
            return null;
        }
    }

    /**
     * 根据标识查找对应数据。
     *
     * @param sessionId 当前会话标识。
     * @return 返回按标识查找得到的结果。
     */
    @Override
    public SessionRecord findById(String sessionId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select " + SELECT_COLUMNS + " from sessions where session_id = ?");
            statement.setString(1, sessionId);
            ResultSet resultSet = statement.executeQuery();
            try {
                if (resultSet.next()) {
                    return map(resultSet);
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return null;
    }

    /**
     * 根据来源And Branch查找对应数据。
     *
     * @param sourceKey 渠道来源键。
     * @param branchName branch名称参数。
     * @return 返回按来源And Branch查找得到的结果。
     */
    @Override
    public SessionRecord findBySourceAndBranch(String sourceKey, String branchName)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select "
                                    + SELECT_COLUMNS
                                    + " from sessions where source_key = ? and branch_name = ? order by updated_at desc limit 1");
            statement.setString(1, sourceKey);
            statement.setString(2, branchName);
            ResultSet resultSet = statement.executeQuery();
            try {
                if (resultSet.next()) {
                    return map(resultSet);
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return null;
    }

    /**
     * 查找Resume Candidates。
     *
     * @param reference 引用参数。
     * @param limit 最大返回数量。
     * @return 返回Resume Candidates结果。
     */
    @Override
    public List<SessionRecord> findResumeCandidates(String reference, int limit) throws Exception {
        List<SessionRecord> results = new ArrayList<SessionRecord>();
        String value = StrUtil.nullToEmpty(reference).trim();
        if (value.length() == 0) {
            return results;
        }
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select "
                                    + SELECT_COLUMNS
                                    + " from sessions where session_id like ? or title = ? order by updated_at desc limit ?");
            statement.setString(1, value + "%");
            statement.setString(2, value);
            statement.setInt(3, Math.max(1, limit));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    results.add(map(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return results;
    }

    /**
     * 执行save，服务于SQLite会话主流程相关逻辑。
     *
     * @param sessionRecord 会话记录参数。
     */
    @Override
    public void save(SessionRecord sessionRecord) throws Exception {
        long updatedAt =
                sessionRecord.getUpdatedAt() > 0
                        ? sessionRecord.getUpdatedAt()
                        : System.currentTimeMillis();
        long createdAt =
                sessionRecord.getCreatedAt() > 0 ? sessionRecord.getCreatedAt() : updatedAt;

        Connection connection = database.openConnection();
        try {
            connection.setAutoCommit(false);
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into sessions (session_id, source_key, branch_name, parent_session_id, model_override, service_tier_override, reasoning_effort_override, active_agent_name, platform_message_id, metadata_json, ndjson, title, compressed_summary, system_prompt_snapshot, agent_snapshot_json, goal_state_json, last_learning_at, last_compression_at, last_compression_input_tokens, compression_failure_count, last_compression_failed_at, last_input_tokens, last_output_tokens, last_reasoning_tokens, last_cache_read_tokens, last_cache_write_tokens, last_total_tokens, cumulative_input_tokens, cumulative_output_tokens, cumulative_reasoning_tokens, cumulative_cache_read_tokens, cumulative_cache_write_tokens, cumulative_total_tokens, last_usage_at, last_resolved_provider, last_resolved_model, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, sessionRecord.getSessionId());
            statement.setString(2, sessionRecord.getSourceKey());
            statement.setString(3, sessionRecord.getBranchName());
            statement.setString(4, sessionRecord.getParentSessionId());
            statement.setString(5, sessionRecord.getModelOverride());
            statement.setString(6, sessionRecord.getServiceTierOverride());
            statement.setString(7, sessionRecord.getReasoningEffortOverride());
            statement.setString(8, sessionRecord.getActiveAgentName());
            statement.setString(9, sessionRecord.getPlatformMessageId());
            statement.setString(10, sessionRecord.getMetadataJson());
            statement.setString(11, sessionRecord.getNdjson());
            statement.setString(12, sessionRecord.getTitle());
            statement.setString(13, sessionRecord.getCompressedSummary());
            statement.setString(14, sessionRecord.getSystemPromptSnapshot());
            statement.setString(15, sessionRecord.getAgentSnapshotJson());
            statement.setString(16, sessionRecord.getGoalStateJson());
            statement.setLong(17, sessionRecord.getLastLearningAt());
            statement.setLong(18, sessionRecord.getLastCompressionAt());
            statement.setInt(19, sessionRecord.getLastCompressionInputTokens());
            statement.setInt(20, sessionRecord.getCompressionFailureCount());
            statement.setLong(21, sessionRecord.getLastCompressionFailedAt());
            statement.setLong(22, sessionRecord.getLastInputTokens());
            statement.setLong(23, sessionRecord.getLastOutputTokens());
            statement.setLong(24, sessionRecord.getLastReasoningTokens());
            statement.setLong(25, sessionRecord.getLastCacheReadTokens());
            statement.setLong(26, sessionRecord.getLastCacheWriteTokens());
            statement.setLong(27, sessionRecord.getLastTotalTokens());
            statement.setLong(28, sessionRecord.getCumulativeInputTokens());
            statement.setLong(29, sessionRecord.getCumulativeOutputTokens());
            statement.setLong(30, sessionRecord.getCumulativeReasoningTokens());
            statement.setLong(31, sessionRecord.getCumulativeCacheReadTokens());
            statement.setLong(32, sessionRecord.getCumulativeCacheWriteTokens());
            statement.setLong(33, sessionRecord.getCumulativeTotalTokens());
            statement.setLong(34, sessionRecord.getLastUsageAt());
            statement.setString(35, sessionRecord.getLastResolvedProvider());
            statement.setString(36, sessionRecord.getLastResolvedModel());
            statement.setLong(37, createdAt);
            statement.setLong(38, updatedAt);
            statement.executeUpdate();
            statement.close();

            upsertSearchIndex(connection, sessionRecord);
            connection.commit();
            sessionRecord.setCreatedAt(createdAt);
            sessionRecord.setUpdatedAt(updatedAt);
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
            connection.close();
        }
    }

    /**
     * 执行搜索相关逻辑。
     *
     * @param keyword keyword 参数。
     * @param limit 最大返回数量。
     * @return 返回搜索结果。
     */
    @Override
    public List<SessionRecord> search(String keyword, int limit) throws Exception {
        LinkedHashMap<String, SessionRecord> results = new LinkedHashMap<String, SessionRecord>();
        int safeLimit = Math.max(1, limit);
        Connection connection = database.openConnection();
        try {
            for (String ftsQuery : sessionFtsQueries(keyword)) {
                try {
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "select "
                                            + SELECT_COLUMNS_WITH_ALIAS
                                            + " "
                                            + "from sessions_fts f join sessions s on s.session_id = f.session_id "
                                            + "where sessions_fts match ? order by bm25(sessions_fts), s.updated_at desc limit ?");
                    statement.setString(1, ftsQuery);
                    statement.setInt(2, safeLimit);
                    ResultSet resultSet = statement.executeQuery();
                    try {
                        while (resultSet.next()) {
                            putSearchResult(results, map(resultSet));
                        }
                    } finally {
                        resultSet.close();
                        statement.close();
                    }
                } catch (SQLException | RuntimeException e) {
                    log.debug(
                            "SQLite session FTS search failed; LIKE fallback will continue: error={}",
                            exceptionSummary(e));
                    // FTS5 对 '-'、冒号和中文长串较敏感；失败时继续尝试安全拆词和 LIKE 兜底。
                }
                if (results.size() >= safeLimit) {
                    break;
                }
            }
            appendLikeSearchResults(connection, results, keyword, safeLimit);
        } finally {
            connection.close();
        }
        return new ArrayList<SessionRecord>(results.values());
    }

    /** 生成安全的 FTS 查询：把连字符、中文长串和多关键词拆成 OR 召回表达式。 */
    private List<String> sessionFtsQueries(String keyword) {
        List<String> queries = new ArrayList<String>();
        String raw = StrUtil.nullToEmpty(keyword).trim();
        List<String> terms = sessionSearchTerms(raw);
        if (!terms.isEmpty()) {
            queries.add(joinQuotedTerms(terms));
        }
        String safeRaw = quoteFtsTerm(raw);
        if (StrUtil.isNotBlank(safeRaw) && !queries.contains(safeRaw)) {
            queries.add(safeRaw);
        }
        return queries;
    }

    /** 从用户搜索词提取可单独召回的词元，避免 '-' 被 FTS 当成 NOT/列语法。 */
    private List<String> sessionSearchTerms(String keyword) {
        LinkedHashSet<String> terms = new LinkedHashSet<String>();
        StringBuilder current = new StringBuilder();
        int currentKind = 0;
        for (int i = 0; i < keyword.length(); i++) {
            char ch = keyword.charAt(i);
            int kind = SearchTextSupport.searchCharKind(ch);
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
        return new ArrayList<String>(terms);
    }

    /** 记录有效搜索词，并限制过长词元避免生成异常大的 SQL/FTS 表达式。 */
    private void addSearchTerm(LinkedHashSet<String> terms, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        String term = current.toString().trim();
        current.setLength(0);
        if (term.length() == 0) {
            return;
        }
        terms.add(term.length() > 80 ? term.substring(0, 80) : term);
    }

    /** 把词元拼成 FTS5 OR 表达式，提升跨渠道历史召回的容错率。 */
    private String joinQuotedTerms(List<String> terms) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (String term : terms) {
            String quoted = quoteFtsTerm(term);
            if (StrUtil.isBlank(quoted)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" OR ");
            }
            builder.append(quoted);
            count++;
            if (count >= 12) {
                break;
            }
        }
        return builder.toString();
    }

    /** FTS5 短语转义，避免特殊符号被解释成查询语法。 */
    private String quoteFtsTerm(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.length() == 0) {
            return "";
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    /** FTS 召回不足时按整串和逐词 LIKE 兜底，兼容中文长串与 FTS 分词缺失。 */
    private void appendLikeSearchResults(
            Connection connection,
            LinkedHashMap<String, SessionRecord> results,
            String keyword,
            int limit)
            throws Exception {
        List<String> patterns = likePatterns(keyword);
        if (patterns.isEmpty() || results.size() >= limit) {
            return;
        }
        StringBuilder sql = new StringBuilder();
        sql.append("select ").append(SELECT_COLUMNS).append(" from sessions where ");
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) {
                sql.append(" or ");
            }
            sql.append(
                    "(ndjson like ? escape '\\' or compressed_summary like ? escape '\\' or title like ? escape '\\')");
        }
        sql.append(" order by updated_at desc limit ?");
        PreparedStatement fallback = connection.prepareStatement(sql.toString());
        int index = 1;
        for (String pattern : patterns) {
            fallback.setString(index++, pattern);
            fallback.setString(index++, pattern);
            fallback.setString(index++, pattern);
        }
        fallback.setInt(index, Math.max(limit, 10));
        ResultSet resultSet = fallback.executeQuery();
        try {
            while (resultSet.next() && results.size() < limit) {
                putSearchResult(results, map(resultSet));
            }
        } finally {
            resultSet.close();
            fallback.close();
        }
    }

    /** LIKE 兜底先查完整串，再查拆分后的关键词。 */
    private List<String> likePatterns(String keyword) {
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        String raw = StrUtil.nullToEmpty(keyword).trim();
        if (raw.length() > 0) {
            values.add(raw);
        }
        for (String term : sessionSearchTerms(raw)) {
            values.add(term);
            if (values.size() >= 12) {
                break;
            }
        }
        List<String> patterns = new ArrayList<String>();
        for (String value : values) {
            patterns.add("%" + escapeLike(value) + "%");
        }
        return patterns;
    }

    /** 转义 LIKE 通配符，防止用户输入扩大为无意义的全表通配。 */
    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 以 session_id 去重保序，避免 FTS 和 LIKE 兜底重复返回同一会话。 */
    private void putSearchResult(
            LinkedHashMap<String, SessionRecord> results, SessionRecord record) {
        if (record != null && StrUtil.isNotBlank(record.getSessionId())) {
            results.put(record.getSessionId(), record);
        }
    }

    /**
     * 列出Recent。
     *
     * @param limit 最大返回数量。
     * @return 返回Recent列表。
     */
    @Override
    public List<SessionRecord> listRecent(int limit) throws Exception {
        return listRecent(limit, 0);
    }

    /**
     * 列出Recent。
     *
     * @param limit 最大返回数量。
     * @param offset 分页偏移量。
     * @return 返回Recent列表。
     */
    @Override
    public List<SessionRecord> listRecent(int limit, int offset) throws Exception {
        List<SessionRecord> results = new ArrayList<SessionRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select "
                                    + SELECT_COLUMNS
                                    + " "
                                    + "from sessions order by updated_at desc limit ? offset ?");
            statement.setInt(1, limit);
            statement.setInt(2, Math.max(0, offset));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    results.add(map(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return results;
    }

    /**
     * 列出Pending Agent Sessions。
     *
     * @param updatedAfterMillis updatedAfterMillis 参数。
     * @param limit 最大返回数量。
     * @return 返回Pending Agent Sessions列表。
     */
    @Override
    public List<SessionRecord> listPendingAgentSessions(long updatedAfterMillis, int limit)
            throws Exception {
        List<SessionRecord> results = new ArrayList<SessionRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select "
                                    + SELECT_COLUMNS
                                    + " "
                                    + "from sessions where updated_at >= ? "
                                    + "and agent_snapshot_json like ? order by updated_at desc limit ?");
            statement.setLong(1, Math.max(0L, updatedAfterMillis));
            statement.setString(2, "%\"_agent_pending_\":true%");
            statement.setInt(3, Math.max(1, limit));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    results.add(map(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return results;
    }

    /**
     * 执行次数全部相关逻辑。
     *
     * @return 返回次数全部结果。
     */
    @Override
    public int countAll() throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("select count(1) from sessions");
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 列出Lineage。
     *
     * @param sessionId 当前会话标识。
     * @return 返回Lineage列表。
     */
    @Override
    public List<SessionRecord> listLineage(String sessionId) throws Exception {
        if (StrUtil.isBlank(sessionId)) {
            return new ArrayList<SessionRecord>();
        }
        String rootSessionId = resolveRootSessionId(sessionId);
        if (StrUtil.isBlank(rootSessionId)) {
            return new ArrayList<SessionRecord>();
        }
        List<SessionRecord> result = new ArrayList<SessionRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "with recursive lineage(session_id) as ("
                                    + "select session_id from sessions where session_id = ? "
                                    + "union "
                                    + "select s.session_id from sessions s join lineage l on s.parent_session_id = l.session_id"
                                    + ") select "
                                    + SELECT_COLUMNS
                                    + " from sessions where session_id in (select session_id from lineage)"
                                    + " order by created_at asc, session_id asc");
            statement.setString(1, rootSessionId);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    result.add(map(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return result;
    }

    /**
     * 执行latestDescendant路径相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回latest Descendant路径。
     */
    @Override
    public List<String> latestDescendantPath(String sessionId) throws Exception {
        SessionRecord root = findById(sessionId);
        if (root == null) {
            return new ArrayList<String>();
        }
        List<SessionRecord> records = listLineage(root.getSessionId());
        List<String> path = new ArrayList<String>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        SessionRecord current = root;
        while (current != null && seen.add(current.getSessionId())) {
            path.add(current.getSessionId());
            SessionRecord newest = null;
            for (SessionRecord candidate : records) {
                if (candidate == null
                        || !current.getSessionId().equals(candidate.getParentSessionId())
                        || seen.contains(candidate.getSessionId())) {
                    continue;
                }
                if (newest == null || candidate.getCreatedAt() > newest.getCreatedAt()) {
                    newest = candidate;
                }
            }
            current = newest;
        }
        return path;
    }

    /**
     * 执行delete，服务于SQLite会话主流程相关逻辑。
     *
     * @param sessionId 当前会话标识。
     */
    @Override
    public void delete(String sessionId) throws Exception {
        Connection connection = database.openConnection();
        try {
            connection.setAutoCommit(false);
            deleteSearchIndex(connection, sessionId);

            PreparedStatement deleteBindings =
                    connection.prepareStatement("delete from bindings where session_id = ?");
            deleteBindings.setString(1, sessionId);
            deleteBindings.executeUpdate();
            deleteBindings.close();

            PreparedStatement deleteSession =
                    connection.prepareStatement("delete from sessions where session_id = ?");
            deleteSession.setString(1, sessionId);
            deleteSession.executeUpdate();
            deleteSession.close();
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
            connection.close();
        }
    }

    /**
     * 写入模型Override。
     *
     * @param sessionId 当前会话标识。
     * @param modelOverride 模型Override标识或键值。
     */
    @Override
    public void setModelOverride(String sessionId, String modelOverride) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update sessions set model_override = ?, updated_at = ? where session_id = ?");
            statement.setString(1, modelOverride);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, sessionId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 写入服务Tier Override。
     *
     * @param sessionId 当前会话标识。
     * @param serviceTierOverride serviceTierOverride标识或键值。
     */
    @Override
    public void setServiceTierOverride(String sessionId, String serviceTierOverride)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update sessions set service_tier_override = ?, updated_at = ? where session_id = ?");
            statement.setString(1, serviceTierOverride);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, sessionId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 写入Reasoning Effort Override。
     *
     * @param sessionId 当前会话标识。
     * @param reasoningEffortOverride 推理EffortOverride标识或键值。
     */
    @Override
    public void setReasoningEffortOverride(String sessionId, String reasoningEffortOverride)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update sessions set reasoning_effort_override = ?, updated_at = ? where session_id = ?");
            statement.setString(1, reasoningEffortOverride);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, sessionId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 写入Active Agent名称。
     *
     * @param sessionId 当前会话标识。
     * @param agentName Agent名称参数。
     */
    @Override
    public void setActiveAgentName(String sessionId, String agentName) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update sessions set active_agent_name = ?, updated_at = ? where session_id = ?");
            statement.setString(1, agentName);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, sessionId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 清理Active Agent名称。
     *
     * @param agentName Agent名称参数。
     */
    @Override
    public void clearActiveAgentName(String agentName) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update sessions set active_agent_name = null, updated_at = ? where active_agent_name = ?");
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, agentName);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 写入Goal状态。
     *
     * @param sessionId 当前会话标识。
     * @param goalStateJson 目标状态JSON参数。
     */
    @Override
    public void setGoalState(String sessionId, String goalStateJson) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update sessions set goal_state_json = ?, updated_at = ? where session_id = ?");
            statement.setString(1, goalStateJson);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, sessionId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 写入Last Learning时间。
     *
     * @param sessionId 当前会话标识。
     * @param lastLearningAt lastLearningAt 参数。
     */
    @Override
    public void setLastLearningAt(String sessionId, long lastLearningAt) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update sessions set last_learning_at = ?, updated_at = ? where session_id = ?");
            statement.setLong(1, lastLearningAt);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, sessionId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /** 将会话同步到 FTS5 索引。 */
    private void upsertSearchIndex(Connection connection, SessionRecord sessionRecord)
            throws Exception {
        try {
            deleteSearchIndex(connection, sessionRecord.getSessionId());

            PreparedStatement insert =
                    connection.prepareStatement(
                            "insert into sessions_fts (session_id, title, compressed_summary, ndjson, tool_names, tool_calls) values (?, ?, ?, ?, ?, ?)");
            try {
                ToolIndex toolIndex = buildToolIndex(sessionRecord.getNdjson());
                insert.setString(1, sessionRecord.getSessionId());
                insert.setString(2, sessionRecord.getTitle());
                insert.setString(3, sessionRecord.getCompressedSummary());
                insert.setString(4, sessionRecord.getNdjson());
                insert.setString(5, toolIndex.names);
                insert.setString(6, toolIndex.calls);
                insert.executeUpdate();
            } finally {
                insert.close();
            }
        } catch (SQLException | RuntimeException e) {
            log.warn(
                    "SQLite session search index update failed; session save continues: error={}",
                    exceptionSummary(e));
            // 保留此处实现约束，避免后续维护时破坏既有行为。
        }
    }

    /**
     * 删除搜索Index。
     *
     * @param connection 连接参数。
     * @param sessionId 当前会话标识。
     */
    private void deleteSearchIndex(Connection connection, String sessionId) {
        try {
            PreparedStatement delete =
                    connection.prepareStatement("delete from sessions_fts where session_id = ?");
            try {
                delete.setString(1, sessionId);
                delete.executeUpdate();
            } finally {
                delete.close();
            }
        } catch (SQLException | RuntimeException e) {
            log.debug(
                    "SQLite session search index delete failed; session delete continues: error={}",
                    exceptionSummary(e));
            // 这里的失败不应影响主流程或安全关键路径。
        }
    }

    /**
     * 构建工具Index。
     *
     * @param ndjson ndjson 参数。
     * @return 返回创建好的工具Index。
     */
    @SuppressWarnings("unchecked")
    private ToolIndex buildToolIndex(String ndjson) {
        StringBuilder names = new StringBuilder();
        StringBuilder calls = new StringBuilder();
        try {
            List<ChatMessage> messages = MessageSupport.loadMessages(ndjson);
            for (ChatMessage message : messages) {
                if (!(message instanceof AssistantMessage)) {
                    continue;
                }
                AssistantMessage assistant = (AssistantMessage) message;
                if (assistant.getToolCalls() != null) {
                    for (ToolCall toolCall : assistant.getToolCalls()) {
                        append(names, toolCall == null ? "" : toolCall.getName());
                        if (toolCall != null) {
                            append(calls, toolCall.getName());
                            append(calls, toolCall.getArgumentsStr());
                            append(calls, ONode.serialize(toolCall.getArguments()));
                        }
                    }
                }
                if (assistant.getToolCallsRaw() != null) {
                    for (Map raw : assistant.getToolCallsRaw()) {
                        Object function = raw == null ? null : raw.get("function");
                        if (function instanceof Map) {
                            Map functionMap = (Map) function;
                            append(names, String.valueOf(functionMap.get("name")));
                            append(calls, String.valueOf(functionMap.get("name")));
                            append(calls, String.valueOf(functionMap.get("arguments")));
                        } else {
                            append(calls, ONode.serialize(raw));
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug(
                    "SQLite session tool index parse failed; tool search fields remain empty: error={}",
                    exceptionSummary(e));
        }
        return new ToolIndex(names.toString(), calls.toString());
    }

    /**
     * 生成会话仓储日志中的单行脱敏异常摘要，避免输出消息正文、token 或完整 NDJSON。
     *
     * @param error 当前捕获的异常。
     * @return 返回异常类型和脱敏后的短消息。
     */
    private static String exceptionSummary(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        if (StrUtil.isBlank(message)) {
            return error.getClass().getSimpleName();
        }
        String safeMessage = message.replaceAll("[\\r\\n\\t]+", " ");
        safeMessage =
                safeMessage.replaceAll(
                        "(?i)(api[_-]?key|token|authorization|secret|password)\\s*[:=]\\s*[^,;\\s]+",
                        "$1=<redacted>");
        safeMessage =
                safeMessage.replaceAll(
                        "\"(content|ndjson|message|messages|transcript)\"\\s*:\\s*\"(?:\\\\.|[^\"])*\"",
                        "\"$1\":\"<redacted>\"");
        safeMessage = safeMessage.replaceAll("\"(?:\\\\.|[^\"]){32,}\"", "\"<redacted>\"");
        safeMessage = safeMessage.replaceAll("'(?:\\\\.|[^']){32,}'", "'<redacted>'");
        safeMessage =
                safeMessage.replaceAll(
                        "\\b[A-Za-z0-9_./+=:-]{48,}\\b",
                        "<redacted>");
        if (safeMessage.length() > 160) {
            safeMessage = safeMessage.substring(0, 160) + "...";
        }
        return error.getClass().getSimpleName() + ": " + safeMessage;
    }

    /**
     * 执行append相关逻辑。
     *
     * @param buffer buffer 参数。
     * @param value 待规范化或校验的原始值。
     */
    private void append(StringBuilder buffer, String value) {
        if (StrUtil.isBlank(value) || "null".equals(value)) {
            return;
        }
        if (buffer.length() > 0) {
            buffer.append('\n');
        }
        buffer.append(value.trim());
    }

    /** 承载工具索引相关状态和辅助逻辑。 */
    private static class ToolIndex {
        /** 记录工具索引中的名称。 */
        private final String names;

        /** 记录工具索引中的calls。 */
        private final String calls;

        /**
         * 创建工具Index实例，并注入运行所需依赖。
         *
         * @param names names 参数。
         * @param calls calls 参数。
         */
        private ToolIndex(String names, String calls) {
            this.names = names;
            this.calls = calls;
        }
    }

    /** 结果集映射。 */
    private SessionRecord map(ResultSet resultSet) throws Exception {
        SessionRecord record = new SessionRecord();
        record.setSessionId(resultSet.getString("session_id"));
        record.setSourceKey(resultSet.getString("source_key"));
        record.setBranchName(resultSet.getString("branch_name"));
        record.setParentSessionId(resultSet.getString("parent_session_id"));
        record.setModelOverride(resultSet.getString("model_override"));
        record.setServiceTierOverride(resultSet.getString("service_tier_override"));
        record.setReasoningEffortOverride(resultSet.getString("reasoning_effort_override"));
        record.setActiveAgentName(resultSet.getString("active_agent_name"));
        record.setPlatformMessageId(resultSet.getString("platform_message_id"));
        record.setMetadataJson(resultSet.getString("metadata_json"));
        record.setNdjson(resultSet.getString("ndjson"));
        record.setTitle(resultSet.getString("title"));
        record.setCompressedSummary(resultSet.getString("compressed_summary"));
        record.setSystemPromptSnapshot(resultSet.getString("system_prompt_snapshot"));
        record.setAgentSnapshotJson(resultSet.getString("agent_snapshot_json"));
        record.setGoalStateJson(resultSet.getString("goal_state_json"));
        record.setLastLearningAt(resultSet.getLong("last_learning_at"));
        record.setLastCompressionAt(resultSet.getLong("last_compression_at"));
        record.setLastCompressionInputTokens(resultSet.getInt("last_compression_input_tokens"));
        record.setCompressionFailureCount(resultSet.getInt("compression_failure_count"));
        record.setLastCompressionFailedAt(resultSet.getLong("last_compression_failed_at"));
        record.setLastInputTokens(resultSet.getLong("last_input_tokens"));
        record.setLastOutputTokens(resultSet.getLong("last_output_tokens"));
        record.setLastReasoningTokens(resultSet.getLong("last_reasoning_tokens"));
        record.setLastCacheReadTokens(resultSet.getLong("last_cache_read_tokens"));
        record.setLastCacheWriteTokens(resultSet.getLong("last_cache_write_tokens"));
        record.setLastTotalTokens(resultSet.getLong("last_total_tokens"));
        record.setCumulativeInputTokens(resultSet.getLong("cumulative_input_tokens"));
        record.setCumulativeOutputTokens(resultSet.getLong("cumulative_output_tokens"));
        record.setCumulativeReasoningTokens(resultSet.getLong("cumulative_reasoning_tokens"));
        record.setCumulativeCacheReadTokens(resultSet.getLong("cumulative_cache_read_tokens"));
        record.setCumulativeCacheWriteTokens(resultSet.getLong("cumulative_cache_write_tokens"));
        record.setCumulativeTotalTokens(resultSet.getLong("cumulative_total_tokens"));
        record.setLastUsageAt(resultSet.getLong("last_usage_at"));
        record.setLastResolvedProvider(resultSet.getString("last_resolved_provider"));
        record.setLastResolvedModel(resultSet.getString("last_resolved_model"));
        record.setCreatedAt(resultSet.getLong("created_at"));
        record.setUpdatedAt(resultSet.getLong("updated_at"));
        return record;
    }
}
