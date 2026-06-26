package com.jimuqu.solon.claw.gateway.platform;

import java.util.concurrent.ExecutorService;
import okhttp3.WebSocket;

/** 渠道连接资源清理工具，统一处理 websocket 和回调线程池的关闭语义。 */
public final class ChannelConnectionSupport {
    /** 正常关闭 websocket 的状态码。 */
    private static final int NORMAL_CLOSE_CODE = 1000;

    /** 正常关闭 websocket 的原因。 */
    private static final String NORMAL_CLOSE_REASON = "normal";

    /** 工具类不允许创建实例。 */
    private ChannelConnectionSupport() {}

    /**
     * 关闭渠道 websocket 并停止对应回调执行器。
     *
     * @param webSocket 当前渠道持有的 websocket。
     * @param callbackExecutor 当前渠道回调执行器。
     */
    public static void disconnect(WebSocket webSocket, ExecutorService callbackExecutor) {
        if (webSocket != null) {
            webSocket.close(NORMAL_CLOSE_CODE, NORMAL_CLOSE_REASON);
        }
        if (callbackExecutor != null) {
            callbackExecutor.shutdownNow();
        }
    }
}
