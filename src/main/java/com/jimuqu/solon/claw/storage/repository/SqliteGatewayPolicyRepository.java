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
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** SqliteGatewayPolicyRepository 实现。 */
@RequiredArgsConstructor
public class SqliteGatewayPolicyRepository implements GatewayPolicyRepository {
    /** 记录SQLite消息网关策略中的数据库。 */
    private final SqliteDatabase database;

    /**
     * 读取主渠道渠道。
     *
     * @param platform 平台参数。
     * @return 返回读取到的主渠道渠道。
     */
    public HomeChannelRecord getHomeChannel(PlatformType platform) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select platform, chat_id, thread_id, chat_name, is_primary, updated_at from home_channels where platform = ?");
            statement.setString(1, key(platform));
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? mapHome(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 列出主渠道Channels。
     *
     * @return 返回主渠道Channels列表。
     */
    public List<HomeChannelRecord> listHomeChannels() throws Exception {
        List<HomeChannelRecord> records = new ArrayList<HomeChannelRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select platform, chat_id, thread_id, chat_name, is_primary, updated_at from home_channels order by is_primary desc, platform asc");
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    records.add(mapHome(resultSet));
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

    /** {@inheritDoc} */
    @Override
    public HomeChannelRecord getPrimaryHomeChannel() throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select platform, chat_id, thread_id, chat_name, is_primary, updated_at "
                                    + "from home_channels order by is_primary desc, updated_at desc, platform asc limit 1");
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? mapHome(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 保存主渠道渠道。
     *
     * @param record 记录参数。
     */
    public void saveHomeChannel(HomeChannelRecord record) throws Exception {
        Connection connection = database.openConnection();
        try {
            connection.setAutoCommit(false);
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
            statement.setString(1, key(record.getPlatform()));
            statement.setString(2, record.getChatId());
            statement.setString(3, record.getThreadId());
            statement.setString(4, record.getChatName());
            statement.setInt(5, primary ? 1 : 0);
            statement.setLong(6, record.getUpdatedAt());
            statement.executeUpdate();
            statement.close();
            connection.commit();
            record.setPrimary(primary);
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
            connection.close();
        }
    }

    /** {@inheritDoc} */
    @Override
    public HomeChannelRecord setPrimaryHomeChannel(PlatformType platform) throws Exception {
        Connection connection = database.openConnection();
        try {
            connection.setAutoCommit(false);
            clearPrimaryHomeChannel(connection);
            PreparedStatement update =
                    connection.prepareStatement(
                            "update home_channels set is_primary = 1 where platform = ?");
            update.setString(1, key(platform));
            int affected = update.executeUpdate();
            update.close();
            if (affected != 1) {
                connection.rollback();
                throw new IllegalArgumentException("目标平台尚未绑定 home channel。");
            }
            PreparedStatement select =
                    connection.prepareStatement(
                            "select platform, chat_id, thread_id, chat_name, is_primary, updated_at from home_channels where platform = ?");
            select.setString(1, key(platform));
            ResultSet resultSet = select.executeQuery();
            try {
                if (!resultSet.next()) {
                    throw new IllegalStateException("主要通知渠道写入后无法读取。");
                }
                HomeChannelRecord record = mapHome(resultSet);
                connection.commit();
                return record;
            } finally {
                resultSet.close();
                select.close();
            }
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
            connection.close();
        }
    }

    /**
     * 读取平台管理员。
     *
     * @param platform 平台参数。
     * @return 返回读取到的平台管理员。
     */
    public PlatformAdminRecord getPlatformAdmin(PlatformType platform) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select platform, user_id, user_name, chat_id, created_at from platform_admins where platform = ?");
            statement.setString(1, key(platform));
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? mapAdmin(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 创建平台管理员If Absent。
     *
     * @param record 记录参数。
     * @return 返回创建好的平台管理员If Absent。
     */
    public boolean createPlatformAdminIfAbsent(PlatformAdminRecord record) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or ignore into platform_admins (platform, user_id, user_name, chat_id, created_at) values (?, ?, ?, ?, ?)");
            statement.setString(1, key(record.getPlatform()));
            statement.setString(2, record.getUserId());
            statement.setString(3, record.getUserName());
            statement.setString(4, record.getChatId());
            statement.setLong(5, record.getCreatedAt());
            int affected = statement.executeUpdate();
            statement.close();
            return affected > 0;
        } finally {
            connection.close();
        }
    }

    /** 在同一事务中首次绑定管理员、默认私聊并消费 pairing 请求。 */
    @Override
    public boolean claimPlatformAdminIfAbsent(
            PairingRequestRecord request, PlatformAdminRecord admin, HomeChannelRecord home)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            connection.setAutoCommit(false);
            PreparedStatement insertAdmin =
                    connection.prepareStatement(
                            "insert or ignore into platform_admins (platform, user_id, user_name, chat_id, created_at) values (?, ?, ?, ?, ?)");
            insertAdmin.setString(1, key(admin.getPlatform()));
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
            saveHome.setString(1, key(home.getPlatform()));
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
            consumeRequest.setString(1, key(request.getPlatform()));
            consumeRequest.setString(2, request.getCode());
            consumeRequest.setString(3, request.getUserId());
            int consumed = consumeRequest.executeUpdate();
            consumeRequest.close();
            if (consumed != 1) {
                throw new IllegalStateException("pairing 请求已被消费，请刷新后重试。");
            }
            connection.commit();
            return true;
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
            connection.close();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void savePlatformAdmin(PlatformAdminRecord record) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into platform_admins (platform, user_id, user_name, chat_id, created_at) values (?, ?, ?, ?, ?)");
            statement.setString(1, key(record.getPlatform()));
            statement.setString(2, record.getUserId());
            statement.setString(3, record.getUserName());
            statement.setString(4, record.getChatId());
            statement.setLong(5, record.getCreatedAt());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deletePlatformAdmin(PlatformType platform) throws Exception {
        Connection connection = database.openConnection();
        try {
            connection.setAutoCommit(false);
            boolean primary = isPrimary(connection, platform);
            PreparedStatement deleteAdmin =
                    connection.prepareStatement("delete from platform_admins where platform = ?");
            deleteAdmin.setString(1, key(platform));
            deleteAdmin.executeUpdate();
            deleteAdmin.close();
            PreparedStatement deleteHome =
                    connection.prepareStatement("delete from home_channels where platform = ?");
            deleteHome.setString(1, key(platform));
            deleteHome.executeUpdate();
            deleteHome.close();
            if (primary) {
                promotePrimaryHomeChannel(connection);
            }
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
     * 读取Approved用户。
     *
     * @param platform 平台参数。
     * @param userId 用户标识。
     * @return 返回读取到的Approved用户。
     */
    public ApprovedUserRecord getApprovedUser(PlatformType platform, String userId)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select platform, user_id, user_name, approved_at, approved_by from approved_users where platform = ? and user_id = ?");
            statement.setString(1, key(platform));
            statement.setString(2, userId);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? mapApproved(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 保存Approved用户。
     *
     * @param record 记录参数。
     */
    public void saveApprovedUser(ApprovedUserRecord record) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into approved_users (platform, user_id, user_name, approved_at, approved_by) values (?, ?, ?, ?, ?)");
            statement.setString(1, key(record.getPlatform()));
            statement.setString(2, record.getUserId());
            statement.setString(3, record.getUserName());
            statement.setLong(4, record.getApprovedAt());
            statement.setString(5, record.getApprovedBy());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 执行revokeApproved用户相关逻辑。
     *
     * @param platform 平台参数。
     * @param userId 用户标识。
     */
    public void revokeApprovedUser(PlatformType platform, String userId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "delete from approved_users where platform = ? and user_id = ?");
            statement.setString(1, key(platform));
            statement.setString(2, userId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 列出Approved Users。
     *
     * @param platform 平台参数。
     * @return 返回Approved Users列表。
     */
    public List<ApprovedUserRecord> listApprovedUsers(PlatformType platform) throws Exception {
        List<ApprovedUserRecord> list = new ArrayList<ApprovedUserRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select platform, user_id, user_name, approved_at, approved_by from approved_users where platform = ? order by approved_at asc");
            statement.setString(1, key(platform));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    list.add(mapApproved(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return list;
    }

    /**
     * 执行次数ApprovedUsers相关逻辑。
     *
     * @param platform 平台参数。
     * @return 返回次数Approved Users结果。
     */
    public int countApprovedUsers(PlatformType platform) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select count(*) from approved_users where platform = ?");
            statement.setString(1, key(platform));
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
     * 读取配对请求。
     *
     * @param platform 平台参数。
     * @param code code 参数。
     * @return 返回读取到的配对请求。
     */
    public PairingRequestRecord getPairingRequest(PlatformType platform, String code)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select platform, code, user_id, user_name, chat_id, created_at, expires_at from pairing_requests where platform = ? order by created_at asc");
            statement.setString(1, key(platform));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    PairingRequestRecord record = mapPairing(resultSet);
                    if (PairingCodeHash.matches(code, record.getCode())) {
                        return record;
                    }
                }
                return null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 读取Latest用户配对请求。
     *
     * @param platform 平台参数。
     * @param userId 用户标识。
     * @return 返回读取到的Latest用户配对请求。
     */
    public PairingRequestRecord getLatestUserPairingRequest(PlatformType platform, String userId)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select platform, code, user_id, user_name, chat_id, created_at, expires_at from pairing_requests where platform = ? and user_id = ? order by created_at desc limit 1");
            statement.setString(1, key(platform));
            statement.setString(2, userId);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? mapPairing(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 保存配对请求。
     *
     * @param record 记录参数。
     */
    public void savePairingRequest(PairingRequestRecord record) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into pairing_requests (platform, code, user_id, user_name, chat_id, created_at, expires_at) values (?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, key(record.getPlatform()));
            statement.setString(
                    2,
                    PairingCodeHash.isHash(record.getCode())
                            ? record.getCode()
                            : PairingCodeHash.hash(record.getCode()));
            statement.setString(3, record.getUserId());
            statement.setString(4, record.getUserName());
            statement.setString(5, record.getChatId());
            statement.setLong(6, record.getCreatedAt());
            statement.setLong(7, record.getExpiresAt());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 在同一写事务中清理过期请求、检查平台容量并保存新请求，防止多实例并发突破上限。
     *
     * @param record 待保存的 pairing 请求。
     * @param nowEpochMillis 当前时间，用于排除已过期请求。
     * @param maxPending 单平台最大待处理请求数。
     * @return 容量允许且保存成功时返回 true；已达到上限时返回 false。
     */
    @Override
    public boolean trySavePairingRequest(
            PairingRequestRecord record, long nowEpochMillis, int maxPending) throws Exception {
        String hashedCode =
                PairingCodeHash.isHash(record.getCode())
                        ? record.getCode()
                        : PairingCodeHash.hash(record.getCode());
        Connection connection = database.openConnection();
        try {
            connection.setAutoCommit(false);
            PreparedStatement deleteExpired =
                    connection.prepareStatement(
                            "delete from pairing_requests where platform = ? and expires_at < ?");
            deleteExpired.setString(1, key(record.getPlatform()));
            deleteExpired.setLong(2, nowEpochMillis);
            deleteExpired.executeUpdate();
            deleteExpired.close();

            PreparedStatement count =
                    connection.prepareStatement(
                            "select count(*) from pairing_requests where platform = ?");
            count.setString(1, key(record.getPlatform()));
            ResultSet countResult = count.executeQuery();
            int pending;
            try {
                pending = countResult.next() ? countResult.getInt(1) : 0;
            } finally {
                countResult.close();
                count.close();
            }
            if (pending >= maxPending) {
                connection.commit();
                return false;
            }

            PreparedStatement deleteExisting =
                    connection.prepareStatement(
                            "delete from pairing_requests where platform = ? and user_id = ?");
            deleteExisting.setString(1, key(record.getPlatform()));
            deleteExisting.setString(2, record.getUserId());
            deleteExisting.executeUpdate();
            deleteExisting.close();

            PreparedStatement insert =
                    connection.prepareStatement(
                            "insert into pairing_requests (platform, code, user_id, user_name, chat_id, created_at, expires_at) values (?, ?, ?, ?, ?, ?, ?)");
            insert.setString(1, key(record.getPlatform()));
            insert.setString(2, hashedCode);
            insert.setString(3, record.getUserId());
            insert.setString(4, record.getUserName());
            insert.setString(5, record.getChatId());
            insert.setLong(6, record.getCreatedAt());
            insert.setLong(7, record.getExpiresAt());
            insert.executeUpdate();
            insert.close();
            connection.commit();
            return true;
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
            connection.close();
        }
    }

    /**
     * 删除配对请求。
     *
     * @param platform 平台参数。
     * @param code code 参数。
     */
    public void deletePairingRequest(PlatformType platform, String code) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "delete from pairing_requests where platform = ? and code = ?");
            statement.setString(1, key(platform));
            statement.setString(2, code);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /** 删除平台下全部待处理配对请求。 */
    public void deletePendingPairingRequests(PlatformType platform) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("delete from pairing_requests where platform = ?");
            statement.setString(1, key(platform));
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 删除Expired配对Requests。
     *
     * @param platform 平台参数。
     * @param nowEpochMillis nowEpochMillis 参数。
     */
    public void deleteExpiredPairingRequests(PlatformType platform, long nowEpochMillis)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "delete from pairing_requests where platform = ? and expires_at < ?");
            statement.setString(1, key(platform));
            statement.setLong(2, nowEpochMillis);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 列出配对Requests。
     *
     * @param platform 平台参数。
     * @return 返回配对Requests列表。
     */
    public List<PairingRequestRecord> listPairingRequests(PlatformType platform) throws Exception {
        List<PairingRequestRecord> list = new ArrayList<PairingRequestRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select platform, code, user_id, user_name, chat_id, created_at, expires_at from pairing_requests where platform = ? order by created_at asc");
            statement.setString(1, key(platform));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    list.add(mapPairing(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return list;
    }

    /**
     * 读取配对频率限制。
     *
     * @param platform 平台参数。
     * @param userId 用户标识。
     * @return 返回读取到的配对频率限制。
     */
    public PairingRateLimitRecord getPairingRateLimit(PlatformType platform, String userId)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select platform, user_id, requested_at, failed_attempts, lockout_until from pairing_rate_limits where platform = ? and user_id = ?");
            statement.setString(1, key(platform));
            statement.setString(2, userId);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? mapRateLimit(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 保存配对频率限制。
     *
     * @param record 记录参数。
     */
    public void savePairingRateLimit(PairingRateLimitRecord record) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into pairing_rate_limits (platform, user_id, requested_at, failed_attempts, lockout_until) values (?, ?, ?, ?, ?)");
            statement.setString(1, key(record.getPlatform()));
            statement.setString(2, record.getUserId());
            statement.setLong(3, record.getRequestedAt());
            statement.setInt(4, record.getFailedAttempts());
            statement.setLong(5, record.getLockoutUntil());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 使用单条 SQLite UPSERT 原子累计平台级 pairing 审批失败次数并设置锁定期。
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
            throws Exception {
        Connection connection = database.openConnection();
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
            statement.setString(1, key(platform));
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
     * 仅在当前平台未出现审批开始后的新锁时清除失败状态，防止正确 code 覆盖并发安全锁。
     *
     * @param platform pairing 所属平台。
     * @param userId 平台级失败状态内部键。
     * @param approvalStartedAt 本次正确 code 审批开始时间。
     * @return 未锁定并完成清理时返回 true；存在并发新锁时返回 false。
     */
    @Override
    public boolean clearPairingApprovalFailureIfUnlocked(
            PlatformType platform, String userId, long approvalStartedAt) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into pairing_rate_limits (platform, user_id, requested_at, failed_attempts, lockout_until) "
                                    + "values (?, ?, 0, 0, 0) "
                                    + "on conflict(platform, user_id) do update set "
                                    + "requested_at = 0, failed_attempts = 0, lockout_until = 0 "
                                    + "where pairing_rate_limits.lockout_until <= ? "
                                    + "returning platform");
            statement.setString(1, key(platform));
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
     * 执行键相关逻辑。
     *
     * @param platform 平台参数。
     * @return 返回键结果。
     */
    private String key(PlatformType platform) {
        return platform == null ? "UNKNOWN" : platform.name();
    }

    /**
     * 执行map主渠道相关逻辑。
     *
     * @param resultSet 结果Set响应或执行结果。
     * @return 返回map主渠道结果。
     */
    private HomeChannelRecord mapHome(ResultSet resultSet) throws Exception {
        HomeChannelRecord record = new HomeChannelRecord();
        record.setPlatform(PlatformType.fromName(resultSet.getString("platform")));
        record.setChatId(resultSet.getString("chat_id"));
        record.setThreadId(resultSet.getString("thread_id"));
        record.setChatName(resultSet.getString("chat_name"));
        record.setPrimary(resultSet.getInt("is_primary") == 1);
        record.setUpdatedAt(resultSet.getLong("updated_at"));
        return record;
    }

    /** 判断事务中是否已经存在主要通知渠道。 */
    private boolean hasPrimaryHomeChannel(Connection connection) throws Exception {
        PreparedStatement statement =
                connection.prepareStatement(
                        "select 1 from home_channels where is_primary = 1 limit 1");
        ResultSet resultSet = statement.executeQuery();
        try {
            return resultSet.next();
        } finally {
            resultSet.close();
            statement.close();
        }
    }

    /** 判断指定平台当前是否为主要通知渠道。 */
    private boolean isPrimary(Connection connection, PlatformType platform) throws Exception {
        PreparedStatement statement =
                connection.prepareStatement(
                        "select is_primary from home_channels where platform = ?");
        statement.setString(1, key(platform));
        ResultSet resultSet = statement.executeQuery();
        try {
            return resultSet.next() && resultSet.getInt("is_primary") == 1;
        } finally {
            resultSet.close();
            statement.close();
        }
    }

    /** 清除旧主渠道标记，供同一事务内切换主要通知渠道。 */
    private void clearPrimaryHomeChannel(Connection connection) throws Exception {
        PreparedStatement statement =
                connection.prepareStatement("update home_channels set is_primary = 0");
        statement.executeUpdate();
        statement.close();
    }

    /** 提升仍有平台主人的最近通知渠道，避免主人解绑后继续投递给旧私聊。 */
    private void promotePrimaryHomeChannel(Connection connection) throws Exception {
        PreparedStatement statement =
                connection.prepareStatement(
                        "update home_channels set is_primary = 1 where platform = ("
                                + "select home.platform from home_channels home "
                                + "join platform_admins admin on admin.platform = home.platform "
                                + "order by home.updated_at desc, home.platform asc limit 1)");
        statement.executeUpdate();
        statement.close();
    }

    /**
     * 执行mapApproved相关逻辑。
     *
     * @param resultSet 结果Set响应或执行结果。
     * @return 返回map Approved结果。
     */
    private ApprovedUserRecord mapApproved(ResultSet resultSet) throws Exception {
        ApprovedUserRecord record = new ApprovedUserRecord();
        record.setPlatform(PlatformType.fromName(resultSet.getString("platform")));
        record.setUserId(resultSet.getString("user_id"));
        record.setUserName(resultSet.getString("user_name"));
        record.setApprovedAt(resultSet.getLong("approved_at"));
        record.setApprovedBy(resultSet.getString("approved_by"));
        return record;
    }

    /**
     * 执行map配对相关逻辑。
     *
     * @param resultSet 结果Set响应或执行结果。
     * @return 返回map配对结果。
     */
    private PairingRequestRecord mapPairing(ResultSet resultSet) throws Exception {
        PairingRequestRecord record = new PairingRequestRecord();
        record.setPlatform(PlatformType.fromName(resultSet.getString("platform")));
        record.setCode(resultSet.getString("code"));
        record.setUserId(resultSet.getString("user_id"));
        record.setUserName(resultSet.getString("user_name"));
        record.setChatId(resultSet.getString("chat_id"));
        record.setCreatedAt(resultSet.getLong("created_at"));
        record.setExpiresAt(resultSet.getLong("expires_at"));
        return record;
    }

    /**
     * 执行map管理员相关逻辑。
     *
     * @param resultSet 结果Set响应或执行结果。
     * @return 返回map管理员结果。
     */
    private PlatformAdminRecord mapAdmin(ResultSet resultSet) throws Exception {
        PlatformAdminRecord record = new PlatformAdminRecord();
        record.setPlatform(PlatformType.fromName(resultSet.getString("platform")));
        record.setUserId(resultSet.getString("user_id"));
        record.setUserName(resultSet.getString("user_name"));
        record.setChatId(resultSet.getString("chat_id"));
        record.setCreatedAt(resultSet.getLong("created_at"));
        return record;
    }

    /**
     * 执行map频率限制相关逻辑。
     *
     * @param resultSet 结果Set响应或执行结果。
     * @return 返回map频率限制结果。
     */
    private PairingRateLimitRecord mapRateLimit(ResultSet resultSet) throws Exception {
        PairingRateLimitRecord record = new PairingRateLimitRecord();
        record.setPlatform(PlatformType.fromName(resultSet.getString("platform")));
        record.setUserId(resultSet.getString("user_id"));
        record.setRequestedAt(resultSet.getLong("requested_at"));
        record.setFailedAttempts(resultSet.getInt("failed_attempts"));
        record.setLockoutUntil(resultSet.getLong("lockout_until"));
        return record;
    }
}
