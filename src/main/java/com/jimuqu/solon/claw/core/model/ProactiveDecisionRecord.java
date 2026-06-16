package com.jimuqu.solon.claw.core.model;

import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 主动协作决策记录。 */
@Getter
@Setter
@NoArgsConstructor
public class ProactiveDecisionRecord {
    /** 决策记录 ID。 */
    private String decisionId;

    /** 所属 tick ID。 */
    private String tickId;

    /** 关联候选 ID。 */
    private String candidateId;

    /** 关联来源键。 */
    private String sourceKey;

    /** 决策动作。 */
    private String decision;

    /** 决策原因。 */
    private String reason;

    /** 准备投递的消息内容。 */
    private String message;

    /** 投递平台。 */
    private String deliveryPlatform;

    /** 投递会话 ID。 */
    private String deliveryChatId;

    /** 投递线程 ID。 */
    private String deliveryThreadId;

    /** 投递状态。 */
    private String deliveryStatus;

    /** 投递错误摘要。 */
    private String deliveryError;

    /** 决策附加元数据。 */
    private Map<String, Object> metadata;

    /** 记录创建时间。 */
    private long createdAt;
}
