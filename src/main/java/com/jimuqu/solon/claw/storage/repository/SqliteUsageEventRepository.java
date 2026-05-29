package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.usage.UsageEventRecord;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** SQLite usage event repository. */
@RequiredArgsConstructor
public class SqliteUsageEventRepository implements UsageEventRepository {
    private final SqliteDatabase database;

    @Override
    public boolean insertIfAbsent(UsageEventRecord record) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or ignore into usage_events (event_id, session_id, run_id, source_key, provider, model, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens, total_tokens, cost_micros, currency, price_source, pricing_available, unpriced_input_tokens, unpriced_output_tokens, unpriced_cache_read_tokens, unpriced_cache_write_tokens, unpriced_reasoning_tokens, priced_at, created_at, backfill_approximate) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            bind(statement, record);
            int updated = statement.executeUpdate();
            statement.close();
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public UsageEventRecord findByEventId(String eventId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("select * from usage_events where event_id = ?");
            statement.setString(1, eventId);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? map(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    @Override
    public List<UsageEventRecord> listRecent(int limit) throws Exception {
        return listBetween(0L, Long.MAX_VALUE, limit);
    }

    @Override
    public List<UsageEventRecord> listBetween(long fromInclusive, long toInclusive, int limit)
            throws Exception {
        List<UsageEventRecord> records = new ArrayList<UsageEventRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from usage_events where created_at >= ? and created_at <= ? order by created_at desc limit ?");
            statement.setLong(1, Math.max(0L, fromInclusive));
            statement.setLong(2, toInclusive <= 0 ? Long.MAX_VALUE : toInclusive);
            statement.setInt(3, Math.max(1, Math.min(limit <= 0 ? 1000 : limit, 10000)));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    records.add(map(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return records;
    }

    private void bind(PreparedStatement statement, UsageEventRecord record) throws Exception {
        statement.setString(1, record.getEventId());
        statement.setString(2, record.getSessionId());
        statement.setString(3, record.getRunId());
        statement.setString(4, record.getSourceKey());
        statement.setString(5, record.getProvider());
        statement.setString(6, record.getModel());
        statement.setLong(7, record.getInputTokens());
        statement.setLong(8, record.getOutputTokens());
        statement.setLong(9, record.getCacheReadTokens());
        statement.setLong(10, record.getCacheWriteTokens());
        statement.setLong(11, record.getReasoningTokens());
        statement.setLong(12, record.getTotalTokens());
        statement.setLong(13, record.getCostMicros());
        statement.setString(14, record.getCurrency());
        statement.setString(15, record.getPriceSource());
        statement.setInt(16, record.isPricingAvailable() ? 1 : 0);
        statement.setLong(17, record.getUnpricedInputTokens());
        statement.setLong(18, record.getUnpricedOutputTokens());
        statement.setLong(19, record.getUnpricedCacheReadTokens());
        statement.setLong(20, record.getUnpricedCacheWriteTokens());
        statement.setLong(21, record.getUnpricedReasoningTokens());
        statement.setLong(22, record.getPricedAt());
        statement.setLong(23, record.getCreatedAt());
        statement.setInt(24, record.isBackfillApproximate() ? 1 : 0);
    }

    private UsageEventRecord map(ResultSet rs) throws Exception {
        UsageEventRecord record = new UsageEventRecord();
        record.setEventId(rs.getString("event_id"));
        record.setSessionId(rs.getString("session_id"));
        record.setRunId(rs.getString("run_id"));
        record.setSourceKey(rs.getString("source_key"));
        record.setProvider(rs.getString("provider"));
        record.setModel(rs.getString("model"));
        record.setInputTokens(rs.getLong("input_tokens"));
        record.setOutputTokens(rs.getLong("output_tokens"));
        record.setCacheReadTokens(rs.getLong("cache_read_tokens"));
        record.setCacheWriteTokens(rs.getLong("cache_write_tokens"));
        record.setReasoningTokens(rs.getLong("reasoning_tokens"));
        record.setTotalTokens(rs.getLong("total_tokens"));
        record.setCostMicros(rs.getLong("cost_micros"));
        record.setCurrency(rs.getString("currency"));
        record.setPriceSource(rs.getString("price_source"));
        record.setPricingAvailable(rs.getInt("pricing_available") != 0);
        record.setUnpricedInputTokens(rs.getLong("unpriced_input_tokens"));
        record.setUnpricedOutputTokens(rs.getLong("unpriced_output_tokens"));
        record.setUnpricedCacheReadTokens(rs.getLong("unpriced_cache_read_tokens"));
        record.setUnpricedCacheWriteTokens(rs.getLong("unpriced_cache_write_tokens"));
        record.setUnpricedReasoningTokens(rs.getLong("unpriced_reasoning_tokens"));
        record.setPricedAt(rs.getLong("priced_at"));
        record.setCreatedAt(rs.getLong("created_at"));
        record.setBackfillApproximate(rs.getInt("backfill_approximate") != 0);
        return record;
    }
}
