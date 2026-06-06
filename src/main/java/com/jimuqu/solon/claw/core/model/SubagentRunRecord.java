package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 父 run 派生出的子 Agent 运行记录。 */
@Getter
@Setter
@NoArgsConstructor
public class SubagentRunRecord {
    /** 记录子Agent运行中的子Agent标识。 */
    private String subagentId;

    /** 记录子Agent运行中的parent运行标识。 */
    private String parentRunId;

    /** 记录子Agent运行中的child运行标识。 */
    private String childRunId;

    /** 记录子Agent运行中的parent来源键。 */
    private String parentSourceKey;

    /** 记录子Agent运行中的child来源键。 */
    private String childSourceKey;

    /** 记录子Agent运行中的会话标识。 */
    private String sessionId;

    /** 记录子Agent运行中的名称。 */
    private String name;

    /** 记录子Agent运行中的目标预览。 */
    private String goalPreview;

    /** 记录子Agent运行中的状态。 */
    private String status;

    /** 是否启用active。 */
    private boolean active;

    /** 是否启用interruptRequested。 */
    private boolean interruptRequested;

    /** 记录子Agent运行中的depth。 */
    private int depth;

    /** 记录子Agent运行中的任务索引。 */
    private int taskIndex;

    /** 记录子Agent运行中的输出TailJSON。 */
    private String outputTailJson;

    /** 记录子Agent运行中的错误。 */
    private String error;

    /** 记录子Agent运行中的started时间。 */
    private long startedAt;

    /** 记录子Agent运行中的finished时间。 */
    private long finishedAt;

    /** 记录子Agent运行中的心跳时间。 */
    private long heartbeatAt;
}
