package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** stale/crash/max-step 等恢复线索。 */
@Getter
@Setter
@NoArgsConstructor
public class RunRecoveryRecord {
    /** 记录运行恢复中的恢复标识。 */
    private String recoveryId;

    /** 记录运行恢复中的运行标识。 */
    private String runId;

    /** 记录运行恢复中的会话标识。 */
    private String sessionId;

    /** 记录运行恢复中的来源键。 */
    private String sourceKey;

    /** 记录运行恢复中的恢复类型。 */
    private String recoveryType;

    /** 记录运行恢复中的状态。 */
    private String status;

    /** 记录运行恢复中的摘要。 */
    private String summary;

    /** 记录运行恢复中的载荷JSON。 */
    private String payloadJson;

    /** 记录运行恢复中的创建时间。 */
    private long createdAt;

    /** 记录运行恢复中的resolved时间。 */
    private long resolvedAt;
}
