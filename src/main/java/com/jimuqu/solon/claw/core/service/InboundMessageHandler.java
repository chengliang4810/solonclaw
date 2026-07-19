package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;

/** 渠道入站消息回调接口。 */
public interface InboundMessageHandler {
    /**
     * 在渠道确认或异步排队前同步持久化原始入站消息。
     *
     * @param message 待准入的原始渠道消息。
     * @return 成功准入返回 true；稳定幂等键已存在时返回 false。
     */
    default boolean admit(GatewayMessage message) throws Exception {
        return true;
    }

    /** 执行单条渠道入站消息相关逻辑。 */
    void handle(GatewayMessage message) throws Exception;

    /**
     * 处理已经同步准入的渠道消息；默认兼容未启用两阶段准入的处理器。
     *
     * @param message 已携带原始入站总账标识的消息。
     */
    default void handleAdmitted(GatewayMessage message) throws Exception {
        handle(message);
    }

    /**
     * 捕获本次渠道连接之前已落库消息的稳定恢复水位；默认处理器没有持久化消息。
     *
     * @return 当前稳定插入序号上界；没有待恢复总账时返回 0。
     */
    default long capturePendingRecoveryWatermark() throws Exception {
        return 0L;
    }

    /**
     * 恢复指定稳定水位内已准入但尚未被消费的消息；默认处理器无需额外动作。
     *
     * @param maxSequence 当前渠道连接建立前捕获的稳定插入序号上界。
     */
    default void recoverPendingThrough(long maxSequence) throws Exception {}

    /**
     * 恢复指定渠道在稳定水位内已准入但尚未消费的消息。
     *
     * <p>默认委托旧的全 Profile 恢复入口，兼容尚未实现按平台筛选的自定义处理器。
     *
     * @param platform 当前恢复连接的平台。
     * @param maxSequence 当前渠道连接前捕获的稳定插入序号上界。
     */
    default void recoverPendingThrough(PlatformType platform, long maxSequence) throws Exception {
        recoverPendingThrough(maxSequence);
    }
}
