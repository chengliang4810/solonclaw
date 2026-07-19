package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.RunBusyDecision;
import java.util.Map;

/** 定义Agent运行Control的抽象契约，供不同运行时实现保持一致行为。 */
public interface AgentRunControlService {
    /** 可释放的入站运行占位，用于把空闲判断与后续运行注册原子衔接。 */
    interface IncomingReservation extends AutoCloseable {
        /** 释放尚未升级为真实 run 的占位；已经升级时调用无副作用。 */
        @Override
        void close();
    }

    /**
     * 停止当前组件并释放运行状态。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回stop结果。
     */
    AgentRunStopResult stop(String sourceKey);

    /**
     * 停止Sibling Thread运行。
     *
     * @param message 平台消息或错误消息。
     * @param ownSourceKey own来源键标识或键值。
     * @return 返回Sibling Thread运行结果。
     */
    default AgentRunStopResult stopSiblingThreadRun(GatewayMessage message, String ownSourceKey) {
        return AgentRunStopResult.none();
    }

    /**
     * 判断是否Running。
     *
     * @param sourceKey 渠道来源键。
     * @return 如果Running满足条件则返回 true，否则返回 false。
     */
    boolean isRunning(String sourceKey);

    /**
     * 仅在来源键当前空闲时原子领取入站运行占位。
     *
     * <p>默认实现表示当前运行控制器不支持原子交接；调用方不得把 {@code null} 当作空闲证明。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @return 成功领取时返回可释放占位；繁忙或不支持时返回 null。
     */
    default IncomingReservation tryReserveIncoming(String sourceKey, String sessionId) {
        return null;
    }

    /**
     * 返回当前实现是否支持原子入站占位。
     *
     * @return 支持 {@link #tryReserveIncoming(String, String)} 时返回 true。
     */
    default boolean supportsIncomingReservation() {
        return false;
    }

    /**
     * 判断是否存在Running运行。
     *
     * @return 如果Running运行满足条件则返回 true，否则返回 false。
     */
    default boolean hasRunningRuns() {
        return false;
    }

    /**
     * 执行active运行摘要相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回active运行Summary结果。
     */
    default Map<String, Object> activeRunSummary(String sourceKey) {
        return null;
    }

    /**
     * 执行running运行次数相关逻辑。
     *
     * @return 返回running运行次数结果。
     */
    default int runningRunCount() {
        return hasRunningRuns() ? 1 : 0;
    }

    /**
     * 停止全部Running运行。
     *
     * @return 返回全部Running运行结果。
     */
    default int stopAllRunningRuns() {
        return 0;
    }

    /**
     * 停止全部Running运行。
     *
     * @param resumeReason resume原因参数。
     * @return 返回全部Running运行结果。
     */
    default int stopAllRunningRuns(String resumeReason) {
        return stopAllRunningRuns();
    }

    /**
     * 执行last运行Finished时间相关逻辑。
     *
     * @return 返回last运行Finished时间结果。
     */
    default long lastRunFinishedAt() {
        return 0L;
    }

    /**
     * 执行coordinate入站消息相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param message 平台消息或错误消息。
     * @return 返回coordinate Incoming结果。
     */
    default RunBusyDecision coordinateIncoming(
            String sourceKey, String sessionId, GatewayMessage message) throws Exception {
        return RunBusyDecision.runNow("queue");
    }

    /**
     * 加入队列入站消息。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param message 平台消息或错误消息。
     * @return 返回queue Incoming结果。
     */
    default RunBusyDecision queueIncoming(
            String sourceKey, String sessionId, GatewayMessage message) throws Exception {
        return RunBusyDecision.runNow("queue");
    }

    /**
     * 执行steer入站消息相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param message 平台消息或错误消息。
     * @return 返回steer Incoming结果。
     */
    default RunBusyDecision steerIncoming(
            String sourceKey, String sessionId, GatewayMessage message) throws Exception {
        return RunBusyDecision.runNow("steer");
    }

    /**
     * 执行控制运行相关逻辑。
     *
     * @param runId 运行标识。
     * @param command 待执行或解析的命令文本。
     * @param payload 待签名或解析的载荷内容。
     * @return 返回control运行结果。
     */
    default Map<String, Object> controlRun(
            String runId, String command, Map<String, Object> payload) throws Exception {
        throw new UnsupportedOperationException("run control unavailable");
    }

    /**
     * 消费Steer指令。
     *
     * @param runId 运行标识。
     * @return 返回consume Steer Instruction结果。
     */
    default String consumeSteerInstruction(String runId) {
        return null;
    }

    /**
     * 响应运行Finished事件。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param runner runner 参数。
     */
    default void onRunFinished(
            String sourceKey,
            String sessionId,
            java.util.function.Function<
                            GatewayMessage, com.jimuqu.solon.claw.core.model.GatewayReply>
                    runner) {}
}
