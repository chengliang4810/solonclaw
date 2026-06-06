package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.LinkedHashMap;
import java.util.Map;

/** 承载控制台响应相关状态和辅助逻辑。 */
public final class DashboardResponse {
    /** 创建控制台响应实例。 */
    private DashboardResponse() {}

    /**
     * 构造成功结果。
     *
     * @param data 数据参数。
     * @return 返回ok结果。
     */
    public static Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("data", data == null ? new LinkedHashMap<String, Object>() : data);
        return result;
    }

    /**
     * 执行错误相关逻辑。
     *
     * @param code code 参数。
     * @param message 平台消息或错误消息。
     * @return 返回error结果。
     */
    public static Map<String, Object> error(String code, String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", false);
        result.put("code", code == null ? "ERROR" : code);
        result.put("error", message == null ? "" : SecretRedactor.redact(message, 1000));
        return result;
    }
}
