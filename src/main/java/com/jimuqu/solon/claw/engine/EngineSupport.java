package com.jimuqu.solon.claw.engine;

import com.jimuqu.solon.claw.support.ErrorTextSupport;

/** engine 包内共享的通用辅助方法。 */
public final class EngineSupport {
    /** 工具类只提供静态方法，不允许实例化。 */
    private EngineSupport() {}

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param error 错误参数。
     * @return 返回safe Error结果。
     */
    public static String safeError(Throwable error) {
        return ErrorTextSupport.safeError(error);
    }
}
