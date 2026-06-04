package com.jimuqu.solon.claw.core.enums;

/** 渠道消息处理结果，用于驱动处理状态表情回应。 */
public enum ProcessingOutcome {
    /** 回复已正常生成并投递。 */
    SUCCESS,

    /** 主链处理失败并生成错误回复。 */
    FAILURE,

    /** Agent 运行被用户取消。 */
    CANCELLED
}
