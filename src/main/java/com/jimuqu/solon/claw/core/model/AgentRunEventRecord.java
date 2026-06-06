package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Agent 运行时间线事件。 */
@Getter
@Setter
@NoArgsConstructor
public class AgentRunEventRecord {
    /** 记录Agent运行事件中的事件标识。 */
    private String eventId;

    /** 记录Agent运行事件中的运行标识。 */
    private String runId;

    /** 记录Agent运行事件中的会话标识。 */
    private String sessionId;

    /** 记录Agent运行事件中的来源键。 */
    private String sourceKey;

    /** 记录Agent运行事件中的事件类型。 */
    private String eventType;

    /** 记录Agent运行事件中的phase。 */
    private String phase;

    /** 记录Agent运行事件中的severity。 */
    private String severity;

    /** 记录Agent运行事件中的attemptNo。 */
    private int attemptNo;

    /** 记录Agent运行事件中的提供方。 */
    private String provider;

    /** 记录Agent运行事件中的模型。 */
    private String model;

    /** 记录Agent运行事件中的摘要。 */
    private String summary;

    /** 记录Agent运行事件中的元数据JSON。 */
    private String metadataJson;

    /** 记录Agent运行事件中的创建时间。 */
    private long createdAt;
}
