package com.jimuqu.solon.claw.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证基础值判断工具的安全输入边界语义。 */
class BasicValueSupportTest {
    /** 控制字符应被识别，普通文本和 null 不应误报。 */
    @Test
    void shouldDetectControlCharacters() {
        assertTrue(BasicValueSupport.containsControlCharacter("safe\u0000path"));
        assertFalse(BasicValueSupport.containsControlCharacter("safe/path"));
        assertFalse(BasicValueSupport.containsControlCharacter(null));
    }
}
