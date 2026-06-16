package com.jimuqu.solon.claw.core.model;

import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 主动协作运行时决策结果，用于在调度链路中传递候选是否允许触达。 */
@Getter
@Setter
@NoArgsConstructor
public class ProactiveDecision {
    /** 决策记录 ID。 */
    private String decisionId;

    /** 所属 tick ID。 */
    private String tickId;

    /** 关联候选 ID。 */
    private String candidateId;

    /** 关联来源键。 */
    private String sourceKey;

    /** 决策动作，取值为 SEND 或 SKIP。 */
    private String decision;

    /** 决策原因，必须可用于诊断“为什么没有主动联系”。 */
    private String reason;

    /** 模型或确定性规则给出的消息意图，不直接作为最终出站文案。 */
    private String messageIntent;

    /** 敏感度标签，用于后续文案和投递策略判断。 */
    private String sensitivity;

    /** 决策关联候选，供后续文案生成和投递阶段使用。 */
    private ProactiveCandidateRecord candidate;

    /** 决策附加元数据。 */
    private Map<String, Object> metadata;

    /** 决策创建时间。 */
    private long createdAt;
}
