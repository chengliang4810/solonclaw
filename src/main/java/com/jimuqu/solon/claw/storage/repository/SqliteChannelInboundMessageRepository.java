package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.core.model.ChannelInboundMessageRecord;
import com.jimuqu.solon.claw.core.repository.ChannelInboundMessageRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** SQLite 渠道入站消息总账仓储实现。 */
public class SqliteChannelInboundMessageRepository implements ChannelInboundMessageRepository {
    /** SQLite 数据库。 */
    private final SqliteDatabase database;

    /**
     * 创建 SQLite 入站消息仓储。
     *
     * @param database SQLite 数据库。
     */
    public SqliteChannelInboundMessageRepository(SqliteDatabase database) {
        this.database = database;
    }

    /** 按唯一消息键插入记录。 */
    @Override
    public boolean saveIfAbsent(ChannelInboundMessageRecord record) throws Exception {
        String sql =
                "insert or ignore into channel_inbound_messages (ingress_id, message_key, profile,"
                        + " platform, source_key, message_json, status, attempts, reply_json,"
                        + " last_error, created_at, updated_at, processed_at, completed_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.getIngressId());
            statement.setString(2, record.getMessageKey());
            statement.setString(3, record.getProfile());
            statement.setString(4, record.getPlatform());
            statement.setString(5, record.getSourceKey());
            statement.setString(6, record.getMessageJson());
            statement.setString(7, record.getStatus());
            statement.setInt(8, record.getAttempts());
            statement.setString(9, record.getReplyJson());
            statement.setString(10, record.getLastError());
            statement.setLong(11, record.getCreatedAt());
            statement.setLong(12, record.getUpdatedAt());
            statement.setLong(13, record.getProcessedAt());
            statement.setLong(14, record.getCompletedAt());
            return statement.executeUpdate() > 0;
        }
    }

    /** 原子消费原始 pending receipt，并由 leader 承担合并后的逻辑处理。 */
    @Override
    public boolean startBatch(
            String leaderIngressId,
            List<String> memberIngressIds,
            String sourceKey,
            String messageJson,
            long startedAt)
            throws Exception {
        List<String> normalized = normalizeBatchMembers(leaderIngressId, memberIngressIds);
        if (normalized.isEmpty()) {
            return false;
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String ingressId : normalized) {
                    if (!isPending(connection, ingressId)) {
                        connection.rollback();
                        return false;
                    }
                }
                if (!startLeader(connection, leaderIngressId, sourceKey, messageJson, startedAt)) {
                    connection.rollback();
                    return false;
                }
                for (String ingressId : normalized) {
                    if (leaderIngressId.equals(ingressId)) {
                        continue;
                    }
                    if (!completeMergedMember(connection, ingressId, leaderIngressId, startedAt)) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** 按幂等键读取记录。 */
    @Override
    public ChannelInboundMessageRecord findByMessageKey(String messageKey) throws Exception {
        String sql =
                "select rowid as inbound_sequence, * from channel_inbound_messages"
                        + " where message_key = ?";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, messageKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? map(resultSet) : null;
            }
        }
    }

    /** 保存业务回复。 */
    @Override
    public boolean markProcessed(String ingressId, String replyJson, long processedAt)
            throws Exception {
        return update(
                        "update channel_inbound_messages set status = ?, reply_json = ?, last_error = null,"
                                + " recovery_token = null, recovery_claimed_at = null,"
                                + " processed_at = ?, updated_at = ?"
                                + " where ingress_id = ? and status = ?",
                        ChannelInboundMessageRecord.STATUS_PROCESSED,
                        replyJson,
                        Long.valueOf(processedAt),
                        Long.valueOf(processedAt),
                        ingressId,
                        ChannelInboundMessageRecord.STATUS_PROCESSING)
                > 0;
    }

    /** 保存业务回复并把正常投递 owner 与回复原子绑定。 */
    @Override
    public boolean markProcessedForDelivery(
            String ingressId, String replyJson, String deliveryToken, long processedAt)
            throws Exception {
        return update(
                        "update channel_inbound_messages set status = ?, reply_json = ?, last_error = null,"
                                + " recovery_token = ?, recovery_claimed_at = ?,"
                                + " processed_at = ?, updated_at = ?"
                                + " where ingress_id = ? and status = ?",
                        ChannelInboundMessageRecord.STATUS_PROCESSED,
                        replyJson,
                        deliveryToken,
                        Long.valueOf(processedAt),
                        Long.valueOf(processedAt),
                        Long.valueOf(processedAt),
                        ingressId,
                        ChannelInboundMessageRecord.STATUS_PROCESSING)
                == 1;
    }

    /** 标记渠道投递完成。 */
    @Override
    public boolean markCompleted(String ingressId, long completedAt) throws Exception {
        return update(
                        "update channel_inbound_messages set status = ?, last_error = null,"
                                + " recovery_token = null, recovery_claimed_at = null,"
                                + " completed_at = ?, updated_at = ?"
                                + " where ingress_id = ? and status = ?",
                        ChannelInboundMessageRecord.STATUS_COMPLETED,
                        Long.valueOf(completedAt),
                        Long.valueOf(completedAt),
                        ingressId,
                        ChannelInboundMessageRecord.STATUS_PROCESSED)
                > 0;
    }

    /** 记录可重试的投递失败。 */
    @Override
    public void markDeliveryFailed(String ingressId, String error, long updatedAt)
            throws Exception {
        update(
                "update channel_inbound_messages set status = ?, last_error = ?, recovery_token = null,"
                        + " recovery_claimed_at = null, updated_at = ?"
                        + " where ingress_id = ? and status = ?",
                ChannelInboundMessageRecord.STATUS_PROCESSED,
                error,
                Long.valueOf(updatedAt),
                ingressId,
                ChannelInboundMessageRecord.STATUS_PROCESSED);
    }

    /** 标记不能安全自动重放的失败。 */
    @Override
    public void markFailed(String ingressId, String error, long updatedAt) throws Exception {
        update(
                "update channel_inbound_messages set status = ?, last_error = ?, recovery_token = null,"
                        + " recovery_claimed_at = null, updated_at = ?"
                        + " where ingress_id = ? and status <> ?",
                ChannelInboundMessageRecord.STATUS_FAILED,
                error,
                Long.valueOf(updatedAt),
                ingressId,
                ChannelInboundMessageRecord.STATUS_COMPLETED);
    }

    /** 收敛进程启动前遗留的未完成处理。 */
    @Override
    public int markInterrupted(String profile, long before, String error) throws Exception {
        String sql =
                "update channel_inbound_messages set status = ?, last_error = ?, updated_at = ?"
                        + " where profile = ? and status = ? and updated_at <= ?";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ChannelInboundMessageRecord.STATUS_FAILED);
            statement.setString(2, error);
            statement.setLong(3, before);
            statement.setString(4, profile);
            statement.setString(5, ChannelInboundMessageRecord.STATUS_PROCESSING);
            statement.setLong(6, before);
            return statement.executeUpdate();
        }
    }

    /** 捕获当前 Profile 已落库消息的 SQLite rowid 高水位。 */
    @Override
    public long capturePendingWatermark(String profile) throws Exception {
        String sql =
                "select coalesce(max(rowid), 0) from channel_inbound_messages where profile = ?";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    /** 从稳定游标之后列出连接前高水位内遗留的 pending receipt。 */
    @Override
    public List<ChannelInboundMessageRecord> listPending(
            String profile, long maxSequence, long afterSequence, int limit) throws Exception {
        return listPendingInternal(profile, null, maxSequence, afterSequence, limit);
    }

    /** 从稳定游标之后列出指定平台在连接前高水位内遗留的 pending receipt。 */
    @Override
    public List<ChannelInboundMessageRecord> listPending(
            String profile, String platform, long maxSequence, long afterSequence, int limit)
            throws Exception {
        return listPendingInternal(profile, platform, maxSequence, afterSequence, limit);
    }

    /** 按可选平台条件执行 pending receipt 的稳定 rowid 分页查询。 */
    private List<ChannelInboundMessageRecord> listPendingInternal(
            String profile, String platform, long maxSequence, long afterSequence, int limit)
            throws Exception {
        boolean platformFiltered = platform != null && platform.trim().length() > 0;
        String sql =
                "select rowid as inbound_sequence, * from channel_inbound_messages"
                        + " where profile = ?"
                        + (platformFiltered ? " and platform = ?" : "")
                        + " and status = ? and rowid <= ? and rowid > ?"
                        + " order by rowid asc limit ?";
        List<ChannelInboundMessageRecord> records = new ArrayList<ChannelInboundMessageRecord>();
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, profile);
            if (platformFiltered) {
                statement.setString(index++, platform);
            }
            statement.setString(index++, ChannelInboundMessageRecord.STATUS_PENDING);
            statement.setLong(index++, maxSequence);
            statement.setLong(index++, afterSequence);
            statement.setInt(index, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(map(resultSet));
                }
            }
        }
        return records;
    }

    /** 在渠道启动前用唯一令牌认领当时已有的全部 processed 回复。 */
    @Override
    public int claimStartupDeliveries(String profile, String recoveryToken) throws Exception {
        long claimedAt = System.currentTimeMillis();
        return update(
                "update channel_inbound_messages set recovery_token = ?, recovery_claimed_at = ?"
                        + " where profile = ? and status = ? and recovery_token is null",
                recoveryToken,
                Long.valueOf(claimedAt),
                profile,
                ChannelInboundMessageRecord.STATUS_PROCESSED);
    }

    /** 在平台可用后认领该平台全部尚未完成且未被占用的 processed 回复。 */
    @Override
    public int claimPlatformDeliveries(String profile, String platform, String recoveryToken)
            throws Exception {
        long claimedAt = System.currentTimeMillis();
        return update(
                "update channel_inbound_messages set recovery_token = ?, recovery_claimed_at = ?"
                        + " where profile = ? and platform = ? and status = ?"
                        + " and recovery_token is null",
                recoveryToken,
                Long.valueOf(claimedAt),
                profile,
                platform,
                ChannelInboundMessageRecord.STATUS_PROCESSED);
    }

    /** 在平台连接成功后认领尚未被恢复的投递失败回复。 */
    @Override
    public int claimFailedDeliveries(String profile, String platform, String recoveryToken)
            throws Exception {
        long claimedAt = System.currentTimeMillis();
        return update(
                "update channel_inbound_messages set recovery_token = ?, recovery_claimed_at = ?"
                        + " where profile = ? and platform = ? and status = ?"
                        + " and last_error is not null and recovery_token is null",
                recoveryToken,
                Long.valueOf(claimedAt),
                profile,
                platform,
                ChannelInboundMessageRecord.STATUS_PROCESSED);
    }

    /** 启动时 fail-closed 收敛外发结果未知记录，并释放尚未开始外发的旧认领。 */
    @Override
    public int convergeInterruptedDeliveries(String profile, long interruptedBefore, String error)
            throws Exception {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                int uncertain =
                        update(
                                connection,
                                "update channel_inbound_messages set status = ?, last_error = ?,"
                                        + " recovery_token = null, recovery_claimed_at = null,"
                                        + " updated_at = ? where profile = ? and status = ?"
                                        + " and updated_at <= ?",
                                ChannelInboundMessageRecord.STATUS_FAILED,
                                error,
                                Long.valueOf(interruptedBefore),
                                profile,
                                ChannelInboundMessageRecord.STATUS_DELIVERING,
                                Long.valueOf(interruptedBefore));
                int safeClaims =
                        update(
                                connection,
                                "update channel_inbound_messages set recovery_token = null,"
                                        + " recovery_claimed_at = null where profile = ? and status = ?"
                                        + " and recovery_token is not null"
                                        + " and (recovery_claimed_at is null or recovery_claimed_at <= ?)",
                                profile,
                                ChannelInboundMessageRecord.STATUS_PROCESSED,
                                Long.valueOf(interruptedBefore));
                connection.commit();
                return uncertain + safeClaims;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** 仅由当前 owner 在真实外发前把记录推进为 delivering。 */
    @Override
    public boolean markClaimedDeliveryStarted(
            String ingressId, String deliveryToken, long startedAt) throws Exception {
        return update(
                        "update channel_inbound_messages set status = ?, updated_at = ?"
                                + " where ingress_id = ? and status = ? and recovery_token = ?",
                        ChannelInboundMessageRecord.STATUS_DELIVERING,
                        Long.valueOf(startedAt),
                        ingressId,
                        ChannelInboundMessageRecord.STATUS_PROCESSED,
                        deliveryToken)
                == 1;
    }

    /** 仅由当前 owner 释放尚未开始外发的单条认领。 */
    @Override
    public boolean releaseClaimedDelivery(String ingressId, String deliveryToken) throws Exception {
        return update(
                        "update channel_inbound_messages set recovery_token = null,"
                                + " recovery_claimed_at = null where ingress_id = ? and status = ?"
                                + " and recovery_token = ?",
                        ingressId,
                        ChannelInboundMessageRecord.STATUS_PROCESSED,
                        deliveryToken)
                == 1;
    }

    /** 仅由当前恢复 owner 把已投递回复收敛为 completed。 */
    @Override
    public boolean markClaimedCompleted(String ingressId, String recoveryToken, long completedAt)
            throws Exception {
        return update(
                        "update channel_inbound_messages set status = ?, recovery_token = null,"
                                + " recovery_claimed_at = null, completed_at = ?, updated_at = ?"
                                + " where ingress_id = ? and status = ? and recovery_token = ?",
                        ChannelInboundMessageRecord.STATUS_COMPLETED,
                        Long.valueOf(completedAt),
                        Long.valueOf(completedAt),
                        ingressId,
                        ChannelInboundMessageRecord.STATUS_DELIVERING,
                        recoveryToken)
                == 1;
    }

    /** 仅由当前恢复 owner 把不可重放记录收敛为 failed。 */
    @Override
    public boolean markClaimedFailed(
            String ingressId, String recoveryToken, String error, long updatedAt) throws Exception {
        return update(
                        "update channel_inbound_messages set status = ?, last_error = ?,"
                                + " recovery_token = null, recovery_claimed_at = null, updated_at = ?"
                                + " where ingress_id = ? and status in (?, ?)"
                                + " and recovery_token = ?",
                        ChannelInboundMessageRecord.STATUS_FAILED,
                        error,
                        Long.valueOf(updatedAt),
                        ingressId,
                        ChannelInboundMessageRecord.STATUS_PROCESSED,
                        ChannelInboundMessageRecord.STATUS_DELIVERING,
                        recoveryToken)
                == 1;
    }

    /** 按恢复批次令牌稳定分页列出已认领回复。 */
    @Override
    public List<ChannelInboundMessageRecord> listClaimedDeliveries(
            String profile,
            String recoveryToken,
            long afterProcessedAt,
            String afterIngressId,
            int limit)
            throws Exception {
        String sql =
                "select rowid as inbound_sequence, * from channel_inbound_messages"
                        + " where profile = ? and status = ?"
                        + " and recovery_token = ?"
                        + " and (processed_at > ? or (processed_at = ? and ingress_id > ?))"
                        + " order by processed_at asc, ingress_id asc limit ?";
        List<ChannelInboundMessageRecord> records = new ArrayList<ChannelInboundMessageRecord>();
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile);
            statement.setString(2, ChannelInboundMessageRecord.STATUS_PROCESSED);
            statement.setString(3, recoveryToken);
            statement.setLong(4, afterProcessedAt);
            statement.setLong(5, afterProcessedAt);
            statement.setString(6, afterIngressId == null ? "" : afterIngressId);
            statement.setInt(7, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(map(resultSet));
                }
            }
        }
        return records;
    }

    /** 释放指定批次仍停留在 processed 的恢复认领。 */
    @Override
    public void releaseDeliveryClaim(String profile, String recoveryToken) throws Exception {
        update(
                "update channel_inbound_messages set recovery_token = null,"
                        + " recovery_claimed_at = null"
                        + " where profile = ? and status = ? and recovery_token = ?",
                profile,
                ChannelInboundMessageRecord.STATUS_PROCESSED,
                recoveryToken);
    }

    /** 列出可安全恢复投递的已处理回复。 */
    @Override
    public List<ChannelInboundMessageRecord> listProcessed(String profile, int limit)
            throws Exception {
        return listProcessed(profile, -1L, "", limit);
    }

    /** 从稳定游标之后列出可安全恢复投递的已处理回复。 */
    @Override
    public List<ChannelInboundMessageRecord> listProcessed(
            String profile, long afterProcessedAt, String afterIngressId, int limit)
            throws Exception {
        String sql =
                "select rowid as inbound_sequence, * from channel_inbound_messages"
                        + " where profile = ? and status = ?"
                        + " and (processed_at > ? or (processed_at = ? and ingress_id > ?))"
                        + " order by processed_at asc, ingress_id asc limit ?";
        List<ChannelInboundMessageRecord> records = new ArrayList<ChannelInboundMessageRecord>();
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile);
            statement.setString(2, ChannelInboundMessageRecord.STATUS_PROCESSED);
            statement.setLong(3, afterProcessedAt);
            statement.setLong(4, afterProcessedAt);
            statement.setString(5, afterIngressId == null ? "" : afterIngressId);
            statement.setInt(6, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(map(resultSet));
                }
            }
        }
        return records;
    }

    /** 仅按 Profile 和更新时间删除 completed/failed 终态，保留全部可处理或可恢复状态。 */
    @Override
    public int pruneTerminal(String profile, long updatedBefore) throws Exception {
        return update(
                "delete from channel_inbound_messages where profile = ? and updated_at < ?"
                        + " and status in (?, ?)",
                profile,
                Long.valueOf(updatedBefore),
                ChannelInboundMessageRecord.STATUS_COMPLETED,
                ChannelInboundMessageRecord.STATUS_FAILED);
    }

    /** 执行参数数量较少的状态更新。 */
    private int update(String sql, Object... values) throws Exception {
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, values);
            return statement.executeUpdate();
        }
    }

    /** 在调用方事务连接内执行状态更新。 */
    private int update(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, values);
            return statement.executeUpdate();
        }
    }

    /** 按 Java 类型绑定通用 SQL 参数。 */
    private void bindValues(PreparedStatement statement, Object... values) throws Exception {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            if (value instanceof Number) {
                statement.setLong(index + 1, ((Number) value).longValue());
            } else {
                statement.setString(index + 1, value == null ? null : String.valueOf(value));
            }
        }
    }

    /** 校验批次成员非空、无重复，并确认 leader 包含在成员集合中。 */
    private List<String> normalizeBatchMembers(
            String leaderIngressId, List<String> memberIngressIds) {
        List<String> normalized = new ArrayList<String>();
        if (isBlank(leaderIngressId) || memberIngressIds == null || memberIngressIds.isEmpty()) {
            return normalized;
        }
        Set<String> seen = new HashSet<String>();
        for (String ingressId : memberIngressIds) {
            if (isBlank(ingressId) || !seen.add(ingressId)) {
                normalized.clear();
                return normalized;
            }
            normalized.add(ingressId);
        }
        if (!seen.contains(leaderIngressId)) {
            normalized.clear();
        }
        return normalized;
    }

    /** 判断指定入站记录是否仍可被批次领取。 */
    private boolean isPending(Connection connection, String ingressId) throws Exception {
        String sql = "select status from channel_inbound_messages where ingress_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ingressId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        && ChannelInboundMessageRecord.STATUS_PENDING.equals(
                                resultSet.getString("status"));
            }
        }
    }

    /** 把批次 leader 原子推进到 processing 并保存合并后的消息。 */
    private boolean startLeader(
            Connection connection,
            String ingressId,
            String sourceKey,
            String messageJson,
            long startedAt)
            throws Exception {
        String sql =
                "update channel_inbound_messages set status = ?, source_key = ?, message_json = ?,"
                        + " attempts = attempts + 1, last_error = null, updated_at = ?"
                        + " where ingress_id = ? and status = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ChannelInboundMessageRecord.STATUS_PROCESSING);
            statement.setString(2, sourceKey);
            statement.setString(3, messageJson);
            statement.setLong(4, startedAt);
            statement.setString(5, ingressId);
            statement.setString(6, ChannelInboundMessageRecord.STATUS_PENDING);
            return statement.executeUpdate() == 1;
        }
    }

    /** 把由 leader 合并处理的其余原始 receipt 收敛为完成态。 */
    private boolean completeMergedMember(
            Connection connection, String ingressId, String leaderIngressId, long completedAt)
            throws Exception {
        String sql =
                "update channel_inbound_messages set status = ?, last_error = ?, completed_at = ?,"
                        + " updated_at = ? where ingress_id = ? and status = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ChannelInboundMessageRecord.STATUS_COMPLETED);
            statement.setString(2, "已合并到入站 " + leaderIngressId + " 处理。");
            statement.setLong(3, completedAt);
            statement.setLong(4, completedAt);
            statement.setString(5, ingressId);
            statement.setString(6, ChannelInboundMessageRecord.STATUS_PENDING);
            return statement.executeUpdate() == 1;
        }
    }

    /** 判断字符串是否为空白。 */
    private boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

    /** 把当前结果行转换为入站消息记录。 */
    private ChannelInboundMessageRecord map(ResultSet resultSet) throws Exception {
        ChannelInboundMessageRecord record = new ChannelInboundMessageRecord();
        record.setIngressId(resultSet.getString("ingress_id"));
        record.setSequence(resultSet.getLong("inbound_sequence"));
        record.setMessageKey(resultSet.getString("message_key"));
        record.setProfile(resultSet.getString("profile"));
        record.setPlatform(resultSet.getString("platform"));
        record.setSourceKey(resultSet.getString("source_key"));
        record.setMessageJson(resultSet.getString("message_json"));
        record.setStatus(resultSet.getString("status"));
        record.setAttempts(resultSet.getInt("attempts"));
        record.setReplyJson(resultSet.getString("reply_json"));
        record.setLastError(resultSet.getString("last_error"));
        record.setCreatedAt(resultSet.getLong("created_at"));
        record.setUpdatedAt(resultSet.getLong("updated_at"));
        record.setProcessedAt(resultSet.getLong("processed_at"));
        record.setCompletedAt(resultSet.getLong("completed_at"));
        return record;
    }
}
