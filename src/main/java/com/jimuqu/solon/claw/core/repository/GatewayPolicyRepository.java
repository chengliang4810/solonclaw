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

    /** 查询管理员认领请求。 */
    PairingRequestRecord getAdminClaimRequest(PlatformType platform) throws Exception;

    /** 查询用户最近一次有效 pairing 请求。 */
    PairingRequestRecord getLatestUserPairingRequest(PlatformType platform, String userId)
            throws Exception;

    /** 保存 pairing 请求。 */
    void savePairingRequest(PairingRequestRecord record) throws Exception;

    /**
     * 创建管理员Claim请求If Absent。
     *
     * @param record 记录参数。
     * @return 返回创建好的管理员Claim请求If Absent。
     */
    default boolean createAdminClaimRequestIfAbsent(PairingRequestRecord record) throws Exception {
        savePairingRequest(record);
        return true;
    }

    /** 删除指定 pairing 请求。 */
    void deletePairingRequest(PlatformType platform, String code) throws Exception;

    /**
     * 删除平台下待处理 pairing 请求，但保留管理员认领请求。
     *
     * @param platform 平台参数。
     */
    default void deletePendingPairingRequests(PlatformType platform) throws Exception {
        List<PairingRequestRecord> records = listPairingRequests(platform, false);
        for (PairingRequestRecord record : records) {
            deletePairingRequest(platform, record.getCode());
        }
    }

    /** 删除已过期 pairing 请求。 */
    void deleteExpiredPairingRequests(PlatformType platform, long nowEpochMillis) throws Exception;

    /** 列出平台待处理请求。 */
    List<PairingRequestRecord> listPairingRequests(PlatformType platform, boolean includeAdminClaim)
            throws Exception;

    /** 读取用户 pairing 限流状态。 */
    PairingRateLimitRecord getPairingRateLimit(PlatformType platform, String userId)
            throws Exception;

    /** 保存 pairing 限流状态。 */
    void savePairingRateLimit(PairingRateLimitRecord record) throws Exception;
}
