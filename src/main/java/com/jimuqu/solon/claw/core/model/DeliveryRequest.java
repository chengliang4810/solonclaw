package com.jimuqu.solon.claw.core.model;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 统一消息投递请求。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryRequest {
    /** 投递目标所属 Profile；用于 multiplex 回复、通知和状态反馈保持原路由。 */
    private String profile;

    /** 目标平台。 */
    private PlatformType platform;

    /** 目标会话 ID。 */
    private String chatId;

    /** 目标用户 ID。 */
    private String userId;

    /** 会话类型。 */
    private String chatType;

    /** 线程或话题 ID。 */
    private String threadId;

    /** 回复目标消息 ID；与线程标识分离，避免普通消息被误判为独立话题。 */
    private String replyToMessageId;

    /** 接收侧应写回的精确会话来源键；群聊主人复用私聊等场景不能由投递 chatId 反推。 */
    private String conversationSourceKey;

    /** 要投递的文本内容。 */
    private String text;

    /** 可选的会话回写内容；用于补记此前已发送但当时尚不能建立可信会话的系统消息。 */
    private String conversationRecordText;

    /** 成功投递后是否把用户可见内容作为 Agent 消息回写到普通会话。 */
    private boolean recordInConversation;

    /** 要投递的附件列表。 */
    private List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();

    /** 渠道定制扩展参数。 */
    private Map<String, Object> channelExtras = new LinkedHashMap<String, Object>();
}
