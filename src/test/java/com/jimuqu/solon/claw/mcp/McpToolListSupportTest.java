package com.jimuqu.solon.claw.mcp;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证 MCP 工具清单数量、名称提取和差异计算。 */
class McpToolListSupportTest {
    /** 工具名称应按 prefixed_name 优先、去重并排序。 */
    @Test
    void shouldExtractSortedUniqueToolNames() {
        String tools =
                "[{\"name\":\"b\"},{\"prefixed_name\":\"a\",\"name\":\"ignored\"},{\"name\":\"b\"}]";
        assertEquals(Arrays.asList("a", "b"), McpToolListSupport.toolNames(tools));
        assertEquals(3, McpToolListSupport.countTools(tools));
    }

    /** 非数组非空文本按一个工具计数，空文本按零个工具计数。 */
    @Test
    void shouldCountFallbackToolText() {
        assertEquals(1, McpToolListSupport.countTools("{bad json"));
        assertEquals(0, McpToolListSupport.countTools(" "));
    }

    /** 差异计算应忽略空白值并保持左侧顺序。 */
    @Test
    void shouldCalculateDifference() {
        List<String> diff =
                McpToolListSupport.difference(
                        Arrays.asList("a", "b", "", "a", "c"),
                        Collections.singletonList("b"));
        assertEquals(Arrays.asList("a", "c"), diff);
    }

    /** firstPresent 应返回第一个存在的键，未命中返回 null。 */
    @Test
    void shouldReturnFirstPresentValue() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("second", "value");
        assertEquals("value", McpToolListSupport.firstPresent(map, "first", "second"));
        assertNull(McpToolListSupport.firstPresent(map, "missing"));
    }
}
