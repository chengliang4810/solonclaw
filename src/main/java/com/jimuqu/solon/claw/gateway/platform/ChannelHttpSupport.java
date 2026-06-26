package com.jimuqu.solon.claw.gateway.platform;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import okhttp3.Response;

/** 渠道 HTTP 请求的通用文本处理逻辑。 */
public final class ChannelHttpSupport {
    /** 工具类不允许创建实例。 */
    private ChannelHttpSupport() {}

    /**
     * 读取受限长度的响应正文，避免错误响应过大拖垮渠道处理。
     *
     * @param response 当前 HTTP 响应。
     * @return 响应正文；无响应体时返回空串。
     */
    public static String safeBody(Response response) throws Exception {
        if (response.body() == null) {
            return "";
        }
        return BoundedAttachmentIO.readOkHttpText(response, BoundedAttachmentIO.JSON_MAX_BYTES);
    }

    /**
     * 规范化渠道 API 域名，保留原有空值兜底并移除末尾斜杠。
     *
     * @param configured 配置中的 API 域名。
     * @param fallback 默认 API 域名。
     * @return 可直接拼接路径的 API 域名。
     */
    public static String apiDomain(String configured, String fallback) {
        String value = StrUtil.blankToDefault(configured, fallback).trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 返回首个非空白文本并裁剪首尾空白；全部为空时返回空串。
     *
     * @param values 候选文本。
     * @return 首个非空白文本。
     */
    public static String firstNonBlank(String... values) {
        return StrUtil.trim(StrUtil.blankToDefault(StrUtil.firstNonBlank(values), ""));
    }
}
