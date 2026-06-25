package com.jimuqu.solon.claw.plugin.provider;

import java.util.List;
import java.util.Map;

/** Web 搜索后端接口。 */
public interface WebSearchProvider {
    /**
     * 执行名称相关逻辑。
     *
     * @return 返回名称结果。
     */
    String name();

    /**
     * 判断是否Available。
     *
     * @return 如果Available满足条件则返回 true，否则返回 false。
     */
    boolean isAvailable();

    /**
     * 执行搜索相关逻辑。
     *
     * @param query 查询参数。
     * @param limit 最大返回数量。
     * @return 返回搜索结果。
     */
    List<SearchResult> search(String query, int limit);

    /**
     * 从浏览器页面提取指定内容。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回extract结果。
     */
    default String extract(String url) {
        return null;
    }

    /** 表示搜索结果，携带调用方后续判断所需信息。 */
    class SearchResult {
        /** 记录搜索中的标题。 */
        private final String title;

        /** 记录搜索中的URL。 */
        private final String url;

        /** 记录搜索中的描述。 */
        private final String description;

        /**
         * 创建搜索结果实例，并注入运行所需依赖。
         *
         * @param title title 参数。
         * @param url 待校验或访问的 URL。
         * @param description 描述参数。
         */
        public SearchResult(String title, String url, String description) {
            this.title = title;
            this.url = url;
            this.description = description;
        }

        /**
         * 读取标题。
         *
         * @return 返回读取到的标题。
         */
        public String getTitle() {
            return title;
        }

        /**
         * 读取URL。
         *
         * @return 返回读取到的URL。
         */
        public String getUrl() {
            return url;
        }

        /**
         * 读取Description。
         *
         * @return 返回读取到的Description。
         */
        public String getDescription() {
            return description;
        }

        /**
         * 转换为Map。
         *
         * @return 返回转换后的Map。
         */
        public Map<String, String> toMap() {
            Map<String, String> map = new java.util.LinkedHashMap<String, String>();
            map.put("title", title);
            map.put("url", url);
            map.put("description", description);
            return map;
        }
    }
}
