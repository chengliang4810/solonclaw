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

    /** 返回适配器运行详情。 */
    String detail();

    /** 发送一条消息。 */
    void send(DeliveryRequest request) throws Exception;

    /** 注册入站消息处理器。 */
    default void setInboundMessageHandler(InboundMessageHandler inboundMessageHandler) {
        // 留给具体渠道按需实现。
    }

    /** 注册运行期断线后的重连请求处理器。 */
    default void setReconnectHandler(Runnable reconnectHandler) {
        // 留给具备长连接生命周期的渠道按需实现。
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
