package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;

/** Agent 主循环调度接口。 */
public interface ConversationOrchestrator {
    /** 执行普通入站消息相关逻辑。 */
    GatewayReply handleIncoming(GatewayMessage message) throws Exception;

    /** 执行普通入站消息，并向事件接收器输出运行过程相关逻辑。 */
    default GatewayReply handleIncoming(GatewayMessage message, ConversationEventSink eventSink)
            throws Exception {
        return handleIncoming(message);
    }

    /** 执行定时任务触发的消息相关逻辑。 */
    GatewayReply runScheduled(GatewayMessage syntheticMessage) throws Exception;

    /** 执行定时任务触发的消息，并向事件接收器输出运行过程相关逻辑。 */
    default GatewayReply runScheduled(
            GatewayMessage syntheticMessage, ConversationEventSink eventSink) throws Exception {
        return runScheduled(syntheticMessage);
    }

    /** 恢复当前来源键下因危险命令审批而挂起的会话。 */
    GatewayReply resumePending(String sourceKey) throws Exception;

    /** 按会话 ID 恢复指定 pending 会话。 */
    default GatewayReply resumePending(String sourceKey, String sessionId) throws Exception {
        return resumePending(sourceKey);
    }

    /** 恢复当前来源键下因危险命令审批而挂起的会话，并输出运行过程。 */
    default GatewayReply resumePending(String sourceKey, ConversationEventSink eventSink)
            throws Exception {
        return resumePending(sourceKey);
    }

    /** 按会话 ID 恢复指定 pending 会话，并输出运行过程。 */
    default GatewayReply resumePending(
            String sourceKey, String sessionId, ConversationEventSink eventSink) throws Exception {
        return resumePending(sourceKey, eventSink);
    }
}
