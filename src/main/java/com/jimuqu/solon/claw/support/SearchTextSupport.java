package com.jimuqu.solon.claw.support;

/** 搜索文本处理工具，集中维护中英文混合查询的字符分类规则。 */
public final class SearchTextSupport {
    /** 工具类不保存状态，禁止创建实例。 */
    private SearchTextSupport() {}

    /**
     * 区分 ASCII 词、中文词和其他字母数字，便于中英文相邻时拆开。
     *
     * @param ch 待分类字符。
     * @return 1 表示 ASCII 词，2 表示中文词，3 表示其他字母数字，0 表示分隔符。
     */
    public static int searchCharKind(char ch) {
        if ((ch >= 'A' && ch <= 'Z')
                || (ch >= 'a' && ch <= 'z')
                || (ch >= '0' && ch <= '9')
                || ch == '_') {
            return 1;
        }
        if (ch >= '\u4e00' && ch <= '\u9fff') {
            return 2;
        }
        if (Character.isLetterOrDigit(ch)) {
            return 3;
        }
        return 0;
    }
}
