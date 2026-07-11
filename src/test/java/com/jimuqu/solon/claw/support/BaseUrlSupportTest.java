package com.jimuqu.solon.claw.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 验证基础 URL 末尾斜杠规范化。 */
class BaseUrlSupportTest {
    /** 应去除首尾空白和全部末尾斜杠。 */
    @Test
    void shouldStripTrailingSlashes() {
        assertEquals(
                "https://example.com/api",
                BaseUrlSupport.stripTrailingSlashes(" https://example.com/api/// "));
    }

    /** 空值按空字符串处理，保持调用方原有空值语义。 */
    @Test
    void shouldTreatNullAsEmpty() {
        assertEquals("", BaseUrlSupport.stripTrailingSlashes(null));
    }
}
