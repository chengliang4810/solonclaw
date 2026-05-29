package com.jimuqu.solon.claw.plugin.provider;

import java.util.List;
import java.util.Map;

/** Web 搜索后端接口。 */
public interface WebSearchProvider {
    String name();

    boolean isAvailable();

    List<SearchResult> search(String query, int limit);

    default String extract(String url) { return null; }

    class SearchResult {
        private final String title;
        private final String url;
        private final String description;

        public SearchResult(String title, String url, String description) {
            this.title = title;
            this.url = url;
            this.description = description;
        }

        public String getTitle() { return title; }
        public String getUrl() { return url; }
        public String getDescription() { return description; }

        public Map<String, String> toMap() {
            return Map.of("title", title, "url", url, "description", description);
        }
    }
}
