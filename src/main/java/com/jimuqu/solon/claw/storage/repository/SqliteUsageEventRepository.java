package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.usage.UsageEventRecord;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** 负责SQLite用量事件数据的持久化读写，隔离底层存储实现。 */
@RequiredArgsConstructor
public class SqliteUsageEventRepository implements UsageEventRepository {
    /** 记录SQLite用量事件中的数据库。 */
    private final SqliteDatabase database;

    /**
     * 写入IfAbsent。
     *
     * @param record 记录参数。
     * @return 返回insert If Absent结果。
     */
    @Override
    public boolean insertIfAbsent(UsageEventRecord record) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or ignore into usage_events (event_id, session_id, run_id, source_key, provider, model, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens, total_tokens, request_count, cost_micros, currency, price_source, price_source_url, pricing_version, price_fetched_at, raw_usage_json, pricing_available, unpriced_input_tokens, unpriced_output_tokens, unpriced_cache_read_tokens, unpriced_cache_write_tokens, unpriced_reasoning_tokens, priced_at, created_at, backfill_approximate) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            bind(statement, record);
            int updated = statement.executeUpdate();
            statement.close();
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    /**
     * 根据事件标识查找对应数据。
     *
     * @param eventId 事件标识。
     * @return 返回按事件标识查找得到的结果。
     */
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

    /**
     * 列出Recent。
     *
     * @param limit 最大返回数量。
     * @return 返回Recent列表。
     */
    @Override
    public List<UsageEventRecord> listRecent(int limit) throws Exception {
        return listBetween(0L, Long.MAX_VALUE, limit);
    }

    /**
     * 列出Between。
     *
     * @param fromInclusive fromInclusive 参数。
     * @param toInclusive toInclusive 参数。
     * @param limit 最大返回数量。
     * @return 返回Between列表。
     */
    @Override
    public List<UsageEventRecord> listBetween(long fromInclusive, long toInclusive, int limit)
            throws Exception {
        return listBetweenInternal(fromInclusive, toInclusive, true, limit);
    }

    /**
     * 列出Between。
     *
     * @param fromInclusive fromInclusive 参数。
     * @param toInclusive toInclusive 参数。
     * @return 返回Between列表。
     */
    @Override
    public List<UsageEventRecord> listBetween(long fromInclusive, long toInclusive)
            throws Exception {
        return listBetweenInternal(fromInclusive, toInclusive, false, 0);
    }

    /**
     * 列出Between Internal。
     *
     * @param fromInclusive fromInclusive 参数。
     * @param toInclusive toInclusive 参数。
     * @param limited limited 参数。
     * @param limit 最大返回数量。
     * @return 返回Between Internal列表。
     */
    private List<UsageEventRecord> listBetweenInternal(
            long fromInclusive, long toInclusive, boolean limited, int limit) throws Exception {
        List<UsageEventRecord> records = new ArrayList<UsageEventRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            limited
                                    ? "select * from usage_events where created_at >= ? and created_at <= ? order by created_at desc limit ?"
                                    : "select * from usage_events where created_at >= ? and created_at <= ? order by created_at desc");
            statement.setLong(1, Math.max(0L, fromInclusive));
            statement.setLong(2, toInclusive <= 0 ? Long.MAX_VALUE : toInclusive);
            if (limited) {
                statement.setInt(3, Math.max(1, Math.min(limit <= 0 ? 1000 : limit, 10000)));
            }
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

    /**
     * 执行bind相关逻辑。
     *
     * @param statement statement 参数。
     * @param record 记录参数。
     */
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
        statement.setLong(13, record.getRequestCount());
        statement.setLong(14, record.getCostMicros());
        statement.setString(15, record.getCurrency());
        statement.setString(16, record.getPriceSource());
        statement.setString(17, record.getPriceSourceUrl());
        statement.setString(18, record.getPricingVersion());
        statement.setLong(19, record.getPriceFetchedAt());
        statement.setString(20, record.getRawUsageJson());
        statement.setInt(21, record.isPricingAvailable() ? 1 : 0);
        statement.setLong(22, record.getUnpricedInputTokens());
        statement.setLong(23, record.getUnpricedOutputTokens());
        statement.setLong(24, record.getUnpricedCacheReadTokens());
        statement.setLong(25, record.getUnpricedCacheWriteTokens());
        statement.setLong(26, record.getUnpricedReasoningTokens());
        statement.setLong(27, record.getPricedAt());
        statement.setLong(28, record.getCreatedAt());
        statement.setInt(29, record.isBackfillApproximate() ? 1 : 0);
    }

    /**
     * 执行map相关逻辑。
     *
     * @param rs rs 参数。
     * @return 返回map结果。
     */
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
        record.setRequestCount(rs.getLong("request_count"));
        record.setCostMicros(rs.getLong("cost_micros"));
        record.setCurrency(rs.getString("currency"));
        record.setPriceSource(rs.getString("price_source"));
        record.setPriceSourceUrl(rs.getString("price_source_url"));
        record.setPricingVersion(rs.getString("pricing_version"));
        record.setPriceFetchedAt(rs.getLong("price_fetched_at"));
        record.setRawUsageJson(rs.getString("raw_usage_json"));
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
