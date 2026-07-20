package com.jimuqu.solon.claw.gateway.authorization;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.PairingRateLimitRecord;
import com.jimuqu.solon.claw.core.model.PairingRequestAdmissionResult;
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

        // 平台已有主人后，其他私聊不再产生新的授权入口，保持个人助手边界。
        return null;
    }

    /** 判断消息发送者是否具备对话权限。 */
    public boolean isAuthorized(GatewayMessage message) throws Exception {
        PlatformType platform = message.getPlatform();
        if (platform == null) {
            return false;
        }

        PlatformAdminRecord admin = repository.getPlatformAdmin(platform);
        if (admin != null && sameUser(admin.getUserId(), message.getUserId())) {
            return true;
        }

        return false;
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

    /** 返回平台默认通知私聊，供可信控制面展示。 */
    public HomeChannelRecord homeChannel(PlatformType platform) throws Exception {
        return repository.getHomeChannel(platform);
    }

    /**
     * 由可信控制面把已绑定主人的平台私聊设为当前 Profile 的主要通知渠道。
     *
     * @param platform 目标平台。
     * @return 更新后的主要通知渠道记录。
     */
    public HomeChannelRecord setPrimaryHomeChannel(PlatformType platform) throws Exception {
        if (platform == null) {
            throw new IllegalArgumentException("平台不能为空。");
        }
        if (repository.getPlatformAdmin(platform) == null) {
            throw new IllegalArgumentException("该平台尚未绑定主人，不能设为主要通知渠道。");
        }
        HomeChannelRecord home = repository.setPrimaryHomeChannel(platform);
        if (home == null) {
            throw new IllegalArgumentException("该平台尚未设置默认通知私聊。");
        }
        return home;
    }

    /** 返回未过期 pairing 请求，供可信控制面展示申请人信息。 */
    public List<PairingRequestRecord> pendingPairings(PlatformType platform) throws Exception {
        repository.deleteExpiredPairingRequests(platform, System.currentTimeMillis());
        return repository.listPairingRequests(platform);
    }

    /**
     * 由可信控制面使用首次私聊 pairing code 绑定平台主人和默认通知私聊。
     *
     * <p>渠道内的 {@code /pairing claim-admin} 仍固定拒绝；只有已认证 Dashboard 或本机控制面可以调用此方法。
     *
     * @param platform 目标平台。
     * @param code 主人首次私聊生成的临时 code。
     * @return 新创建的平台管理员记录，包含欢迎消息的私聊投递目标。
     */
    public PlatformAdminRecord claimPairingOwner(PlatformType platform, String code)
            throws Exception {
        if (platform == null || StrUtil.isBlank(code)) {
            throw new IllegalArgumentException("平台和 pairing code 不能为空。");
        }
        if (repository.getPlatformAdmin(platform) != null) {
            throw new IllegalArgumentException("该平台已绑定主人，不能再次认领。");
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
        if (StrUtil.isBlank(request.getChatId())) {
            throw new IllegalArgumentException("pairing 请求缺少主人私聊标识，请重新发起绑定。");
        }
        if (!repository.clearPairingApprovalFailureIfUnlocked(
                platform, PLATFORM_APPROVAL_RATE_LIMIT_KEY, now)) {
            throw new IllegalArgumentException("pairing 审批失败次数过多，请稍后再试。");
        }

        PlatformAdminRecord admin = new PlatformAdminRecord();
        admin.setPlatform(platform);
        admin.setUserId(request.getUserId());
        admin.setUserName(request.getUserName());
        admin.setChatId(request.getChatId());
        admin.setCreatedAt(now);

        HomeChannelRecord home = new HomeChannelRecord();
        home.setPlatform(platform);
        home.setChatId(request.getChatId());
        home.setChatName(blankToDefault(request.getUserName(), request.getChatId()));
        home.setUpdatedAt(now);
        if (!repository.claimPlatformAdminIfAbsent(request, admin, home)) {
            throw new IllegalArgumentException("该平台已绑定主人，不能再次认领。");
        }
        return admin;
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

    /** 生成平台状态文本。 */
    public String formatPlatformStatus(List<ChannelStatus> statuses) throws Exception {
        StringBuilder buffer = new StringBuilder();
        for (ChannelStatus status : statuses) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            PlatformAdminRecord admin = repository.getPlatformAdmin(status.getPlatform());
            HomeChannelRecord home = repository.getHomeChannel(status.getPlatform());
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
                    .append(getUnauthorizedDmBehavior(status.getPlatform()));
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
            return null;
        }
        PairingRequestRecord request = new PairingRequestRecord();
        request.setPlatform(platform);
        request.setCode(generateCode());
        request.setUserId(message.getUserId());
        request.setUserName(message.getUserName());
        request.setChatId(message.getChatId());
        request.setCreatedAt(now);
        request.setExpiresAt(now + PairingConstants.CODE_TTL_MILLIS);
        PairingRequestAdmissionResult admission =
                repository.admitPairingRequest(
                        request,
                        now,
                        PairingConstants.MAX_PENDING_PER_PLATFORM,
                        PairingConstants.RATE_LIMIT_MILLIS);
        if (admission.getStatus() == PairingRequestAdmissionResult.Status.RATE_LIMITED) {
            return null;
        }
        if (admission.getStatus() == PairingRequestAdmissionResult.Status.CAPACITY_REACHED) {
            return null;
        }
        return pairingPrompt(platform, request.getCode());
    }

    /** 格式化 pairing 提示文案。 */
    private GatewayReply pairingPrompt(PlatformType platform, String code) {
        return GatewayReply.ok(pairingPromptText(platform, code));
    }

    /** 返回 pairing 提示正文，供可信控制面在批准后补入主人会话上下文。 */
    public String pairingPromptText(PlatformType platform, String code) {
        return "当前 Profile 尚未绑定你的 "
                + platform.name().toLowerCase()
                + " 私聊。\n\n"
                + "这是你的 pairing code：`"
                + code
                + "`\n\n"
                + "请打开 Dashboard 的消息渠道页面，在当前 Profile 下选择该渠道，"
                + "然后在“绑定本人”中输入此配对码。";
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
