package com.jimuqu.solon.claw.web.profile;

/** Dashboard 请求指定的 Profile 不存在时抛出的明确异常。 */
public class DashboardProfileNotFoundException extends IllegalArgumentException {
    /**
     * 创建 Profile 不存在异常。
     *
     * @param message 可安全返回给 Dashboard 的错误说明。
     */
    public DashboardProfileNotFoundException(String message) {
        super(message);
    }

    /**
     * 创建带底层原因的 Profile 不存在异常。
     *
     * @param message 可安全返回给 Dashboard 的错误说明。
     * @param cause 底层路径解析异常。
     */
    public DashboardProfileNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
