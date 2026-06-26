package com.jimuqu.solon.claw.tool.runtime;

/** 承载网站Rule相关状态和辅助逻辑。 */
final class SecurityWebsiteRule {
    /** 记录网站Rule中的URL。 */
    final String url;

    /** 记录网站Rule中的rule。 */
    final String rule;

    /**
     * 创建Website Rule实例，并注入运行所需依赖。
     *
     * @param url 待校验或访问的 URL。
     * @param rule rule 参数。
     */
    SecurityWebsiteRule(String url, String rule) {
        this.url = url;
        this.rule = rule;
    }
}
