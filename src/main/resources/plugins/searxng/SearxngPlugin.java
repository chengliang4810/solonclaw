import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.plugin.AgentPlugin;
import com.jimuqu.solon.claw.plugin.AgentPluginContext;
import com.jimuqu.solon.claw.plugin.provider.WebSearchProvider;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SearxngPlugin implements AgentPlugin {

    private static final Logger log = LoggerFactory.getLogger(SearxngPlugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        ctx.registerWebSearchProvider(new SearxngSearchProvider(ctx));
    }

    static class SearxngSearchProvider implements WebSearchProvider {
        private final AgentPluginContext ctx;

        SearxngSearchProvider(AgentPluginContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public String name() {
            return "searxng";
        }

        @Override
        public boolean isAvailable() {
            String url = ctx.getEnv("SEARXNG_URL");
            return StrUtil.isNotBlank(url);
        }

        @Override
        public List<SearchResult> search(String query, int limit) {
            String baseUrl = ctx.getEnv("SEARXNG_URL");
            if (StrUtil.isBlank(baseUrl)) {
                baseUrl = "http://localhost:8080";
            }
            try {
                HttpResponse response = HttpRequest.get(baseUrl + "/search")
                        .form("q", query)
                        .form("format", "json")
                        .form("pageno", "1")
                        .timeout(15000)
                        .execute();

                if (!response.isOk()) {
                    log.warn("SearXNG search failed with status: {}", response.getStatus());
                    return Collections.emptyList();
                }

                ONode root = ONode.ofJson(response.body());
                ONode results = root.get("results");
                List<SearchResult> list = new ArrayList<>();
                for (ONode item : results.ary()) {
                    if (list.size() >= limit) {
                        break;
                    }
                    String title = item.get("title").getString();
                    String url = item.get("url").getString();
                    String content = item.get("content").getString();
                    list.add(new SearchResult(
                            title != null ? title : "",
                            url,
                            content != null ? content : ""));
                }
                return list;
            } catch (Exception e) {
                log.warn("SearXNG search error: {}", e.getMessage());
                return Collections.emptyList();
            }
        }
    }
}
