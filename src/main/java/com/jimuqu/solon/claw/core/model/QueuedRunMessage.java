package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Busy queue 中等待执行的用户输入。 */
@Getter
@Setter
@NoArgsConstructor
public class QueuedRunMessage {
    /** 记录排队运行消息中的队列标识。 */
    private String queueId;

    /** 记录排队运行消息中的运行标识。 */
    private String runId;

    /** 记录排队运行消息中的会话标识。 */
    private String sessionId;

    /** 记录排队运行消息中的来源键。 */
    private String sourceKey;

    /** 记录排队运行消息中的消息文本。 */
    private String messageText;

    /** 记录排队运行消息中的消息JSON。 */
    private String messageJson;

    /** 记录排队运行消息中的状态。 */
    private String status;

    /** 记录排队运行消息中的busy策略。 */
    private String busyPolicy;

    /** 记录排队运行消息中的创建时间。 */
    private long createdAt;

    /** 记录排队运行消息中的started时间。 */
    private long startedAt;

    /** 记录排队运行消息中的finished时间。 */
    private long finishedAt;

    /** 记录排队运行消息中的错误。 */
    private String error;
}
