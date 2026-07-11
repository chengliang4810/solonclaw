package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ApprovedUserRecord;
import com.jimuqu.solon.claw.core.model.PairingRequestRecord;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteGatewayPolicyRepository;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 为已认证 Dashboard 提供跨 Profile 的可信 pairing 管理能力。 */
public class DashboardPairingService {
    /** 当前运行 Profile 的授权服务。 */
    private final GatewayAuthorizationService currentAuthorization;

    /** Dashboard Profile 请求上下文。 */
    private final DashboardProfileContext profileContext;

    /** 创建 pairing 管理服务。 */
    public DashboardPairingService(
            GatewayAuthorizationService currentAuthorization,
            DashboardProfileContext profileContext) {
        this.currentAuthorization = currentAuthorization;
        this.profileContext = profileContext;
    }

    /** 列出各国内渠道的管理员、待审批申请和已批准用户。 */
    public Map<String, Object> list(String requestedProfile) throws Exception {
        return withAuthorization(
                requestedProfile,
                new PairingAction<Map<String, Object>>() {
                    @Override
                    public Map<String, Object> run(GatewayAuthorizationService authorization)
                            throws Exception {
                        List<Map<String, Object>> platforms = new ArrayList<Map<String, Object>>();
                        for (PlatformType platform : PlatformType.DOMESTIC_PLATFORMS) {
                            Map<String, Object> item = new LinkedHashMap<String, Object>();
                            item.put("platform", platform.name().toLowerCase());
                            item.put("admin", adminMap(authorization.platformAdmin(platform)));
                            item.put(
                                    "pending",
                                    pendingMaps(authorization.pendingPairings(platform)));
                            item.put(
                                    "approved",
                                    approvedMaps(authorization.approvedUsers(platform)));
                            platforms.add(item);
                        }
                        Map<String, Object> result = new LinkedHashMap<String, Object>();
                        result.put("platforms", platforms);
                        return result;
                    }
                });
    }

    /** 使用用户提交的 code 批准 pairing 请求。 */
    public Map<String, Object> approve(String requestedProfile, String platform, String code)
            throws Exception {
        final PlatformType target = requirePlatform(platform);
        final String candidate = requireText(code, "pairing code");
        return withAuthorization(
                requestedProfile,
                authorization ->
                        approvedMap(authorization.approvePairing(target, candidate, "dashboard")));
    }

    /** 撤销普通用户的 pairing 授权。 */
    public Map<String, Object> revoke(String requestedProfile, String platform, String userId)
            throws Exception {
        final PlatformType target = requirePlatform(platform);
        final String user = requireText(userId, "用户 ID");
        return withAuthorization(
                requestedProfile,
                authorization -> {
                    authorization.revokePairing(target, user);
                    return statusMap(target, user);
                });
    }

    /** 显式设置平台管理员。 */
    public Map<String, Object> setAdmin(
            String requestedProfile, String platform, String userId, String userName, String chatId)
            throws Exception {
        final PlatformType target = requirePlatform(platform);
        final String user = requireText(userId, "管理员用户 ID");
        return withAuthorization(
                requestedProfile,
                authorization ->
                        adminMap(authorization.setPlatformAdmin(target, user, userName, chatId)));
    }

    /** 显式清除平台管理员。 */
    public Map<String, Object> clearAdmin(String requestedProfile, String platform)
            throws Exception {
        final PlatformType target = requirePlatform(platform);
        return withAuthorization(
                requestedProfile,
                authorization -> {
                    authorization.clearPlatformAdmin(target);
                    return statusMap(target, null);
                });
    }

    /** 在目标 Profile 的授权服务上执行一次操作。 */
    private <T> T withAuthorization(String requestedProfile, PairingAction<T> action)
            throws Exception {
        DashboardProfileContext.Scope scope = profileContext.resolve(requestedProfile);
        if (scope.isCurrent()) {
            return action.run(currentAuthorization);
        }
        AppConfig config = scope.getConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            return action.run(
                    new GatewayAuthorizationService(
                            new SqliteGatewayPolicyRepository(database), config));
        } finally {
            database.shutdown();
        }
    }

    /** 校验国内渠道平台。 */
    private PlatformType requirePlatform(String value) {
        PlatformType platform = PlatformType.fromName(value);
        if (!PlatformType.DOMESTIC_PLATFORMS.contains(platform)) {
            throw new IllegalArgumentException("不支持的国内渠道平台：" + value);
        }
        return platform;
    }

    /** 校验必填文本。 */
    private String requireText(String value, String label) {
        if (StrUtil.isBlank(value)) {
            throw new IllegalArgumentException(label + " 不能为空。");
        }
        return value.trim();
    }

    /** 将管理员记录转换为 Dashboard 安全字段。 */
    private Map<String, Object> adminMap(PlatformAdminRecord record) {
        if (record == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("user_id", record.getUserId());
        result.put("user_name", record.getUserName());
        result.put("chat_id", record.getChatId());
        result.put("created_at", Long.valueOf(record.getCreatedAt()));
        return result;
    }

    /** 将待审批记录转换为不含 code 摘要的 Dashboard 安全字段。 */
    private List<Map<String, Object>> pendingMaps(List<PairingRequestRecord> records) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (PairingRequestRecord record : records) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("user_id", record.getUserId());
            item.put("user_name", record.getUserName());
            item.put("chat_id", record.getChatId());
            item.put("created_at", Long.valueOf(record.getCreatedAt()));
            item.put("expires_at", Long.valueOf(record.getExpiresAt()));
            result.add(item);
        }
        return result;
    }

    /** 将已批准记录列表转换为 Dashboard 字段。 */
    private List<Map<String, Object>> approvedMaps(List<ApprovedUserRecord> records) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ApprovedUserRecord record : records) {
            result.add(approvedMap(record));
        }
        return result;
    }

    /** 将单个已批准记录转换为 Dashboard 字段。 */
    private Map<String, Object> approvedMap(ApprovedUserRecord record) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("platform", record.getPlatform().name().toLowerCase());
        result.put("user_id", record.getUserId());
        result.put("user_name", record.getUserName());
        result.put("approved_at", Long.valueOf(record.getApprovedAt()));
        result.put("approved_by", record.getApprovedBy());
        return result;
    }

    /** 返回简单操作结果。 */
    private Map<String, Object> statusMap(PlatformType platform, String userId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("platform", platform.name().toLowerCase());
        result.put("user_id", userId);
        result.put("ok", Boolean.TRUE);
        return result;
    }

    /** 目标 Profile pairing 操作。 */
    private interface PairingAction<T> {
        /** 执行一次授权管理操作。 */
        T run(GatewayAuthorizationService authorization) throws Exception;
    }
}
