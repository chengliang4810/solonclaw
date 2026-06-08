package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.model.GatewayMessage;

/** 渠道入站消息回调接口。 */
public interface InboundMessageHandler {
    /** 执行单条渠道入站消息相关逻辑。 */
    void handle(GatewayMessage message) throws Exception;
}
