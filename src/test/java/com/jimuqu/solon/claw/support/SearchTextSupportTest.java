package com.jimuqu.solon.claw.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 验证搜索词元字符分类规则。 */
class SearchTextSupportTest {
    /** 应区分 ASCII、中文、其他字母数字和分隔符。 */
    @Test
    void shouldClassifySearchCharacters() {
        assertEquals(1, SearchTextSupport.searchCharKind('A'));
        assertEquals(1, SearchTextSupport.searchCharKind('_'));
        assertEquals(2, SearchTextSupport.searchCharKind('中'));
        assertEquals(3, SearchTextSupport.searchCharKind('é'));
        assertEquals(0, SearchTextSupport.searchCharKind('-'));
    }
}
