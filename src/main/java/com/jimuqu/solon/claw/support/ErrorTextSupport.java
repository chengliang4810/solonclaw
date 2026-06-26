package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;

/** 错误文本脱敏工具，统一把异常转换为可展示但不泄漏敏感信息的摘要。 */
public final class ErrorTextSupport {
    /** 工具类不需要实例化。 */
    private ErrorTextSupport() {}

    /**
     * 将异常转换为脱敏错误摘要。
     *
     * @param error 捕获到的异常。
     * @return 可写入日志、状态或接口响应的错误摘要。
     */
    public static String safeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        String value = StrUtil.isBlank(message) ? error.getClass().getSimpleName() : message;
        return SecretRedactor.redact(value, 1000);
    }
}
