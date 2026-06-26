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
        String value = StrUtil.isBlank(message) ? error.getClass().getSimpleName() : message.trim();
        return SecretRedactor.redact(value, 1000);
    }

    /**
     * 将异常转换为包含异常类型的脱敏摘要，适合 debug 日志定位降级原因。
     *
     * @param error 捕获到的异常。
     * @return 异常类型与脱敏消息摘要。
     */
    public static String summaryWithType(Throwable error) {
        if (error == null) {
            return "";
        }
        String message =
                SecretRedactor.redact(
                        StrUtil.blankToDefault(error.getMessage(), error.getClass().getName()),
                        500);
        return error.getClass().getSimpleName() + ": " + message;
    }

    /**
     * 只返回异常类型名称，适合不能输出异常消息正文的内部 debug 日志。
     *
     * @param error 捕获到的异常。
     * @return 异常简单类名，缺失时返回unknown。
     */
    public static String typeOnly(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        return StrUtil.blankToDefault(error.getClass().getSimpleName(), error.getClass().getName());
    }
}
