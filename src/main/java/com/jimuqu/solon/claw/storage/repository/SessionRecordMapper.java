package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import java.sql.ResultSet;

/** 会话记录结果集映射工具，供多个会话仓储共享同一份列映射逻辑。 */
final class SessionRecordMapper {

    private SessionRecordMapper() {
        // 工具类，禁止实例化。
    }

    /** 将结果集当前行映射为 SessionRecord。 */
    static SessionRecord map(ResultSet resultSet) throws Exception {
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
        record.setPersistedConcurrentSettings(concurrentSettings(record));
        return record;
    }

    /** 提取需要防止旧快照覆盖的会话设置。 */
    private static Object[] concurrentSettings(SessionRecord record) {
        return new Object[] {
            record.getModelOverride(),
            record.getServiceTierOverride(),
            record.getReasoningEffortOverride(),
            record.getActiveAgentName(),
            record.getGoalStateJson(),
            Long.valueOf(record.getLastLearningAt())
        };
    }
}
