package com.jimuqu.solon.claw.gateway.context;

import com.jimuqu.solon.claw.core.model.GatewayMessage;

/**
 * 网关会话上下文隔离工具。
 *
 * <p>确保每个 platform + chatId + userId 组合拥有独立的会话上下文，防止不同聊天间的工具状态、 文件状态、审批状态互相影响。隔离键与 {@link
 * GatewayMessage#sourceKey()} 保持一致。
 */
public final class GatewayContextIsolation {

    /** 创建消息网关上下文Isolation实例。 */
    private GatewayContextIsolation() {}

    /**
     * 从消息中提取会话隔离键。
     *
     * <p>隔离键格式为 {@code platform:chatId:userId}，与 {@link GatewayMessage#sourceKey()} 一致，
     * 可直接用于会话存储、工具状态、审批状态等需要按会话隔离的场景。
     *
     * @param message 入站消息
     * @return 会话隔离键，不为 null
     */
    public static String isolationKey(GatewayMessage message) {
        if (message == null) {
            return "UNKNOWN::";
        }
        return message.sourceKey();
    }

    /**
     * 判断两条消息是否属于同一隔离上下文。
     *
     * @param a 消息 A
     * @param b 消息 B
     * @return 两条消息的隔离键相同时返回 true
     */
    public static boolean sameContext(GatewayMessage a, GatewayMessage b) {
        if (a == null || b == null) {
            return false;
        }
        return isolationKey(a).equals(isolationKey(b));
    }
}
