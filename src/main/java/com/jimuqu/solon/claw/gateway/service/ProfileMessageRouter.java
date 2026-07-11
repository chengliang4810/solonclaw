package com.jimuqu.solon.claw.gateway.service;

import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;

/** 把显式指定命名 Profile 的入站消息转交给对应独立运行时。 */
public interface ProfileMessageRouter {
    /**
     * 路由单条消息。
     *
     * @param profile 已规范化的命名 Profile。
     * @param message 待处理消息。
     * @return 目标 Profile 处理结果。
     * @throws Exception Profile 不存在、未承载或目标处理失败。
     */
    GatewayReply route(String profile, GatewayMessage message) throws Exception;
}
