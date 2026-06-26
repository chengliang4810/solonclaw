package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;

/** 提供基础 URL 文本规范化的复用逻辑。 */
public final class BaseUrlSupport {
    /** 工具类不允许创建实例。 */
    private BaseUrlSupport() {}

    /**
     * 去掉基础 URL 末尾的斜杠，避免拼接接口路径时产生重复分隔符。
     *
     * @param baseUrl 原始基础 URL。
     * @return 去除首尾空白和末尾斜杠后的基础 URL。
     */
    public static String stripTrailingSlashes(String baseUrl) {
        String value = StrUtil.nullToEmpty(baseUrl).trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
