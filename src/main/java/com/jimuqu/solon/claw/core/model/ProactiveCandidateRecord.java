package com.jimuqu.solon.claw.core.model;

import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 主动协作候选记录。 */
@Getter
@Setter
@NoArgsConstructor
public class ProactiveCandidateRecord {
    /** 候选记录 ID。 */
    private String candidateId;

    /** 候选来源类型。 */
    private String sourceType;

    /** 候选来源引用。 */
    private String sourceRef;

    /** 候选关联来源键。 */
    private String sourceKey;

    /** 主体类型。 */
    private String subjectType;

    /** 主体引用。 */
    private String subjectRef;

    /** 候选主题。 */
    private String topic;

    /** 候选标题。 */
    private String title;

    /** 候选摘要。 */
    private String summary;

    /** 推荐原因。 */
    private String reason;

    /** 建议动作。 */
    private String actionOffer;

    /** 候选证据数据。 */
    private Map<String, Object> evidence;

    /** 置信度。 */
    private double confidence;

    /** 优先级。 */
    private int priority;

    /** 去重键。 */
    private String dedupKey;

    /** 候选状态哈希。 */
    private String stateHash;

    /** 创建时间。 */
    private long createdAt;

    /** 过期时间；0 表示不过期。 */
    private long expiresAt;

    /** 当前状态。 */
    private String status;

    /** 最近一次决策 ID。 */
    private String lastDecisionId;

    /** 更新时间。 */
    private long updatedAt;
}
