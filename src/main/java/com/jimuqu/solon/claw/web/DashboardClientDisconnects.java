package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import java.util.Locale;

/** Dashboard HTTP 输出阶段的客户端断连识别工具。 */
final class DashboardClientDisconnects {
    /** 客户端断连异常的常见消息片段。 */
    private static final String[] DISCONNECT_MESSAGES =
            new String[] {
                "writebuffer has closed",
                "outputstream has closed",
                "broken pipe",
                "connection reset",
                "client abort"
            };

    /** 工具类不允许实例化。 */
    private DashboardClientDisconnects() {}

    /**
     * 判断异常链是否表示浏览器刷新、关闭页面或网络中断导致的客户端断连。
     *
     * @param error HTTP 输出阶段捕获到的异常。
     * @return 如果异常链匹配客户端断连特征则返回 true。
     */
    static boolean isClientDisconnected(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = StrUtil.nullToEmpty(current.getMessage()).toLowerCase(Locale.ROOT);
            for (String candidate : DISCONNECT_MESSAGES) {
                if (message.contains(candidate)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
