package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.PairingRequestRecord;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.gateway.service.ProfileRuntimeBundle;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteGatewayPolicyRepository;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
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

    /** 当前运行 Profile 的消息投递服务。 */
    private final DeliveryService currentDeliveryService;

    /** 命名 Profile 子运行时管理器。 */
    private final ProfileMultiplexRuntimeManager profileRuntimeManager;

    /** 创建 pairing 管理服务。 */
    public DashboardPairingService(
            GatewayAuthorizationService currentAuthorization,
            DashboardProfileContext profileContext,
            DeliveryService currentDeliveryService,
            ProfileMultiplexRuntimeManager profileRuntimeManager) {
        this.currentAuthorization = currentAuthorization;
        this.profileContext = profileContext;
        this.currentDeliveryService = currentDeliveryService;
        this.profileRuntimeManager = profileRuntimeManager;
    }

    /** 列出各国内渠道的主人和待绑定请求。 */
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
                            item.put("home_channel", homeMap(authorization.homeChannel(platform)));
                            item.put(
                                    "pending",
                                    pendingMaps(authorization.pendingPairings(platform)));
                            platforms.add(item);
                        }
                        Map<String, Object> result = new LinkedHashMap<String, Object>();
                        result.put("platforms", platforms);
                        return result;
                    }
                });
    }

    /** 使用首次私聊 pairing code 绑定当前 Profile 的平台主人和默认通知私聊。 */
    public Map<String, Object> claimOwner(String requestedProfile, String platform, String code)
            throws Exception {
        final PlatformType target = requirePlatform(platform);
        final String candidate = requireText(code, "pairing code");
        DashboardProfileContext.Scope scope = profileContext.resolve(requestedProfile);
        Map<String, Object> result =
                withAuthorization(
                        scope,
                        authorization ->
                                ownerClaimMap(
                                        scope.getName(),
                                        authorization.claimPairingOwner(target, candidate)));
        deliverWelcome(scope, result);
        return result;
    }

    /** 向已绑定平台主人的原私聊重发欢迎语，不接受客户端提供的投递目标。 */
    public Map<String, Object> retryWelcome(String requestedProfile, String platform)
            throws Exception {
        final PlatformType target = requirePlatform(platform);
        DashboardProfileContext.Scope scope = profileContext.resolve(requestedProfile);
        PlatformAdminRecord admin =
                withAuthorization(scope, authorization -> authorization.platformAdmin(target));
        if (admin == null) {
            throw new IllegalArgumentException("该平台尚未绑定主人，无法重发欢迎语。");
        }
        requireText(admin.getChatId(), "主人私聊 ID");
        Map<String, Object> result = ownerClaimMap(scope.getName(), admin);
        deliverWelcome(scope, result);
        return result;
    }

    /** 把已绑定主人的平台设为当前 Profile 唯一的主要通知渠道。 */
    public Map<String, Object> setPrimary(String requestedProfile, String platform)
            throws Exception {
        final PlatformType target = requirePlatform(platform);
        return withAuthorization(
                requestedProfile,
                authorization -> {
                    Map<String, Object> result = new LinkedHashMap<String, Object>();
                    result.put("platform", target.name().toLowerCase());
                    result.put(
                            "home_channel", homeMap(authorization.setPrimaryHomeChannel(target)));
                    result.put("ok", Boolean.TRUE);
                    return result;
                });
    }

    /** 清除当前 Profile 的平台主人。 */
    public Map<String, Object> clearOwner(String requestedProfile, String platform)
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
        return withAuthorization(profileContext.resolve(requestedProfile), action);
    }

    /** 在已解析的目标 Profile 授权服务上执行一次操作。 */
    private <T> T withAuthorization(DashboardProfileContext.Scope scope, PairingAction<T> action)
            throws Exception {
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

    /** 返回绑定结果和欢迎消息投递参数，供控制面在提交成功后立即主动问候。 */
    private Map<String, Object> ownerClaimMap(String profile, PlatformAdminRecord admin) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("platform", admin.getPlatform().name().toLowerCase());
        result.put("admin", adminMap(admin));
        Map<String, Object> welcome = new LinkedHashMap<String, Object>();
        welcome.put("profile", profile);
        welcome.put("platform", admin.getPlatform().name().toLowerCase());
        welcome.put("chat_id", admin.getChatId());
        welcome.put("user_id", admin.getUserId());
        welcome.put("chat_type", GatewayBehaviorConstants.CHAT_TYPE_DM);
        welcome.put("text", "你好，我已经准备好了。之后我会通过这个私聊主动联系和通知你。");
        // 默认视为未发送失败，只有渠道投递正常返回才标记为 sent。
        welcome.put("status", "failed");
        result.put("welcome_delivery", welcome);
        result.put("ok", Boolean.TRUE);
        return result;
    }

    /** 使用目标 Profile 自己的渠道凭据投递欢迎语，并把发送状态写回响应。 */
    @SuppressWarnings("unchecked")
    private void deliverWelcome(
            DashboardProfileContext.Scope scope, Map<String, Object> claimResult) {
        Map<String, Object> welcome = (Map<String, Object>) claimResult.get("welcome_delivery");
        DeliveryService deliveryService;
        try {
            if (scope.isCurrent()) {
                deliveryService = currentDeliveryService;
            } else {
                if (profileRuntimeManager == null) {
                    welcome.put("status", "failed");
                    welcome.put("error", "目标 Profile 网关运行时尚未启动。");
                    return;
                }
                ProfileRuntimeBundle runtime =
                        profileRuntimeManager.requireRuntime(scope.getName());
                deliveryService = runtime.appContext().getBean(DeliveryService.class);
            }
            if (deliveryService == null) {
                welcome.put("status", "failed");
                welcome.put("error", "目标 Profile 暂无可用的消息投递服务。");
                return;
            }

            DeliveryRequest request = new DeliveryRequest();
            request.setProfile(String.valueOf(welcome.get("profile")));
            request.setPlatform(PlatformType.fromName(String.valueOf(welcome.get("platform"))));
            request.setChatId(String.valueOf(welcome.get("chat_id")));
            request.setUserId(String.valueOf(welcome.get("user_id")));
            request.setChatType(String.valueOf(welcome.get("chat_type")));
            request.setText(String.valueOf(welcome.get("text")));
            deliveryService.deliver(request);
            welcome.put("status", "sent");
        } catch (Exception e) {
            welcome.put("status", "failed");
            welcome.put("error", ErrorTextSupport.safeError(e));
        }
    }

    /** 将默认通知私聊转换为 Dashboard 字段。 */
    private Map<String, Object> homeMap(HomeChannelRecord record) {
        if (record == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("chat_id", record.getChatId());
        result.put("thread_id", record.getThreadId());
        result.put("chat_name", record.getChatName());
        result.put("primary", Boolean.valueOf(record.isPrimary()));
        result.put("updated_at", Long.valueOf(record.getUpdatedAt()));
        return result;
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
