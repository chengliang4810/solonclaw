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
    /** 管理员CLAIMCODE的统一常量值。 */
    public static final String ADMIN_CLAIM_CODE = "__ADMIN_CLAIM__";

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
                            "select platform, chat_id, thread_id, chat_name, updated_at from home_channels where platform = ?");
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
                            "select platform, chat_id, thread_id, chat_name, updated_at from home_channels order by platform asc");
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

    /**
     * 保存主渠道渠道。
     *
     * @param record 记录参数。
     */
    public void saveHomeChannel(HomeChannelRecord record) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into home_channels (platform, chat_id, thread_id, chat_name, updated_at) values (?, ?, ?, ?, ?)");
            statement.setString(1, key(record.getPlatform()));
            statement.setString(2, record.getChatId());
            statement.setString(3, record.getThreadId());
            statement.setString(4, record.getChatName());
            statement.setLong(5, record.getUpdatedAt());
            statement.executeUpdate();
            statement.close();
        } finally {
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
            PreparedStatement statement =
                    connection.prepareStatement("delete from platform_admins where platform = ?");
            statement.setString(1, key(platform));
            statement.executeUpdate();
            statement.close();
        } finally {
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
     * 读取管理员Claim请求。
     *
     * @param platform 平台参数。
     * @return 返回读取到的管理员Claim请求。
     */
    public PairingRequestRecord getAdminClaimRequest(PlatformType platform) throws Exception {
        return getPairingRequest(platform, ADMIN_CLAIM_CODE);
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
                            "select platform, code, user_id, user_name, chat_id, created_at, expires_at from pairing_requests where platform = ? and user_id = ? and code <> ? order by created_at desc limit 1");
            statement.setString(1, key(platform));
            statement.setString(2, userId);
            statement.setString(3, ADMIN_CLAIM_CODE);
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
     * 创建管理员Claim请求If Absent。
     *
     * @param record 记录参数。
     * @return 返回创建好的管理员Claim请求If Absent。
     */
    public boolean createAdminClaimRequestIfAbsent(PairingRequestRecord record) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or ignore into pairing_requests (platform, code, user_id, user_name, chat_id, created_at, expires_at) values (?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, key(record.getPlatform()));
            statement.setString(2, ADMIN_CLAIM_CODE);
            statement.setString(3, record.getUserId());
            statement.setString(4, record.getUserName());
            statement.setString(5, record.getChatId());
            statement.setLong(6, record.getCreatedAt());
            statement.setLong(7, record.getExpiresAt());
            try {
                return statement.executeUpdate() > 0;
            } finally {
                statement.close();
            }
        } finally {
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

    /**
     * 删除待处理配对请求，保留管理员认领请求。
     *
     * @param platform 平台参数。
     */
    public void deletePendingPairingRequests(PlatformType platform) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "delete from pairing_requests where platform = ? and code <> ?");
            statement.setString(1, key(platform));
            statement.setString(2, ADMIN_CLAIM_CODE);
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
     * @param includeAdminClaim includeAdminClaim 参数。
     * @return 返回配对Requests列表。
     */
    public List<PairingRequestRecord> listPairingRequests(
            PlatformType platform, boolean includeAdminClaim) throws Exception {
        List<PairingRequestRecord> list = new ArrayList<PairingRequestRecord>();
        Connection connection = database.openConnection();
        try {
            String sql =
                    includeAdminClaim
                            ? "select platform, code, user_id, user_name, chat_id, created_at, expires_at from pairing_requests where platform = ? order by created_at asc"
                            : "select platform, code, user_id, user_name, chat_id, created_at, expires_at from pairing_requests where platform = ? and code <> ? order by created_at asc";
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, key(platform));
            if (!includeAdminClaim) {
                statement.setString(2, ADMIN_CLAIM_CODE);
            }
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
        record.setUpdatedAt(resultSet.getLong("updated_at"));
        return record;
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
