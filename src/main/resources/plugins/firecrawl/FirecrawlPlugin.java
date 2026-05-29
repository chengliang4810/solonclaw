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

public class FirecrawlPlugin implements AgentPlugin {

    private static final Logger log = LoggerFactory.getLogger(FirecrawlPlugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        ctx.registerWebSearchProvider(new FirecrawlSearchProvider(ctx));
    }

    static class FirecrawlSearchProvider implements WebSearchProvider {
        private final AgentPluginContext ctx;

        FirecrawlSearchProvider(AgentPluginContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public String name() {
            return "firecrawl";
        }

        @Override
        public boolean isAvailable() {
            return StrUtil.isNotBlank(ctx.getEnv("FIRECRAWL_API_KEY"));
        }

        @Override
        public List<SearchResult> search(String query, int limit) {
            String apiKey = ctx.getEnv("FIRECRAWL_API_KEY");
            if (StrUtil.isBlank(apiKey)) {
                return Collections.emptyList();
            }
            try {
                ONode body = new ONode();
                body.set("query", query);
                body.set("limit", limit);

                HttpResponse response = HttpRequest.post("https://api.firecrawl.dev/v1/search")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .body(body.toJson())
                        .timeout(15000)
                        .execute();

                if (!response.isOk()) {
                    log.warn("Firecrawl search failed with status: {}", response.getStatus());
                    return Collections.emptyList();
                }

                ONode root = ONode.ofJson(response.body());
                ONode results = root.get("data");
                List<SearchResult> list = new ArrayList<>();
                for (ONode item : results.ary()) {
                    String title = item.get("title").getString();
                    String url = item.get("url").getString();
                    String description = item.get("description").getString();
                    list.add(new SearchResult(
                            title != null ? title : "",
                            url,
                            description != null ? description : ""));
                }
                return list;
            } catch (Exception e) {
                log.warn("Firecrawl search error: {}", e.getMessage());
                return Collections.emptyList();
            }
        }

        @Override
        public String extract(String url) {
            String apiKey = ctx.getEnv("FIRECRAWL_API_KEY");
            if (StrUtil.isBlank(apiKey)) {
                return null;
            }
            try {
                ONode body = new ONode();
                body.set("url", url);

                HttpResponse response = HttpRequest.post("https://api.firecrawl.dev/v1/scrape")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .body(body.toJson())
                        .timeout(30000)
                        .execute();

                if (!response.isOk()) {
                    log.warn("Firecrawl scrape failed with status: {}", response.getStatus());
                    return null;
                }

                ONode root = ONode.ofJson(response.body());
                return root.get("data").get("markdown").getString();
            } catch (Exception e) {
                log.warn("Firecrawl scrape error: {}", e.getMessage());
                return null;
            }
        }
    }
}
