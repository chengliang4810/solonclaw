package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Dashboard/渠道对长任务发出的控制命令。 */
@Getter
@Setter
@NoArgsConstructor
public class RunControlCommand {
    /** 记录运行控制命令中的命令标识。 */
    private String commandId;

    /** 记录运行控制命令中的运行标识。 */
    private String runId;

    /** 记录运行控制命令中的来源键。 */
    private String sourceKey;

    /** 记录运行控制命令中的命令。 */
    private String command;

    /** 记录运行控制命令中的载荷JSON。 */
    private String payloadJson;

    /** 记录运行控制命令中的状态。 */
    private String status;

    /** 记录运行控制命令中的创建时间。 */
    private long createdAt;

    /** 记录运行控制命令中的handled时间。 */
    private long handledAt;
}
