package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.enums.ProcessingOutcome;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;

/** 渠道适配器统一接口。 */
public interface ChannelAdapter {
    /** 返回适配器所属平台。 */
    PlatformType platform();

    /** 当前适配器是否启用。 */
    boolean isEnabled();

    /** 建立连接。 */
    boolean connect();

    /** 关闭连接。 */
    void disconnect();

    /** 当前是否已连接。 */
    boolean isConnected();

    /**
     * 当前渠道是否能提供真实传输连接健康信号。
     *
     * @return 能可靠判断 {@link #isConnected()} 时返回 true；SDK 不暴露健康状态时返回 false。
     */
    default boolean isConnectionHealthObservable() {
        return true;
    }

    /** 返回适配器运行详情。 */
    String detail();

    /** 发送一条消息。 */
    void send(DeliveryRequest request) throws Exception;

    /**
     * 在入站总账持久化之后下载并解密原始附件引用。
     *
     * <p>默认渠道没有需要后置处理的附件；实现抛出异常时，调用方必须保留 pending receipt 供后续恢复。
     *
     * @param message 已持久化、尚未领取处理权的入站消息。
     */
    default void hydrateInboundAttachments(GatewayMessage message) throws Exception {
        // 默认渠道没有平台原始附件引用需要水化。
    }

    /** 注册入站消息处理器。 */
    default void setInboundMessageHandler(InboundMessageHandler inboundMessageHandler) {
        // 留给具体渠道按需实现。
    }

    /** 注册运行期断线后的重连请求处理器。 */
    default void setReconnectHandler(Runnable reconnectHandler) {
        // 留给具备长连接生命周期的渠道按需实现。
    }

    /** 注册渠道协议真正可用后的通知处理器。 */
    default void setConnectionReadyHandler(Runnable connectionReadyHandler) {
        // 留给异步握手或 SDK 自恢复渠道按需实现。
    }

    /** 注册渠道协议进入暂不可用状态但由 SDK 自恢复时的通知处理器。 */
    default void setConnectionUnavailableHandler(Runnable connectionUnavailableHandler) {
        // 留给具备 SDK 内部重连能力的渠道按需实现。
    }

    /** 标记渠道已开始处理入站消息，用于添加“处理中”表情回应。 */
    default void onProcessingStart(GatewayMessage message) throws Exception {
        // 默认渠道不支持处理状态表情回应。
    }

    /** 标记渠道已结束处理入站消息，用于清理或切换处理状态表情回应。 */
    default void onProcessingComplete(GatewayMessage message, ProcessingOutcome outcome)
            throws Exception {
        // 默认渠道不支持处理状态表情回应。
    }

    /** 返回渠道状态快照，供 dashboard / doctor 聚合。 */
    default ChannelStatus statusSnapshot() {
        return new ChannelStatus(platform(), isEnabled(), isConnected(), detail());
    }
}
