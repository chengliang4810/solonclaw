package com.jimuqu.solon.claw.web;

/** Profile 独立网关无法处理 Dashboard 请求时返回的稳定异常。 */
public class DashboardProfileGatewayException extends IllegalStateException {
    /** 建议返回给调用方的 HTTP 状态码。 */
    private final int status;

    /** 不包含 Profile 凭据的稳定错误码。 */
    private final String code;

    /**
     * 创建 Profile 网关异常。
     *
     * @param status HTTP 状态码。
     * @param code 稳定错误码。
     * @param message 可安全展示的错误说明。
     */
    public DashboardProfileGatewayException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * 创建带底层原因的 Profile 网关异常。
     *
     * @param status HTTP 状态码。
     * @param code 稳定错误码。
     * @param message 可安全展示的错误说明。
     * @param cause 底层网络或解析异常。
     */
    public DashboardProfileGatewayException(
            int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /**
     * @return 建议返回的 HTTP 状态码。
     */
    public int getStatus() {
        return status;
    }

    /**
     * @return 稳定错误码。
     */
    public String getCode() {
        return code;
    }
}
