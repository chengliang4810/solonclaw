package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ApprovedUserRecord;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.PairingRateLimitRecord;
import com.jimuqu.solon.claw.core.model.PairingRequestRecord;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** SqliteGatewayPolicyRepository 实现。 */
@RequiredArgsConstructor
public class SqliteGatewayPolicyRepository extends SqliteRepositorySupport
        implements GatewayPolicyRepository {
    /** 记录SQLite消息网关策略中的数据库。 */
    private final SqliteDatabase database;

    @Override
    protected Connection getConnection() throws SQLException {
        return database.openConnection();
    }

    /**
     * 读取主渠道渠道。
     *
     * @param platform 平台参数。
     * @return 返回读取到的主渠道渠道。
     */
    public HomeChannelRecord getHomeChannel(PlatformType platform) throws SQLException {
        return queryOne(
                "select home.platform, home.chat_id, home.thread_id, home.chat_name, home.is_primary, home.updated_at "
                        + "from home_channels home join platform_admins admin on admin.platform = home.platform "
                        + "where home.platform = ?",
                stmt -> stmt.setString(1, platformKey(platform)),
                this::mapHome);
    }

    /**
     * 列出主渠道Channels。
     *
     * @return 返回主渠道Channels列表。
     */
    public List<HomeChannelRecord> listHomeChannels() throws SQLException {
        return queryList(
                "select home.platform, home.chat_id, home.thread_id, home.chat_name, home.is_primary, home.updated_at "
                        + "from home_channels home join platform_admins admin on admin.platform = home.platform "
                        + "order by home.is_primary desc, home.platform asc",
                null,
                this::mapHome);
    }

    /** {@inheritDoc} */
    @Override
    public HomeChannelRecord getPrimaryHomeChannel() throws SQLException {
        return queryOne(
                "select home.platform, home.chat_id, home.thread_id, home.chat_name, home.is_primary, home.updated_at "
                        + "from home_channels home join platform_admins admin on admin.platform = home.platform "
                        + "where home.is_primary = 1 order by home.updated_at desc, home.platform asc limit 1",
                null,
                this::mapHome);
    }

    /**
     * 保存主渠道渠道。
     *
     * @param record 记录参数。
     */
    public void saveHomeChannel(HomeChannelRecord record) throws SQLException {
        inTransaction(
                connection -> {
                    boolean primary =
                            record.isPrimary()
                                    || isPrimary(connection, record.getPlatform())
                                    || !hasPrimaryHomeChannel(connection);
                    if (record.isPrimary()) {
                        clearPrimaryHomeChannel(connection);
                    }
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "insert or replace into home_channels (platform, chat_id, thread_id, chat_name, is_primary, updated_at) values (?, ?, ?, ?, ?, ?)");
                    statement.setString(1, platformKey(record.getPlatform()));
                    statement.setString(2, record.getChatId());
                    statement.setString(3, record.getThreadId());
                    statement.setString(4, record.getChatName());
                    statement.setInt(5, primary ? 1 : 0);
                    statement.setLong(6, record.getUpdatedAt());
                    statement.executeUpdate();
                    statement.close();
                    record.setPrimary(primary);
                    return null;
                });
    }

    /** {@inheritDoc} */
    @Override
    public HomeChannelRecord setPrimaryHomeChannel(PlatformType platform) throws SQLException {
        return inTransaction(
                connection -> {
                    clearPrimaryHomeChannel(connection);
                    PreparedStatement update =
                            connection.prepareStatement(
                                    "update home_channels set is_primary = 1 where platform = ?");
                    update.setString(1, platformKey(platform));
                    int affected = update.executeUpdate();
                    update.close();
                    if (affected != 1) {
                        throw new IllegalArgumentException("目标平台尚未绑定 home channel。");
                    }
                    PreparedStatement select =
                            connection.prepareStatement(
                                    "select platform, chat_id, thread_id, chat_name, is_primary, updated_at from home_channels where platform = ?");
                    select.setString(1, platformKey(platform));
                    ResultSet resultSet = select.executeQuery();
                    try {
                        if (!resultSet.next()) {
                            throw new IllegalStateException("主要通知渠道写入后无法读取。");
                        }
                        return mapHome(resultSet);
                    } finally {
                        resultSet.close();
                        select.close();
                    }
                });
    }

    /**
     * 读取平台管理员。
     *
     * @param platform 平台参数。
     * @return 返回读取到的平台管理员。
     */
    public PlatformAdminRecord getPlatformAdmin(PlatformType platform) throws SQLException {
        return queryOne(
                "select platform, user_id, user_name, chat_id, created_at from platform_admins where platform = ?",
                stmt -> stmt.setString(1, platformKey(platform)),
                this::mapAdmin);
    }

    /**
     * 创建平台管理员If Absent。
     *
     * @param record 记录参数。
     * @return 返回创建好的平台管理员If Absent。
     */
    public boolean createPlatformAdminIfAbsent(PlatformAdminRecord record) throws SQLException {
        int affected =
                executeUpdate(
                        "insert or ignore into platform_admins (platform, user_id, user_name, chat_id, created_at) values (?, ?, ?, ?, ?)",
                        stmt -> {
                            stmt.setString(1, platformKey(record.getPlatform()));
                            stmt.setString(2, record.getUserId());
                            stmt.setString(3, record.getUserName());
                            stmt.setString(4, record.getChatId());
                            stmt.setLong(5, record.getCreatedAt());
                        });
        return affected > 0;
    }

    /** 在同一事务中首次绑定管理员、默认私聊并消费 pairing 请求。 */
    @Override
    public boolean claimPlatformAdminIfAbsent(
            PairingRequestRecord request, PlatformAdminRecord admin, HomeChannelRecord home)
            throws SQLException {
        return inTransaction(
                connection -> {
                    PreparedStatement insertAdmin =
                            connection.prepareStatement(
                                    "insert or ignore into platform_admins (platform, user_id, user_name, chat_id, created_at) values (?, ?, ?, ?, ?)");
                    insertAdmin.setString(1, platformKey(admin.getPlatform()));
                    insertAdmin.setString(2, admin.getUserId());
                    insertAdmin.setString(3, admin.getUserName());
                    insertAdmin.setString(4, admin.getChatId());
                    insertAdmin.setLong(5, admin.getCreatedAt());
                    int adminInserted = insertAdmin.executeUpdate();
                    insertAdmin.close();
                    if (adminInserted == 0) {
                        connection.rollback();
                        return false;
                    }

                    PreparedStatement saveHome =
                            connection.prepareStatement(
                                    "insert or replace into home_channels (platform, chat_id, thread_id, chat_name, is_primary, updated_at) values (?, ?, ?, ?, ?, ?)");
                    boolean primary = !hasPrimaryHomeChannel(connection);
                    saveHome.setString(1, platformKey(home.getPlatform()));
                    saveHome.setString(2, home.getChatId());
                    saveHome.setString(3, home.getThreadId());
                    saveHome.setString(4, home.getChatName());
                    saveHome.setInt(5, primary ? 1 : 0);
                    saveHome.setLong(6, home.getUpdatedAt());
                    saveHome.executeUpdate();
                    saveHome.close();
                    home.setPrimary(primary);

                    PreparedStatement consumeRequest =
                            connection.prepareStatement(
                                    "delete from pairing_requests where platform = ? and code = ? and user_id = ?");
                    consumeRequest.setString(1, platformKey(request.getPlatform()));
                    consumeRequest.setString(2, request.getCode());
                    consumeRequest.setString(3, request.getUserId());
                    int consumed = consumeRequest.executeUpdate();
                    consumeRequest.close();
                    if (consumed != 1) {
                        throw new IllegalStateException("pairing 请求已被消费，请刷新后重试。");
                    }
                    return true;
                });
    }

    /** {@inheritDoc} */
    @Override
    public void savePlatformAdmin(PlatformAdminRecord record) throws SQLException {
        executeUpdate(
                "insert or replace into platform_admins (platform, user_id, user_name, chat_id, created_at) values (?, ?, ?, ?, ?)",
                stmt -> {
                    stmt.setString(1, platformKey(record.getPlatform()));
                    stmt.setString(2, record.getUserId());
                    stmt.setString(3, record.getUserName());
                    stmt.setString(4, record.getChatId());
                    stmt.setLong(5, record.getCreatedAt());
                });
    }

    /** {@inheritDoc} */
    @Override
    public void deletePlatformAdmin(PlatformType platform) throws SQLException {
        inTransaction(
                connection -> {
                    PreparedStatement deleteAdmin =
                            connection.prepareStatement(
                                    "delete from platform_admins where platform = ?");
                    deleteAdmin.setString(1, platformKey(platform));
                    deleteAdmin.executeUpdate();
                    deleteAdmin.close();
                    PreparedStatement deleteHome =
                            connection.prepareStatement(
                                    "delete from home_channels where platform = ?");
                    deleteHome.setString(1, platformKey(platform));
                    deleteHome.executeUpdate();
                    deleteHome.close();
                    return null;
                });
    }

    /**
     * 读取Approved用户。
     *
     * @param platform 平台参数。
     * @param userId 用户标识。
     * @return 返回读取到的Approved用户。
     */
    public ApprovedUserRecord getApprovedUser(PlatformType platform, String userId)
            throws SQLException {
        return queryOne(
                "select platform, user_id, user_name, approved_at, approved_by from approved_users where platform = ? and user_id = ?",
                stmt -> {
                    stmt.setString(1, platformKey(platform));
                    stmt.setString(2, userId);
                },
                this::mapApproved);
    }

    /**
     * 保存Approved用户。
     *
     * @param record 记录参数。
     */
    public void saveApprovedUser(ApprovedUserRecord record) throws SQLException {
        executeUpdate(
                "insert or replace into approved_users (platform, user_id, user_name, approved_at, approved_by) values (?, ?, ?, ?, ?)",
                stmt -> {
                    stmt.setString(1, platformKey(record.getPlatform()));
                    stmt.setString(2, record.getUserId());
                    stmt.setString(3, record.getUserName());
                    stmt.setLong(4, record.getApprovedAt());
                    stmt.setString(5, record.getApprovedBy());
                });
    }

    /**
     * 执行revokeApproved用户相关逻辑。
     *
     * @param platform 平台参数。
     * @param userId 用户标识。
     */
    public void revokeApprovedUser(PlatformType platform, String userId) throws SQLException {
        executeUpdate(
                "delete from approved_users where platform = ? and user_id = ?",
                stmt -> {
                    stmt.setString(1, platformKey(platform));
                    stmt.setString(2, userId);
                });
    }

    /**
     * 列出Approved Users。
     *
     * @param platform 平台参数。
     * @return 返回Approved Users列表。
     */
    public List<ApprovedUserRecord> listApprovedUsers(PlatformType platform) throws SQLException {
        return queryList(
                "select platform, user_id, user_name, approved_at, approved_by from approved_users where platform = ? order by approved_at asc",
                stmt -> stmt.setString(1, platformKey(platform)),
                this::mapApproved);
    }

    /**
     * 执行次数ApprovedUsers相关逻辑。
     *
     * @param platform 平台参数。
     * @return 返回次数Approved Users结果。
     */
    public int countApprovedUsers(PlatformType platform) throws SQLException {
        return queryInt(
                "select count(*) from approved_users where platform = ?",
                stmt -> stmt.setString(1, platformKey(platform)));
    }

    /**
     * 读取配对请求。
     *
     * @param platform 平台参数。
     * @param code code 参数。
     * @return 返回读取到的配对请求。
     */
    public PairingRequestRecord getPairingRequest(PlatformType platform, String code)
            throws Exception {
        List<PairingRequestRecord> requests =
                queryList(
                        "select platform, code, user_id, user_name, chat_id, created_at, expires_at from pairing_requests where platform = ? order by created_at asc",
                        stmt -> stmt.setString(1, platformKey(platform)),
                        this::mapPairing);
        for (PairingRequestRecord record : requests) {
            if (PairingCodeHash.matches(code, record.getCode())) {
                return record;
            }
        }
        return null;
    }

    /**
     * 列出配对请求。
     *
     * @param platform 平台参数。
     * @return 返回配对请求列表。
     */
    @Override
    public List<PairingRequestRecord> listPairingRequests(PlatformType platform)
            throws SQLException {
        return queryList(
                "select platform, code, user_id, user_name, chat_id, created_at, expires_at from pairing_requests where platform = ? order by created_at desc",
                stmt -> stmt.setString(1, platformKey(platform)),
                this::mapPairing);
    }

    /**
     * 读取Latest用户配对请求。
     *
     * @param platform 平台参数。
     * @param userId 用户标识。
     * @return 返回读取到的Latest用户配对请求。
     */
    public PairingRequestRecord getLatestUserPairingRequest(PlatformType platform, String userId)
            throws SQLException {
        return queryOne(
                "select platform, code, user_id, user_name, chat_id, created_at, expires_at from pairing_requests where platform = ? and user_id = ? order by created_at desc limit 1",
                stmt -> {
                    stmt.setString(1, platformKey(platform));
                    stmt.setString(2, userId);
                },
                this::mapPairing);
    }

    /**
     * 保存配对请求。
     *
     * @param record 记录参数。
     */
    public void savePairingRequest(PairingRequestRecord record) throws SQLException {
        executeUpdate(
                "insert or replace into pairing_requests (platform, code, user_id, user_name, chat_id, created_at, expires_at) values (?, ?, ?, ?, ?, ?, ?)",
                stmt -> {
                    stmt.setString(1, platformKey(record.getPlatform()));
                    stmt.setString(
                            2,
                            PairingCodeHash.isHash(record.getCode())
                                    ? record.getCode()
                                    : PairingCodeHash.hash(record.getCode()));
                    stmt.setString(3, record.getUserId());
                    stmt.setString(4, record.getUserName());
                    stmt.setString(5, record.getChatId());
                    stmt.setLong(6, record.getCreatedAt());
                    stmt.setLong(7, record.getExpiresAt());
                });
    }

    /**
     * 原子保存Pairing Request：在同一事务中清理过期请求、检查平台容量并保存新请求。
     *
     * @param record 待保存的配对请求。
     * @param nowEpochMillis 当前时间，用于排除已过期请求。
     * @param maxPending 单平台最大待处理请求数。
     * @return 容量允许且保存成功时返回 true；已达到上限时返回 false。
     */
    @Override
    public boolean trySavePairingRequest(
            PairingRequestRecord record, long nowEpochMillis, int maxPending) throws SQLException {
        return inTransaction(
                connection -> {
                    /* 清理过期请求 */
                    PreparedStatement deleteExpired =
                            connection.prepareStatement(
                                    "delete from pairing_requests where platform = ? and expires_at < ?");
                    deleteExpired.setString(1, platformKey(record.getPlatform()));
                    deleteExpired.setLong(2, nowEpochMillis);
                    deleteExpired.executeUpdate();
                    deleteExpired.close();

                    /* 检查平台容量 */
                    PreparedStatement countStmt =
                            connection.prepareStatement(
                                    "select count(*) from pairing_requests where platform = ?");
                    countStmt.setString(1, platformKey(record.getPlatform()));
                    ResultSet countRs = countStmt.executeQuery();
                    int pending;
                    try {
                        pending = countRs.next() ? countRs.getInt(1) : 0;
                    } finally {
                        countRs.close();
                        countStmt.close();
                    }
                    if (pending >= maxPending) {
                        return false;
                    }

                    /* 同一用户始终只保留最新请求。 */
                    PreparedStatement deleteExisting =
                            connection.prepareStatement(
                                    "delete from pairing_requests where platform = ? and user_id = ?");
                    deleteExisting.setString(1, platformKey(record.getPlatform()));
                    deleteExisting.setString(2, record.getUserId());
                    deleteExisting.executeUpdate();
                    deleteExisting.close();

                    /* 保存新请求 */
                    PreparedStatement insert =
                            connection.prepareStatement(
                                    "insert into pairing_requests (platform, code, user_id, user_name, chat_id, created_at, expires_at) "
                                            + "values (?, ?, ?, ?, ?, ?, ?)");
                    insert.setString(1, platformKey(record.getPlatform()));
                    insert.setString(
                            2,
                            PairingCodeHash.isHash(record.getCode())
                                    ? record.getCode()
                                    : PairingCodeHash.hash(record.getCode()));
                    insert.setString(3, record.getUserId());
                    insert.setString(4, record.getUserName());
                    insert.setString(5, record.getChatId());
                    insert.setLong(6, record.getCreatedAt());
                    insert.setLong(7, record.getExpiresAt());
                    insert.executeUpdate();
                    insert.close();

                    return true;
                });
    }

    /**
     * 执行consumePairingRequest相关逻辑。
     *
     * @param platform 平台参数。
     * @param code code 参数。
     * @param userId 用户标识。
     */
    public void consumePairingRequest(PlatformType platform, String code, String userId)
            throws SQLException {
        executeUpdate(
                "delete from pairing_requests where platform = ? and code = ? and user_id = ?",
                stmt -> {
                    stmt.setString(1, platformKey(platform));
                    stmt.setString(2, code);
                    stmt.setString(3, userId);
                });
    }

    /**
     * 删除配对请求。
     *
     * @param platform 平台参数。
     * @param code code 参数。
     */
    @Override
    public void deletePairingRequest(PlatformType platform, String code) throws SQLException {
        executeUpdate(
                "delete from pairing_requests where platform = ? and code = ?",
                stmt -> {
                    stmt.setString(1, platformKey(platform));
                    stmt.setString(2, code);
                });
    }

    /**
     * 列出过期Pairing Requests。
     *
     * @param nowEpochMillis nowEpochMillis 参数。
     * @return 返回过期Pairing Requests列表。
     */
    public List<PairingRequestRecord> listExpiredPairingRequests(long nowEpochMillis)
            throws SQLException {
        return queryList(
                "select platform, code, user_id, user_name, chat_id, created_at, expires_at from pairing_requests where expires_at < ?",
                stmt -> stmt.setLong(1, nowEpochMillis),
                this::mapPairing);
    }

    /**
     * 删除过期Pairing Requests。
     *
     * @param platform 平台参数。
     * @param nowEpochMillis nowEpochMillis 参数。
     */
    @Override
    public void deleteExpiredPairingRequests(PlatformType platform, long nowEpochMillis)
            throws SQLException {
        executeUpdate(
                "delete from pairing_requests where platform = ? and expires_at < ?",
                stmt -> {
                    stmt.setString(1, platformKey(platform));
                    stmt.setLong(2, nowEpochMillis);
                });
    }

    /**
     * 执行cleanupExpiredPairingRequests相关逻辑。
     *
     * @param nowEpochMillis nowEpochMillis 参数。
     */
    public void cleanupExpiredPairingRequests(long nowEpochMillis) throws SQLException {
        executeUpdate(
                "delete from pairing_requests where expires_at < ?",
                stmt -> stmt.setLong(1, nowEpochMillis));
    }

    /**
     * 执行countPairingRequestsByPlatform相关逻辑。
     *
     * @param platform 平台参数。
     * @return 返回countPairingRequestsByPlatform结果。
     */
    public int countPairingRequestsByPlatform(PlatformType platform) throws SQLException {
        return queryInt(
                "select count(*) from pairing_requests where platform = ?",
                stmt -> stmt.setString(1, platformKey(platform)));
    }

    /**
     * 保存配对Rate Limit。
     *
     * @param record 记录参数。
     */
    public void savePairingRateLimit(PairingRateLimitRecord record) throws SQLException {
        executeUpdate(
                "insert or replace into pairing_rate_limits (platform, user_id, failed_attempts, requested_at, lockout_until) values (?, ?, ?, ?, ?)",
                stmt -> {
                    stmt.setString(1, platformKey(record.getPlatform()));
                    stmt.setString(2, record.getUserId());
                    stmt.setInt(3, record.getFailedAttempts());
                    stmt.setLong(4, record.getRequestedAt());
                    stmt.setLong(5, record.getLockoutUntil());
                });
    }

    /**
     * 使用单条SQLite UPSERT原子累计平台级pairing审批失败次数并设置锁定期。
     *
     * @return 更新后的平台级失败限制记录。
     */
    @Override
    public PairingRateLimitRecord recordPairingApprovalFailure(
            PlatformType platform,
            String userId,
            long nowEpochMillis,
            int maxAttempts,
            long lockoutMillis)
            throws SQLException {
        Connection connection = getConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into pairing_rate_limits (platform, user_id, requested_at, failed_attempts, lockout_until) "
                                    + "values (?, ?, ?, case when ? <= 1 then 0 else 1 end, case when ? <= 1 then ? + ? else 0 end) "
                                    + "on conflict(platform, user_id) do update set "
                                    + "requested_at = excluded.requested_at, "
                                    + "failed_attempts = case "
                                    + "when pairing_rate_limits.lockout_until > excluded.requested_at then pairing_rate_limits.failed_attempts "
                                    + "when pairing_rate_limits.failed_attempts + 1 >= ? then 0 "
                                    + "else pairing_rate_limits.failed_attempts + 1 end, "
                                    + "lockout_until = case "
                                    + "when pairing_rate_limits.lockout_until > excluded.requested_at then pairing_rate_limits.lockout_until "
                                    + "when pairing_rate_limits.failed_attempts + 1 >= ? then excluded.requested_at + ? "
                                    + "else 0 end "
                                    + "returning platform, user_id, requested_at, failed_attempts, lockout_until");
            statement.setString(1, platformKey(platform));
            statement.setString(2, userId);
            statement.setLong(3, nowEpochMillis);
            statement.setInt(4, maxAttempts);
            statement.setInt(5, maxAttempts);
            statement.setLong(6, nowEpochMillis);
            statement.setLong(7, lockoutMillis);
            statement.setInt(8, maxAttempts);
            statement.setInt(9, maxAttempts);
            statement.setLong(10, lockoutMillis);
            ResultSet resultSet = statement.executeQuery();
            try {
                if (!resultSet.next()) {
                    throw new IllegalStateException("pairing 审批失败计数未返回更新结果。");
                }
                return mapRateLimit(resultSet);
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 仅在当前平台未出现审批开始后的新锁时清除失败状态。
     *
     * @param platform pairing所属平台。
     * @param userId 平台级失败状态内部键。
     * @param approvalStartedAt 本次正确code审批开始时间。
     * @return 未锁定并完成清理时返回true；存在并发新锁时返回false。
     */
    @Override
    public boolean clearPairingApprovalFailureIfUnlocked(
            PlatformType platform, String userId, long approvalStartedAt) throws SQLException {
        Connection connection = getConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into pairing_rate_limits (platform, user_id, requested_at, failed_attempts, lockout_until) "
                                    + "values (?, ?, 0, 0, 0) "
                                    + "on conflict(platform, user_id) do update set "
                                    + "requested_at = 0, failed_attempts = 0, lockout_until = 0 "
                                    + "where pairing_rate_limits.lockout_until <= ? "
                                    + "returning platform");
            statement.setString(1, platformKey(platform));
            statement.setString(2, userId);
            statement.setLong(3, approvalStartedAt);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next();
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 读取配对Rate Limit。
     *
     * @param platform 平台参数。
     * @param userId 用户标识。
     * @return 返回读取到的配对Rate Limit。
     */
    public PairingRateLimitRecord getPairingRateLimit(PlatformType platform, String userId)
            throws SQLException {
        return queryOne(
                "select platform, user_id, failed_attempts, requested_at, lockout_until from pairing_rate_limits where platform = ? and user_id = ?",
                stmt -> {
                    stmt.setString(1, platformKey(platform));
                    stmt.setString(2, userId);
                },
                this::mapRateLimit);
    }

    // 私有辅助方法

    private HomeChannelRecord mapHome(ResultSet rs) throws SQLException {
        HomeChannelRecord record = new HomeChannelRecord();
        record.setPlatform(PlatformType.valueOf(rs.getString("platform")));
        record.setChatId(rs.getString("chat_id"));
        record.setThreadId(rs.getString("thread_id"));
        record.setChatName(rs.getString("chat_name"));
        record.setPrimary(rs.getInt("is_primary") != 0);
        record.setUpdatedAt(rs.getLong("updated_at"));
        return record;
    }

    private PlatformAdminRecord mapAdmin(ResultSet rs) throws SQLException {
        PlatformAdminRecord record = new PlatformAdminRecord();
        record.setPlatform(PlatformType.valueOf(rs.getString("platform")));
        record.setUserId(rs.getString("user_id"));
        record.setUserName(rs.getString("user_name"));
        record.setChatId(rs.getString("chat_id"));
        record.setCreatedAt(rs.getLong("created_at"));
        return record;
    }

    private ApprovedUserRecord mapApproved(ResultSet rs) throws SQLException {
        ApprovedUserRecord record = new ApprovedUserRecord();
        record.setPlatform(PlatformType.valueOf(rs.getString("platform")));
        record.setUserId(rs.getString("user_id"));
        record.setUserName(rs.getString("user_name"));
        record.setApprovedAt(rs.getLong("approved_at"));
        record.setApprovedBy(rs.getString("approved_by"));
        return record;
    }

    private PairingRequestRecord mapPairing(ResultSet rs) throws SQLException {
        PairingRequestRecord record = new PairingRequestRecord();
        record.setPlatform(PlatformType.valueOf(rs.getString("platform")));
        record.setCode(rs.getString("code"));
        record.setUserId(rs.getString("user_id"));
        record.setUserName(rs.getString("user_name"));
        record.setChatId(rs.getString("chat_id"));
        record.setCreatedAt(rs.getLong("created_at"));
        record.setExpiresAt(rs.getLong("expires_at"));
        return record;
    }

    private PairingRateLimitRecord mapRateLimit(ResultSet rs) throws SQLException {
        PairingRateLimitRecord record = new PairingRateLimitRecord();
        record.setPlatform(PlatformType.valueOf(rs.getString("platform")));
        record.setUserId(rs.getString("user_id"));
        record.setRequestedAt(rs.getLong("requested_at"));
        record.setFailedAttempts(rs.getInt("failed_attempts"));
        record.setLockoutUntil(rs.getLong("lockout_until"));
        return record;
    }

    private boolean isPrimary(Connection connection, PlatformType platform) throws SQLException {
        PreparedStatement statement =
                connection.prepareStatement(
                        "select count(*) from home_channels where platform = ? and is_primary = 1");
        statement.setString(1, platformKey(platform));
        ResultSet resultSet = statement.executeQuery();
        try {
            return resultSet.next() && resultSet.getInt(1) > 0;
        } finally {
            resultSet.close();
            statement.close();
        }
    }

    private boolean hasPrimaryHomeChannel(Connection connection) throws SQLException {
        PreparedStatement statement =
                connection.prepareStatement(
                        "select count(*) from home_channels where is_primary = 1");
        ResultSet resultSet = statement.executeQuery();
        try {
            return resultSet.next() && resultSet.getInt(1) > 0;
        } finally {
            resultSet.close();
            statement.close();
        }
    }

    private void clearPrimaryHomeChannel(Connection connection) throws SQLException {
        PreparedStatement statement =
                connection.prepareStatement("update home_channels set is_primary = 0");
        statement.executeUpdate();
        statement.close();
    }
}
