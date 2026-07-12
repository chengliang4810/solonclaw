package com.jimuqu.solon.claw.core.repository;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ApprovedUserRecord;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.PairingRateLimitRecord;
import com.jimuqu.solon.claw.core.model.PairingRequestRecord;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import java.util.List;

/** 网关授权与 pairing 相关状态仓储接口。 */
public interface GatewayPolicyRepository {
    /** 读取平台 home channel。 */
    HomeChannelRecord getHomeChannel(PlatformType platform) throws Exception;

    /** 列出全部 home channel。 */
    default List<HomeChannelRecord> listHomeChannels() throws Exception {
        return java.util.Collections.emptyList();
    }

    /** 保存平台 home channel。 */
    void saveHomeChannel(HomeChannelRecord record) throws Exception;

    /** 查询平台管理员。 */
    PlatformAdminRecord getPlatformAdmin(PlatformType platform) throws Exception;

    /** 在平台尚无管理员时创建管理员。 */
    boolean createPlatformAdminIfAbsent(PlatformAdminRecord record) throws Exception;

    /** 由可信本机或已认证管理面设置平台管理员。 */
    default void savePlatformAdmin(PlatformAdminRecord record) throws Exception {
        throw new UnsupportedOperationException("当前仓储不支持设置平台管理员。");
    }

    /** 由可信本机或已认证管理面清除平台管理员。 */
    default void deletePlatformAdmin(PlatformType platform) throws Exception {
        throw new UnsupportedOperationException("当前仓储不支持清除平台管理员。");
    }

    /** 查询已批准用户。 */
    ApprovedUserRecord getApprovedUser(PlatformType platform, String userId) throws Exception;

    /** 保存已批准用户。 */
    void saveApprovedUser(ApprovedUserRecord record) throws Exception;

    /** 撤销已批准用户。 */
    void revokeApprovedUser(PlatformType platform, String userId) throws Exception;

    /** 列出已批准用户。 */
    List<ApprovedUserRecord> listApprovedUsers(PlatformType platform) throws Exception;

    /** 统计已批准用户数量。 */
    int countApprovedUsers(PlatformType platform) throws Exception;

    /** 通过 code 查询 pairing 请求。 */
    PairingRequestRecord getPairingRequest(PlatformType platform, String code) throws Exception;

    /** 查询用户最近一次有效 pairing 请求。 */
    PairingRequestRecord getLatestUserPairingRequest(PlatformType platform, String userId)
            throws Exception;

    /** 保存 pairing 请求。 */
    void savePairingRequest(PairingRequestRecord record) throws Exception;

    /**
     * 在单平台待处理数量未达到上限时保存 pairing 请求。
     *
     * <p>持久化仓储应覆盖此默认实现，以数据库事务保证容量检查与写入不可被并发穿透。
     */
    default boolean trySavePairingRequest(
            PairingRequestRecord record, long nowEpochMillis, int maxPending) throws Exception {
        deleteExpiredPairingRequests(record.getPlatform(), nowEpochMillis);
        if (listPairingRequests(record.getPlatform()).size() >= maxPending) {
            return false;
        }
        PairingRequestRecord existing =
                getLatestUserPairingRequest(record.getPlatform(), record.getUserId());
        if (existing != null && existing.getExpiresAt() > nowEpochMillis) {
            deletePairingRequest(record.getPlatform(), existing.getCode());
        }
        savePairingRequest(record);
        return true;
    }

    /** 删除指定 pairing 请求。 */
    void deletePairingRequest(PlatformType platform, String code) throws Exception;

    /** 删除平台下全部待处理 pairing 请求。 */
    default void deletePendingPairingRequests(PlatformType platform) throws Exception {
        List<PairingRequestRecord> records = listPairingRequests(platform);
        for (PairingRequestRecord record : records) {
            deletePairingRequest(platform, record.getCode());
        }
    }

    /** 删除已过期 pairing 请求。 */
    void deleteExpiredPairingRequests(PlatformType platform, long nowEpochMillis) throws Exception;

    /** 列出平台待处理请求。 */
    List<PairingRequestRecord> listPairingRequests(PlatformType platform) throws Exception;

    /** 读取用户 pairing 限流状态。 */
    PairingRateLimitRecord getPairingRateLimit(PlatformType platform, String userId)
            throws Exception;

    /** 保存 pairing 限流状态。 */
    void savePairingRateLimit(PairingRateLimitRecord record) throws Exception;

    /**
     * 原子记录一次平台级 pairing 审批失败，并在达到阈值时进入锁定期。
     *
     * <p>持久化仓储应覆盖此默认实现，以数据库原子更新避免并发失败次数丢失。
     */
    default PairingRateLimitRecord recordPairingApprovalFailure(
            PlatformType platform,
            String userId,
            long nowEpochMillis,
            int maxAttempts,
            long lockoutMillis)
            throws Exception {
        PairingRateLimitRecord record = getPairingRateLimit(platform, userId);
        if (record == null) {
            record = new PairingRateLimitRecord();
            record.setPlatform(platform);
            record.setUserId(userId);
        }
        if (record.getLockoutUntil() > nowEpochMillis) {
            return record;
        }
        int attempts = record.getFailedAttempts() + 1;
        record.setRequestedAt(nowEpochMillis);
        record.setFailedAttempts(attempts >= maxAttempts ? 0 : attempts);
        record.setLockoutUntil(attempts >= maxAttempts ? nowEpochMillis + lockoutMillis : 0L);
        savePairingRateLimit(record);
        return record;
    }

    /**
     * 在审批开始后没有形成新平台锁时清除失败状态，作为正确 code 写入授权前的最终门禁。
     *
     * <p>持久化仓储应覆盖此默认实现，以数据库条件更新避免成功路径覆盖并发新锁。
     */
    default boolean clearPairingApprovalFailureIfUnlocked(
            PlatformType platform, String userId, long approvalStartedAt) throws Exception {
        PairingRateLimitRecord record = getPairingRateLimit(platform, userId);
        if (record != null && record.getLockoutUntil() > approvalStartedAt) {
            return false;
        }
        PairingRateLimitRecord cleared = new PairingRateLimitRecord();
        cleared.setPlatform(platform);
        cleared.setUserId(userId);
        cleared.setRequestedAt(0L);
        cleared.setFailedAttempts(0);
        cleared.setLockoutUntil(0L);
        savePairingRateLimit(cleared);
        return true;
    }
}
