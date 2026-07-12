package com.jimuqu.solon.claw.gateway.authorization;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ApprovedUserRecord;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.PairingRateLimitRecord;
import com.jimuqu.solon.claw.core.model.PairingRequestRecord;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;
import com.jimuqu.solon.claw.support.constants.PairingConstants;
import java.security.SecureRandom;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** 统一处理平台管理员、pairing 与 home channel 授权逻辑。 */
@RequiredArgsConstructor
public class GatewayAuthorizationService {
    /** 平台审批失败状态使用独立内部键，避免与单个渠道用户的请求限流混用。 */
    private static final String PLATFORM_APPROVAL_RATE_LIMIT_KEY =
            "solonclaw:pairing-platform-approval";

    /** 授权状态仓储。 */
    private final GatewayPolicyRepository repository;

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** pairing code 生成器。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 在进入主处理链前执行预授权检查。
     *
     * @param message 入站消息
     * @return 若需要立即回复则返回对应回复，否则返回 null
     */
    public GatewayReply preAuthorize(GatewayMessage message) throws Exception {
        if (message == null || message.getPlatform() == null) {
            return null;
        }

        PlatformType platform = message.getPlatform();
        PlatformAdminRecord admin = repository.getPlatformAdmin(platform);

        if (admin == null) {
            if (!isDm(message)) {
                return null;
            }
            return createPairingPrompt(message);
        }

        if (isAuthorized(message)) {
            return null;
        }

        if (!isDm(message)) {
            return null;
        }

        if (!GatewayBehaviorConstants.UNAUTHORIZED_DM_BEHAVIOR_PAIR.equals(
                getUnauthorizedDmBehavior(platform))) {
            return null;
        }

        return createPairingPrompt(message);
    }

    /** 判断消息发送者是否具备对话权限。 */
    public boolean isAuthorized(GatewayMessage message) throws Exception {
        PlatformType platform = message.getPlatform();
        if (platform == null) {
            return false;
        }

        AppConfig.ChannelConfig channelConfig = channelConfig(platform);
        if (channelConfig != null && channelConfig.isAllowAllUsers()) {
            return true;
        }
        if (appConfig.getGateway().isAllowAllUsers()) {
            return true;
        }

        PlatformAdminRecord admin = repository.getPlatformAdmin(platform);
        if (admin != null && sameUser(admin.getUserId(), message.getUserId())) {
            return true;
        }

        ApprovedUserRecord approved = repository.getApprovedUser(platform, message.getUserId());
        if (approved != null) {
            return true;
        }

        if (channelConfig != null
                && contains(channelConfig.getAllowedUsers(), message.getUserId())) {
            return true;
        }

        return contains(appConfig.getGateway().getAllowedUsers(), message.getUserId());
    }

    /** 判断消息发送者是否为该平台管理员。 */
    public boolean isAdmin(GatewayMessage message) throws Exception {
        PlatformAdminRecord admin = repository.getPlatformAdmin(message.getPlatform());
        return admin != null && sameUser(admin.getUserId(), message.getUserId());
    }

    /**
     * 拒绝从未认证的入站消息自举平台管理员。
     *
     * <p>平台管理员只能由本机或已认证的管理面显式配置，渠道用户输入不能成为信任根。
     *
     * @param message 入站消息
     * @return 固定拒绝回复
     */
    public GatewayReply claimAdmin(GatewayMessage message) throws Exception {
        return GatewayReply.error("不允许通过渠道消息认领平台管理员，请在本机或管理面完成配置。");
    }

    /**
     * 由可信本机或已认证管理面设置平台管理员。
     *
     * @param platform 目标平台。
     * @param userId 管理员平台用户 ID。
     * @param userName 管理员显示名称。
     * @param chatId 管理员私聊会话 ID。
     * @return 保存后的管理员记录。
     */
    public PlatformAdminRecord setPlatformAdmin(
            PlatformType platform, String userId, String userName, String chatId) throws Exception {
        if (platform == null || StrUtil.isBlank(userId)) {
            throw new IllegalArgumentException("平台和管理员用户 ID 不能为空。");
        }
        PlatformAdminRecord record = new PlatformAdminRecord();
        record.setPlatform(platform);
        record.setUserId(userId.trim());
        record.setUserName(blankToNull(userName));
        record.setChatId(blankToNull(chatId));
        record.setCreatedAt(System.currentTimeMillis());
        repository.savePlatformAdmin(record);
        return record;
    }

    /** 由可信本机或已认证管理面清除平台管理员。 */
    public void clearPlatformAdmin(PlatformType platform) throws Exception {
        if (platform == null) {
            throw new IllegalArgumentException("平台不能为空。");
        }
        repository.deletePlatformAdmin(platform);
    }

    /** 返回平台管理员，供可信控制面展示。 */
    public PlatformAdminRecord platformAdmin(PlatformType platform) throws Exception {
        return repository.getPlatformAdmin(platform);
    }

    /** 返回未过期 pairing 请求，供可信控制面展示申请人信息。 */
    public List<PairingRequestRecord> pendingPairings(PlatformType platform) throws Exception {
        repository.deleteExpiredPairingRequests(platform, System.currentTimeMillis());
        return repository.listPairingRequests(platform);
    }

    /** 返回已批准用户，供可信控制面展示。 */
    public List<ApprovedUserRecord> approvedUsers(PlatformType platform) throws Exception {
        return repository.listApprovedUsers(platform);
    }

    /**
     * 由可信控制面批准用户提交的 pairing code。
     *
     * @param platform 目标平台。
     * @param code 用户提交的临时 code。
     * @param approvedBy 审批来源标识。
     * @return 被批准的用户记录。
     */
    public ApprovedUserRecord approvePairing(PlatformType platform, String code, String approvedBy)
            throws Exception {
        if (platform == null || StrUtil.isBlank(code)) {
            throw new IllegalArgumentException("平台和 pairing code 不能为空。");
        }
        long now = System.currentTimeMillis();
        if (isPlatformApprovalLocked(platform, now)) {
            throw new IllegalArgumentException("pairing 审批失败次数过多，请稍后再试。");
        }
        repository.deleteExpiredPairingRequests(platform, now);
        PairingRequestRecord request = repository.getPairingRequest(platform, code.trim());
        if (request == null || request.getExpiresAt() < now) {
            recordPlatformApprovalFailure(platform, now);
            throw new IllegalArgumentException("pairing code 无效或已过期。");
        }
        if (!repository.clearPairingApprovalFailureIfUnlocked(
                platform, PLATFORM_APPROVAL_RATE_LIMIT_KEY, now)) {
            throw new IllegalArgumentException("pairing 审批失败次数过多，请稍后再试。");
        }
        ApprovedUserRecord approved = new ApprovedUserRecord();
        approved.setPlatform(platform);
        approved.setUserId(request.getUserId());
        approved.setUserName(request.getUserName());
        approved.setApprovedAt(now);
        approved.setApprovedBy(blankToDefault(approvedBy, "trusted-control"));
        repository.saveApprovedUser(approved);
        repository.deletePairingRequest(platform, request.getCode());
        return approved;
    }

    /** 由可信控制面撤销已批准用户，平台管理员不属于可撤销用户列表。 */
    public void revokePairing(PlatformType platform, String userId) throws Exception {
        if (platform == null || StrUtil.isBlank(userId)) {
            throw new IllegalArgumentException("平台和用户 ID 不能为空。");
        }
        PlatformAdminRecord admin = repository.getPlatformAdmin(platform);
        if (admin != null && sameUser(admin.getUserId(), userId.trim())) {
            throw new IllegalArgumentException("平台管理员不能作为普通授权用户撤销。");
        }
        repository.revokeApprovedUser(platform, userId.trim());
    }

    /** 将当前聊天设置为 home channel。 */
    public GatewayReply setHome(GatewayMessage message) throws Exception {
        if (!isAdmin(message)) {
            return GatewayReply.error("只有平台管理员可以执行 " + GatewayCommandConstants.SLASH_SETHOME + "。");
        }

        HomeChannelRecord record = new HomeChannelRecord();
        record.setPlatform(message.getPlatform());
        record.setChatId(message.getChatId());
        record.setThreadId(blankToNull(message.getThreadId()));
        record.setChatName(blankToDefault(message.getChatName(), message.getChatId()));
        record.setUpdatedAt(System.currentTimeMillis());
        repository.saveHomeChannel(record);
        return GatewayReply.ok(
                "已将 Home Channel 设置为 " + record.getChatName() + "（" + record.getChatId() + "）。");
    }

    /** 查看待审批 pairing 请求。 */
    public GatewayReply pairingPending(GatewayMessage message, PlatformType targetPlatform)
            throws Exception {
        if (!isAdminForPlatform(message, targetPlatform)) {
            return GatewayReply.error("只有平台管理员可以查看待处理的 pairing 请求。");
        }
        if (!isDm(message)) {
            return GatewayReply.error("pairing 管理命令必须在私聊中执行。");
        }

        repository.deleteExpiredPairingRequests(targetPlatform, System.currentTimeMillis());
        List<PairingRequestRecord> records = repository.listPairingRequests(targetPlatform);
        if (records.isEmpty()) {
            return GatewayReply.ok(targetPlatform.name().toLowerCase() + " 平台当前没有待处理的 pairing 请求。");
        }

        StringBuilder buffer = new StringBuilder();
        for (PairingRequestRecord record : records) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(blankToDefault(record.getUserName(), record.getUserId()))
                    .append(" [")
                    .append(record.getUserId())
                    .append("]，请向用户索取其 pairing code");
        }
        return GatewayReply.ok(buffer.toString());
    }

    /** 查看待审批与已批准 pairing 用户汇总。 */
    public GatewayReply pairingList(GatewayMessage message, PlatformType targetPlatform)
            throws Exception {
        if (!isAdminForPlatform(message, targetPlatform)) {
            return GatewayReply.error("只有平台管理员可以查看 pairing 列表。");
        }
        if (!isDm(message)) {
            return GatewayReply.error("pairing 管理命令必须在私聊中执行。");
        }

        repository.deleteExpiredPairingRequests(targetPlatform, System.currentTimeMillis());
        List<PairingRequestRecord> pending = repository.listPairingRequests(targetPlatform);
        List<ApprovedUserRecord> approved = repository.listApprovedUsers(targetPlatform);
        String platformName = targetPlatform.name().toLowerCase();
        StringBuilder buffer = new StringBuilder();
        buffer.append(platformName).append(" 待处理 pairing：");
        if (pending.isEmpty()) {
            buffer.append("无");
        } else {
            for (PairingRequestRecord record : pending) {
                buffer.append('\n')
                        .append("- ")
                        .append(blankToDefault(record.getUserName(), record.getUserId()))
                        .append(" [")
                        .append(record.getUserId())
                        .append("]");
            }
        }
        buffer.append('\n').append(platformName).append(" 已批准用户：");
        if (approved.isEmpty()) {
            buffer.append("无");
        } else {
            for (ApprovedUserRecord record : approved) {
                buffer.append('\n')
                        .append("- ")
                        .append(blankToDefault(record.getUserName(), record.getUserId()))
                        .append(" [")
                        .append(record.getUserId())
                        .append("]");
            }
        }
        return GatewayReply.ok(buffer.toString());
    }

    /** 清理平台待处理 pairing 请求，不影响已批准用户。 */
    public GatewayReply pairingClearPending(GatewayMessage message, PlatformType targetPlatform)
            throws Exception {
        if (!isAdminForPlatform(message, targetPlatform)) {
            return GatewayReply.error("只有平台管理员可以清理待处理 pairing 请求。");
        }
        if (!isDm(message)) {
            return GatewayReply.error("pairing 管理命令必须在私聊中执行。");
        }

        repository.deletePendingPairingRequests(targetPlatform);
        return GatewayReply.ok(targetPlatform.name().toLowerCase() + " 平台待处理 pairing 请求已清理。");
    }

    /** 批准 pairing code。 */
    public GatewayReply pairingApprove(
            GatewayMessage message, PlatformType targetPlatform, String code) throws Exception {
        if (!isAdminForPlatform(message, targetPlatform)) {
            return GatewayReply.error("只有平台管理员可以批准 pairing code。");
        }
        if (!isDm(message)) {
            return GatewayReply.error("pairing 批准必须在私聊中执行。");
        }

        ApprovedUserRecord approvedUser;
        try {
            approvedUser = approvePairing(targetPlatform, code, message.getUserId());
        } catch (IllegalArgumentException e) {
            return GatewayReply.error("pairing code 无效或已过期。");
        }
        return GatewayReply.ok(
                "已批准 "
                        + blankToDefault(approvedUser.getUserName(), approvedUser.getUserId())
                        + " 使用 "
                        + targetPlatform.name().toLowerCase()
                        + " 平台。");
    }

    /** 撤销已批准用户。 */
    public GatewayReply pairingRevoke(
            GatewayMessage message, PlatformType targetPlatform, String userId) throws Exception {
        if (!isAdminForPlatform(message, targetPlatform)) {
            return GatewayReply.error("只有平台管理员可以撤销已批准用户。");
        }

        try {
            revokePairing(targetPlatform, userId);
        } catch (IllegalArgumentException e) {
            return GatewayReply.error(e.getMessage());
        }
        return GatewayReply.ok(
                "已撤销 " + userId + " 在 " + targetPlatform.name().toLowerCase() + " 平台的使用权限。");
    }

    /** 查看已批准用户。 */
    public GatewayReply pairingApproved(GatewayMessage message, PlatformType targetPlatform)
            throws Exception {
        if (!isAdminForPlatform(message, targetPlatform)) {
            return GatewayReply.error("只有平台管理员可以查看已批准用户。");
        }

        List<ApprovedUserRecord> records = repository.listApprovedUsers(targetPlatform);
        if (records.isEmpty()) {
            return GatewayReply.ok(targetPlatform.name().toLowerCase() + " 平台当前没有已批准用户。");
        }

        StringBuilder buffer = new StringBuilder();
        for (ApprovedUserRecord record : records) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(blankToDefault(record.getUserName(), record.getUserId()))
                    .append(" [")
                    .append(record.getUserId())
                    .append("]");
        }
        return GatewayReply.ok(buffer.toString());
    }

    /** 生成平台状态文本。 */
    public String formatPlatformStatus(List<ChannelStatus> statuses) throws Exception {
        StringBuilder buffer = new StringBuilder();
        for (ChannelStatus status : statuses) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            PlatformAdminRecord admin = repository.getPlatformAdmin(status.getPlatform());
            HomeChannelRecord home = repository.getHomeChannel(status.getPlatform());
            int approved = repository.countApprovedUsers(status.getPlatform());
            buffer.append(status.getPlatform())
                    .append(" enabled=")
                    .append(status.isEnabled())
                    .append(" connected=")
                    .append(status.isConnected())
                    .append(" detail=")
                    .append(status.getDetail())
                    .append(" admin=")
                    .append(admin == null ? GatewayBehaviorConstants.NONE : admin.getUserId())
                    .append(" home=")
                    .append(home == null ? GatewayBehaviorConstants.NONE : home.getChatId())
                    .append(" pairing=")
                    .append(getUnauthorizedDmBehavior(status.getPlatform()))
                    .append(" approved=")
                    .append(approved);
        }
        return buffer.toString();
    }

    /** 查询 home channel。 */
    public HomeChannelRecord getHomeChannel(PlatformType platform) throws Exception {
        return repository.getHomeChannel(platform);
    }

    /** 创建未授权用户的 pairing 提示。 */
    private GatewayReply createPairingPrompt(GatewayMessage message) throws Exception {
        PlatformType platform = message.getPlatform();
        long now = System.currentTimeMillis();
        if (isPlatformApprovalLocked(platform, now)) {
            return GatewayReply.ok("pairing 失败次数过多，请稍后再试。");
        }
        PairingRateLimitRecord rateLimit =
                repository.getPairingRateLimit(platform, message.getUserId());
        if (rateLimit != null
                && rateLimit.getRequestedAt() > 0
                && now - rateLimit.getRequestedAt() < PairingConstants.RATE_LIMIT_MILLIS) {
            long remainingMillis =
                    PairingConstants.RATE_LIMIT_MILLIS - (now - rateLimit.getRequestedAt());
            long remainingMinutes = Math.max(1L, (remainingMillis + 59_999L) / 60_000L);
            return GatewayReply.ok("pairing 请求过于频繁，请 " + remainingMinutes + " 分钟后再试。");
        }

        PairingRequestRecord request = new PairingRequestRecord();
        request.setPlatform(platform);
        request.setCode(generateCode());
        request.setUserId(message.getUserId());
        request.setUserName(message.getUserName());
        request.setChatId(message.getChatId());
        request.setCreatedAt(now);
        request.setExpiresAt(now + PairingConstants.CODE_TTL_MILLIS);
        if (!repository.trySavePairingRequest(
                request, now, PairingConstants.MAX_PENDING_PER_PLATFORM)) {
            return GatewayReply.ok("当前待处理的 pairing 请求过多，请稍后再试。");
        }
        saveRequestRate(
                platform,
                message.getUserId(),
                now,
                rateLimit == null ? 0 : rateLimit.getFailedAttempts(),
                rateLimit == null ? 0L : rateLimit.getLockoutUntil());
        return pairingPrompt(platform, request.getCode());
    }

    /** 格式化 pairing 提示文案。 */
    private GatewayReply pairingPrompt(PlatformType platform, String code) {
        return GatewayReply.ok(
                "当前还未识别你的身份。\n\n"
                        + "这是你的 pairing code：`"
                        + code
                        + "`\n\n"
                        + "请联系平台管理员在私聊中执行：\n"
                        + "`"
                        + GatewayCommandConstants.SLASH_PAIRING
                        + " "
                        + GatewayCommandConstants.ACTION_APPROVE
                        + " "
                        + platform.name().toLowerCase()
                        + " "
                        + code
                        + "`");
    }

    /** 判断平台是否因连续错误 pairing 审批而处于锁定期。 */
    private boolean isPlatformApprovalLocked(PlatformType platform, long now) throws Exception {
        PairingRateLimitRecord record =
                repository.getPairingRateLimit(platform, PLATFORM_APPROVAL_RATE_LIMIT_KEY);
        return record != null && record.getLockoutUntil() > now;
    }

    /** 记录平台级审批失败，所有 Dashboard、CLI 与渠道审批入口共享同一计数。 */
    private void recordPlatformApprovalFailure(PlatformType platform, long now) throws Exception {
        repository.recordPairingApprovalFailure(
                platform,
                PLATFORM_APPROVAL_RATE_LIMIT_KEY,
                now,
                PairingConstants.MAX_FAILED_ATTEMPTS,
                PairingConstants.LOCKOUT_MILLIS);
    }

    /** 保存 pairing 请求速率记录。 */
    private void saveRequestRate(
            PlatformType platform,
            String userId,
            long requestedAt,
            int failedAttempts,
            long lockoutUntil)
            throws Exception {
        PairingRateLimitRecord record = new PairingRateLimitRecord();
        record.setPlatform(platform);
        record.setUserId(userId);
        record.setRequestedAt(requestedAt);
        record.setFailedAttempts(failedAttempts);
        record.setLockoutUntil(lockoutUntil);
        repository.savePairingRateLimit(record);
    }

    /** 判断消息是否来自目标平台管理员私聊。 */
    private boolean isAdminForPlatform(GatewayMessage message, PlatformType platform)
            throws Exception {
        if (!isDm(message)) {
            return false;
        }
        if (message.getPlatform() != platform) {
            return false;
        }
        return isAdmin(message);
    }

    /** 按平台读取渠道配置。 */
    private AppConfig.ChannelConfig channelConfig(PlatformType platform) {
        if (platform == PlatformType.DINGTALK) {
            return appConfig.getChannels().getDingtalk();
        }
        if (platform == PlatformType.FEISHU) {
            return appConfig.getChannels().getFeishu();
        }
        if (platform == PlatformType.WECOM) {
            return appConfig.getChannels().getWecom();
        }
        if (platform == PlatformType.WEIXIN) {
            return appConfig.getChannels().getWeixin();
        }
        if (platform == PlatformType.QQBOT) {
            return appConfig.getChannels().getQqbot();
        }
        if (platform == PlatformType.YUANBAO) {
            return appConfig.getChannels().getYuanbao();
        }
        return null;
    }

    /** 判断允许名单是否包含指定用户。 */
    private boolean contains(List<String> values, String userId) {
        if (values == null || userId == null) {
            return false;
        }
        if (values.contains(GatewayBehaviorConstants.ALLOW_ALL_MARKER)) {
            return true;
        }
        return values.contains(userId);
    }

    /** 读取渠道的未授权私聊处理行为。 */
    private String getUnauthorizedDmBehavior(PlatformType platform) {
        AppConfig.ChannelConfig channelConfig = channelConfig(platform);
        return channelConfig == null
                ? GatewayBehaviorConstants.UNAUTHORIZED_DM_BEHAVIOR_PAIR
                : channelConfig.getUnauthorizedDmBehavior();
    }

    /** 判断消息是否为私聊。 */
    private boolean isDm(GatewayMessage message) {
        return GatewayBehaviorConstants.CHAT_TYPE_DM.equalsIgnoreCase(
                blankToDefault(message.getChatType(), GatewayBehaviorConstants.CHAT_TYPE_DM));
    }

    /** 比较两个用户是否相同。 */
    private boolean sameUser(String left, String right) {
        return left != null && left.equals(right);
    }

    /** 为空字符串提供默认值。 */
    private String blankToDefault(String value, String defaultValue) {
        return StrUtil.blankToDefault(value, defaultValue);
    }

    /**
     * 将空白字符串归一为空值。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回blank To Null结果。
     */
    private String blankToNull(String value) {
        return StrUtil.blankToDefault(value, null);
    }

    /** 生成新的 pairing code。 */
    private String generateCode() {
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < PairingConstants.CODE_LENGTH; i++) {
            int index = secureRandom.nextInt(PairingConstants.CODE_ALPHABET.length());
            buffer.append(PairingConstants.CODE_ALPHABET.charAt(index));
        }
        return buffer.toString();
    }
}
