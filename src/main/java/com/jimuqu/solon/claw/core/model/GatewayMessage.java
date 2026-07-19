package com.jimuqu.solon.claw.core.model;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 统一网关入站消息模型。 */
@Getter
@Setter
@NoArgsConstructor
public class GatewayMessage {
    /** 群聊访客来源键标记，用于在会话、上下文、记忆与工具层执行统一隐私隔离。 */
    private static final String GROUP_GUEST_SOURCE_MARKER = ":__group_guest__:";

    /** 后台委派完成结果回流父会话时使用的内部运行类型。 */
    public static final String RUN_KIND_DELEGATION_COMPLETION = "delegation_completion";

    /** 消息归属的 Profile；default 或空值保持单 Profile 旧会话键。 */
    private String profile;

    /** 消息来源平台。 */
    private PlatformType platform;

    /** 会话或群 ID。 */
    private String chatId;

    /** 发送者用户 ID。 */
    private String userId;

    /** 会话类型，取值见 {@link GatewayBehaviorConstants}。 */
    private String chatType;

    /** 会话展示名称。 */
    private String chatName;

    /** 发送者展示名称。 */
    private String userName;

    /** 消息文本内容。 */
    private String text;

    /** 渠道线程 ID。 */
    private String threadId;

    /** 渠道原始消息 ID，仅用于把回复关联到本条入站消息，不参与会话来源键。 */
    private String replyToMessageId;

    /** 平台分配的稳定入站消息 ID；仅用于跨进程幂等，不承担回复锚点或内部运行标识语义。 */
    private String platformMessageId;

    /** 入站总账标识，与平台消息 ID 和 Agent run ID 分离。 */
    private String ingressId;

    /** 已同步准入的原始消息总账标识；文本合批时按接收顺序保留，且不进入持久化消息载荷。 */
    private transient List<String> inboundReceiptIds = new ArrayList<String>();

    /** 真实网关回复的进程内终态提交器；由编排器在输出租约内调用，不进入任何持久化载荷。 */
    private transient Function<GatewayReply, Boolean> replyCommitter;

    /** 来源键覆盖值，供逻辑子会话等场景复用同一消息模型。 */
    private String sourceKeyOverride;

    /** 本轮消息使用的临时模型覆盖；不会持久化到会话。 */
    private String modelOverride;

    /** 本轮消息使用的精简系统提示；非空时跳过 Profile 上下文与长期记忆。 */
    private String systemPromptOverride;

    /** 本轮消息使用的临时工具集覆盖；不会持久化到会话。 */
    private List<String> enabledToolsetsOverride = new ArrayList<String>();

    /** 本轮消息需要临时禁用的工具集；不会持久化到会话。 */
    private List<String> disabledToolsetsOverride = new ArrayList<String>();

    /** 本轮消息允许调用的工具名白名单；仅影响当前运行，不写入会话配置。 */
    private List<String> allowedToolsOverride = new ArrayList<String>();

    /** 本轮消息必须真实完成的工具名列表；仅用于受控 Web 回归的运行后校验。 */
    private List<String> requiredToolsOverride = new ArrayList<String>();

    /** 本轮消息允许尝试的最大工具调用次数；仅影响当前运行，不写入会话配置。 */
    private Integer maxToolCallsOverride;

    /** 本轮消息使用的临时工作目录覆盖；不会持久化到会话。 */
    private String workspaceDirOverride;

    /** 本轮消息对应的运行类型，例如 conversation、cron、heartbeat、subagent。 */
    private String runKind;

    /** 是否为 heartbeat 触发的合成消息。 */
    private boolean heartbeat;

    /**
     * 标记本消息是 goal 续轮/kickoff 合成消息（非真实用户输入），用于续轮抢占判定。
     *
     * <p>该标志经 {@code AgentRunSupervisor.serializeMessage} 持久化到队列消息的 messageJson，
     * 反序列化后恢复，从而在续轮调度前能区分队列中的合成消息与真实用户消息。
     */
    private transient boolean goalContinuation;

    /** 入站附件列表。 */
    private List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();

    /** 入站时间戳。 */
    private long timestamp;

    /** 便捷构造方法，用于构建最小可用入站消息。 */
    public GatewayMessage(PlatformType platform, String chatId, String userId, String text) {
        this.platform = platform;
        this.chatId = chatId;
        this.userId = userId;
        this.chatType = GatewayBehaviorConstants.CHAT_TYPE_DM;
        this.chatName = chatId;
        this.userName = userId;
        this.text = text;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 构造来源键，作为会话绑定与工具状态隔离的主键。
     *
     * @return 平台:会话[:线程]:用户 组成的来源键
     */
    public String sourceKey() {
        String key;
        if (StrUtil.isNotBlank(sourceKeyOverride)) {
            key = sourceKeyOverride.trim();
        } else if (StrUtil.isNotBlank(threadId)) {
            key =
                    String.valueOf(platform)
                            + ":"
                            + nullToEmpty(chatId)
                            + ":"
                            + threadId.trim()
                            + ":"
                            + nullToEmpty(userId);
        } else {
            key = String.valueOf(platform) + ":" + nullToEmpty(chatId) + ":" + nullToEmpty(userId);
        }
        return profileScopedKey(key);
    }

    /**
     * 判断当前消息是否为群聊访客的受限会话。
     *
     * @return 来源键已进入群聊访客隔离空间时返回 true
     */
    public boolean isGroupGuest() {
        return isGroupGuestSourceKey(sourceKeyOverride);
    }

    /**
     * 判断来源键是否属于群聊访客隔离空间，兼容命名 Profile 前缀。
     *
     * @param sourceKey 待检查的来源键
     * @return 来源键包含群聊访客保留标记时返回 true
     */
    public static boolean isGroupGuestSourceKey(String sourceKey) {
        return StrUtil.isNotBlank(sourceKey) && sourceKey.contains(GROUP_GUEST_SOURCE_MARKER);
    }

    /**
     * 构造主人私聊来源键，使主人在群聊中的对话复用其私聊历史。
     *
     * @param platform 消息平台
     * @param chatId 主人私聊会话 ID
     * @param userId 主人用户 ID
     * @return 未附加 Profile 前缀的私聊来源键
     */
    public static String directSourceKey(PlatformType platform, String chatId, String userId) {
        return String.valueOf(platform)
                + ":"
                + nullToEmptyStatic(chatId)
                + ":"
                + nullToEmptyStatic(userId);
    }

    /**
     * 构造群聊访客独立来源键，同一群、同一访客可保留自己的连续对话。
     *
     * @param platform 消息平台
     * @param chatId 群聊 ID
     * @param userId 访客用户 ID
     * @return 未附加 Profile 前缀的访客来源键
     */
    public static String groupGuestSourceKey(PlatformType platform, String chatId, String userId) {
        return String.valueOf(platform)
                + GROUP_GUEST_SOURCE_MARKER
                + nullToEmptyStatic(chatId)
                + ":"
                + nullToEmptyStatic(userId);
    }

    /**
     * 把命名 Profile 纳入来源键，避免相同平台会话在多个 Profile 中绑定到同一会话。
     *
     * <p>default 保持原有键格式，避免单 Profile 部署的既有会话失联；命名 Profile 使用稳定前缀，且会识别已经带前缀的合成消息，防止续轮重复添加。
     */
    private String profileScopedKey(String key) {
        String normalized = StrUtil.nullToEmpty(profile).trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() == 0 || "default".equals(normalized)) {
            return key;
        }
        String prefix = "profile:" + normalized + ":";
        return key.startsWith(prefix) ? key : prefix + key;
    }

    /** 将空值转为空字符串，避免来源键出现字面量 null。 */
    private String nullToEmpty(String value) {
        return nullToEmptyStatic(value);
    }

    /** 将静态构造方法收到的空值转为空字符串。 */
    private static String nullToEmptyStatic(String value) {
        return value == null ? "" : value;
    }
}
