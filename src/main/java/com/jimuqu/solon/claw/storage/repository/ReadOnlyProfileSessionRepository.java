package com.jimuqu.solon.claw.storage.repository;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** 以 SQLite 只读模式访问另一个 Profile 的会话数据，禁止任何写入或 schema 初始化。 */
public class ReadOnlyProfileSessionRepository implements SessionRepository {
    /** 搜索所需的稳定会话列，避免跨 Profile 读取依赖非必要运行字段。 */
    private static final String SELECT_COLUMNS =
            "session_id, source_key, branch_name, parent_session_id, model_override, "
                    + "service_tier_override, reasoning_effort_override, active_agent_name, "
                    + "platform_message_id, metadata_json, ndjson, title, compressed_summary, "
                    + "system_prompt_snapshot, agent_snapshot_json, goal_state_json, "
                    + "last_learning_at, last_compression_at, last_compression_input_tokens, "
                    + "compression_failure_count, last_compression_failed_at, last_input_tokens, "
                    + "last_output_tokens, last_reasoning_tokens, last_cache_read_tokens, "
                    + "last_cache_write_tokens, last_total_tokens, cumulative_input_tokens, "
                    + "cumulative_output_tokens, cumulative_reasoning_tokens, "
                    + "cumulative_cache_read_tokens, cumulative_cache_write_tokens, "
                    + "cumulative_total_tokens, last_usage_at, last_resolved_provider, "
                    + "last_resolved_model, created_at, updated_at";

    /** SQLite 只读 URI；mode=ro 会在数据库文件不存在或发生写入时直接失败。 */
    private final String jdbcUrl;

    /**
     * 创建跨 Profile 只读会话仓储。
     *
     * @param stateDb 目标 Profile 的 data/state.db。
     * @throws IOException 数据库文件不存在或不是普通文件。
     */
    public ReadOnlyProfileSessionRepository(Path stateDb) throws IOException {
        if (stateDb == null || !Files.isRegularFile(stateDb)) {
            throw new IOException("Profile session database does not exist: " + stateDb);
        }
        Path normalized = stateDb.toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:sqlite:" + normalized.toUri().toASCIIString() + "?mode=ro";
    }

    /** 打开启用 query_only 的短生命周期只读连接。 */
    private Connection openConnection() throws Exception {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try {
            Statement statement = connection.createStatement();
            try {
                statement.execute("pragma query_only=ON");
                statement.execute("pragma busy_timeout=5000");
            } finally {
                statement.close();
            }
            return connection;
        } catch (Exception e) {
            connection.close();
            throw e;
        }
    }

    /** 按来源键读取绑定会话，不创建缺失绑定。 */
    @Override
    public SessionRecord getBoundSession(String sourceKey) throws Exception {
        Connection connection = openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select session_id from bindings where source_key = ?");
            try {
                statement.setString(1, sourceKey);
                ResultSet resultSet = statement.executeQuery();
                try {
                    return resultSet.next() ? findById(resultSet.getString(1)) : null;
                } finally {
                    resultSet.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /** 跨 Profile 仓储禁止创建会话。 */
    @Override
    public SessionRecord bindNewSession(String sourceKey) {
        throw readOnly();
    }

    /** 跨 Profile 仓储禁止修改来源绑定。 */
    @Override
    public void bindSource(String sourceKey, String sessionId) {
        throw readOnly();
    }

    /** 跨 Profile 仓储禁止克隆会话。 */
    @Override
    public SessionRecord cloneSession(String sourceKey, String sourceSessionId, String branchName) {
        throw readOnly();
    }

    /** 按会话 ID 读取目标 Profile 的会话。 */
    @Override
    public SessionRecord findById(String sessionId) throws Exception {
        Connection connection = openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select " + SELECT_COLUMNS + " from sessions where session_id = ?");
            try {
                statement.setString(1, sessionId);
                ResultSet resultSet = statement.executeQuery();
                try {
                    return resultSet.next() ? map(resultSet) : null;
                } finally {
                    resultSet.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /** 按来源键和分支读取最近会话。 */
    @Override
    public SessionRecord findBySourceAndBranch(String sourceKey, String branchName)
            throws Exception {
        Connection connection = openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select "
                                    + SELECT_COLUMNS
                                    + " from sessions where source_key = ? and branch_name = ? "
                                    + "order by updated_at desc limit 1");
            try {
                statement.setString(1, sourceKey);
                statement.setString(2, branchName);
                ResultSet resultSet = statement.executeQuery();
                try {
                    return resultSet.next() ? map(resultSet) : null;
                } finally {
                    resultSet.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /** 按 ID 前缀或标题查找可恢复会话。 */
    @Override
    public List<SessionRecord> findResumeCandidates(String reference, int limit) throws Exception {
        List<SessionRecord> results = new ArrayList<SessionRecord>();
        String value = StrUtil.nullToEmpty(reference).trim();
        if (value.length() == 0) {
            return results;
        }
        Connection connection = openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select "
                                    + SELECT_COLUMNS
                                    + " from sessions where session_id like ? or title = ? "
                                    + "order by updated_at desc limit ?");
            try {
                statement.setString(1, value + "%");
                statement.setString(2, value);
                statement.setInt(3, Math.max(1, limit));
                appendRows(results, statement);
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
        return results;
    }

    /** 跨 Profile 仓储禁止保存会话。 */
    @Override
    public void save(SessionRecord sessionRecord) {
        throw readOnly();
    }

    /** 使用参数化 LIKE 在目标 Profile 会话正文、摘要和标题中检索。 */
    @Override
    public List<SessionRecord> search(String keyword, int limit) throws Exception {
        List<SessionRecord> results = new ArrayList<SessionRecord>();
        String pattern = "%" + escapeLike(StrUtil.nullToEmpty(keyword).trim()) + "%";
        Connection connection = openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select "
                                    + SELECT_COLUMNS
                                    + " from sessions where title like ? escape '\\' "
                                    + "or compressed_summary like ? escape '\\' "
                                    + "or ndjson like ? escape '\\' order by updated_at desc limit ?");
            try {
                statement.setString(1, pattern);
                statement.setString(2, pattern);
                statement.setString(3, pattern);
                statement.setInt(4, Math.max(1, limit));
                appendRows(results, statement);
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
        return results;
    }

    /** 按更新时间读取最近会话。 */
    @Override
    public List<SessionRecord> listRecent(int limit) throws Exception {
        return listRecent(limit, 0);
    }

    /** 按更新时间分页读取最近会话。 */
    @Override
    public List<SessionRecord> listRecent(int limit, int offset) throws Exception {
        List<SessionRecord> results = new ArrayList<SessionRecord>();
        Connection connection = openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select "
                                    + SELECT_COLUMNS
                                    + " from sessions order by updated_at desc limit ? offset ?");
            try {
                statement.setInt(1, Math.max(1, limit));
                statement.setInt(2, Math.max(0, offset));
                appendRows(results, statement);
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
        return results;
    }

    /** 只读列出仍带 Agent pending 快照的最近会话。 */
    @Override
    public List<SessionRecord> listPendingAgentSessions(long updatedAfterMillis, int limit)
            throws Exception {
        List<SessionRecord> results = new ArrayList<SessionRecord>();
        Connection connection = openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select "
                                    + SELECT_COLUMNS
                                    + " from sessions where updated_at >= ? and agent_snapshot_json like ? "
                                    + "order by updated_at desc limit ?");
            try {
                statement.setLong(1, Math.max(0L, updatedAfterMillis));
                statement.setString(2, "%\"_agent_pending_\":true%");
                statement.setInt(3, Math.max(1, limit));
                appendRows(results, statement);
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
        return results;
    }

    /** 返回目标 Profile 的会话总数。 */
    @Override
    public int countAll() throws Exception {
        Connection connection = openConnection();
        try {
            Statement statement = connection.createStatement();
            try {
                ResultSet resultSet = statement.executeQuery("select count(*) from sessions");
                try {
                    return resultSet.next() ? resultSet.getInt(1) : 0;
                } finally {
                    resultSet.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /** 跨 Profile 仓储禁止删除会话。 */
    @Override
    public void delete(String sessionId) {
        throw readOnly();
    }

    /** 跨 Profile 仓储禁止修改模型覆盖。 */
    @Override
    public void setModelOverride(String sessionId, String modelOverride) {
        throw readOnly();
    }

    /** 跨 Profile 仓储禁止修改服务层级覆盖。 */
    @Override
    public void setServiceTierOverride(String sessionId, String serviceTierOverride) {
        throw readOnly();
    }

    /** 跨 Profile 仓储禁止修改推理强度覆盖。 */
    @Override
    public void setReasoningEffortOverride(String sessionId, String reasoningEffortOverride) {
        throw readOnly();
    }

    /** 跨 Profile 仓储禁止切换会话 Agent。 */
    @Override
    public void setActiveAgentName(String sessionId, String agentName) {
        throw readOnly();
    }

    /** 跨 Profile 仓储禁止清理会话 Agent。 */
    @Override
    public void clearActiveAgentName(String agentName) {
        throw readOnly();
    }

    /** 跨 Profile 仓储禁止写入目标状态。 */
    @Override
    public void setGoalState(String sessionId, String goalStateJson) {
        throw readOnly();
    }

    /** 跨 Profile 仓储禁止写入学习时间。 */
    @Override
    public void setLastLearningAt(String sessionId, long lastLearningAt) {
        throw readOnly();
    }

    /** 将查询结果追加到会话列表。 */
    private void appendRows(List<SessionRecord> results, PreparedStatement statement)
            throws Exception {
        ResultSet resultSet = statement.executeQuery();
        try {
            while (resultSet.next()) {
                results.add(map(resultSet));
            }
        } finally {
            resultSet.close();
        }
    }

    /** 将稳定列映射成会话记录。 */
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

    /** 转义 LIKE 模式字符，确保模型输入只能作为文本检索词。 */
    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 构造统一只读拒绝异常。 */
    private UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("跨 Profile 会话仓储只允许只读访问");
    }
}
