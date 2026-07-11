package com.jimuqu.solon.claw.core.model;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 统一网关入站消息模型。 */
@Getter
@Setter
@NoArgsConstructor
public class GatewayMessage {
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

    /** 来源键覆盖值，供逻辑子会话等场景复用同一消息模型。 */
    private String sourceKeyOverride;

    /** 本轮消息使用的临时模型覆盖；不会持久化到会话。 */
    private String modelOverride;

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
        return value == null ? "" : value;
    }
}
