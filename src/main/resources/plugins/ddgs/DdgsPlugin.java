import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.plugin.AgentPlugin;
import com.jimuqu.solon.claw.plugin.AgentPluginContext;
import com.jimuqu.solon.claw.plugin.provider.WebSearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DdgsPlugin implements AgentPlugin {

    private static final Logger log = LoggerFactory.getLogger(DdgsPlugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        ctx.registerWebSearchProvider(new DdgsSearchProvider());
    }

    static class DdgsSearchProvider implements WebSearchProvider {

        private static final Pattern LINK_PATTERN = Pattern.compile(
                "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>([^<]*)</a>");
        private static final Pattern SNIPPET_PATTERN = Pattern.compile(
                "<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);

        @Override
        public String name() {
            return "ddgs";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public List<SearchResult> search(String query, int limit) {
            try {
                HttpResponse response = HttpRequest.post("https://html.duckduckgo.com/html/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .form("q", query)
                        .timeout(15000)
                        .execute();

                if (!response.isOk()) {
                    log.warn("DuckDuckGo search failed with status: {}", response.getStatus());
                    return Collections.emptyList();
                }

                String html = response.body();
                List<SearchResult> list = new ArrayList<>();

                Matcher linkMatcher = LINK_PATTERN.matcher(html);
                Matcher snippetMatcher = SNIPPET_PATTERN.matcher(html);

                while (linkMatcher.find() && list.size() < limit) {
                    String url = linkMatcher.group(1);
                    String title = linkMatcher.group(2).trim();
                    String description = "";
                    if (snippetMatcher.find()) {
                        description = snippetMatcher.group(1)
                                .replaceAll("<[^>]+>", "")
                                .trim();
                    }
                    if (StrUtil.isNotBlank(url) && StrUtil.isNotBlank(title)) {
                        list.add(new SearchResult(title, url, description));
                    }
                }
                return list;
            } catch (Exception e) {
                log.warn("DuckDuckGo search error: {}", e.getMessage());
                return Collections.emptyList();
            }
        }
    }
}
