package com.jimuqu.solon.claw.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证基础值判断工具的安全输入边界语义。 */
class BasicValueSupportTest {
    /** 控制字符应被识别，普通文本和 null 不应误报。 */
    @Test
    void shouldDetectControlCharacters() {
        assertTrue(BasicValueSupport.containsControlCharacter("safe\u0000path"));
        assertFalse(BasicValueSupport.containsControlCharacter("safe/path"));
        assertFalse(BasicValueSupport.containsControlCharacter(null));
    }

    /** 配置对象树清理应丢弃 null 键、递归处理子 Map 和 List。 */
    @Test
    void shouldSanitizeNestedConfigTree() {
        Map<Object, Object> child = new LinkedHashMap<Object, Object>();
        child.put(null, "ignored");
        child.put("port", Integer.valueOf(8080));
        Map<Object, Object> root = new LinkedHashMap<Object, Object>();
        root.put("server", child);
        root.put("items", Arrays.asList(child, "plain"));

        Map<String, Object> sanitized = BasicValueSupport.sanitizeMap(root);

        assertEquals(Integer.valueOf(8080), ((Map<?, ?>) sanitized.get("server")).get("port"));
        assertFalse(((Map<?, ?>) sanitized.get("server")).containsKey(null));
        assertEquals("plain", ((java.util.List<?>) sanitized.get("items")).get(1));
    }
}
