package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 维护国内渠道扫码 setup ticket 的平台无关生命周期字段。 */
class QrSetupTicketState {
    /** 扫码 setup 接口统一使用本地时区偏移格式输出时间。 */
    private static final DateTimeFormatter ISO_OFFSET_SECONDS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
                    .withZone(ZoneId.systemDefault());

    /** ticket 标识，用于前端轮询状态。 */
    String ticket;

    /** 当前 ticket 状态。 */
    String status;

    /** 当前状态对应的用户可读消息。 */
    String message;

    /** 失败时的稳定错误码。 */
    String errorCode;

    /** 失败时的脱敏错误消息。 */
    String errorMessage;

    /** ticket 创建时间，毫秒时间戳。 */
    long createdAt;

    /** ticket 最近更新时间，毫秒时间戳。 */
    long updatedAt;

    /** ticket 过期时间，毫秒时间戳。 */
    long expiresAt;

    /**
     * 创建扫码 setup ticket 状态。
     *
     * @param timeoutMillis ticket 生命周期毫秒数。
     */
    QrSetupTicketState(long timeoutMillis) {
        this.ticket = IdUtil.fastSimpleUUID();
        this.status = "initializing";
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
        this.expiresAt = this.createdAt + Math.max(0L, timeoutMillis);
    }

    /**
     * 标记普通进度状态。
     *
     * @param status 状态值。
     * @param message 用户可读消息。
     */
    void mark(String status, String message) {
        this.status = status;
        this.message = message;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 标记失败状态并脱敏错误消息。
     *
     * @param code 稳定错误码。
     * @param message 原始错误消息。
     */
    void fail(String code, String message) {
        String safe = SecretRedactor.redact(StrUtil.nullToEmpty(message), 1000);
        this.status = "failed";
        this.errorCode = code;
        this.errorMessage = safe;
        this.message = safe;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 转换时间戳为接口输出格式。
     *
     * @param epochMillis 毫秒时间戳。
     * @return 时间字符串，非正数返回 null。
     */
    String isoTime(long epochMillis) {
        if (epochMillis <= 0) {
            return null;
        }
        return ISO_OFFSET_SECONDS_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    /** 生成扫码 setup 响应中的平台无关基础字段。 */
    Map<String, Object> baseMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ticket", ticket);
        result.put("status", status);
        result.put("message", message);
        result.put("error_code", errorCode);
        result.put("error_message", errorMessage);
        result.put("created_at", isoTime(createdAt));
        result.put("updated_at", isoTime(updatedAt));
        result.put("expires_at", isoTime(expiresAt));
        return result;
    }
}
