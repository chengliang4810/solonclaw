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

public class TavilyPlugin implements AgentPlugin {

    private static final Logger log = LoggerFactory.getLogger(TavilyPlugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        ctx.registerWebSearchProvider(new TavilySearchProvider(ctx));
    }

    static class TavilySearchProvider implements WebSearchProvider {
        private final AgentPluginContext ctx;

        TavilySearchProvider(AgentPluginContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public String name() {
            return "tavily";
        }

        @Override
        public boolean isAvailable() {
            return StrUtil.isNotBlank(ctx.getEnv("TAVILY_API_KEY"));
        }

        @Override
        public List<SearchResult> search(String query, int limit) {
            String apiKey = ctx.getEnv("TAVILY_API_KEY");
            if (StrUtil.isBlank(apiKey)) {
                return Collections.emptyList();
            }
            try {
                ONode body = new ONode();
                body.set("api_key", apiKey);
                body.set("query", query);
                body.set("max_results", limit);
                body.set("include_answer", false);

                HttpResponse response = HttpRequest.post("https://api.tavily.com/search")
                        .header("Content-Type", "application/json")
                        .body(body.toJson())
                        .timeout(15000)
                        .execute();

                if (!response.isOk()) {
                    log.warn("Tavily search failed with status: {}", response.getStatus());
                    return Collections.emptyList();
                }

                ONode root = ONode.ofJson(response.body());
                ONode results = root.get("results");
                List<SearchResult> list = new ArrayList<>();
                for (ONode item : results.ary()) {
                    String title = item.get("title").getString();
                    String url = item.get("url").getString();
                    String content = item.get("content").getString();
                    list.add(new SearchResult(title, url, content != null ? content : ""));
                }
                return list;
            } catch (Exception e) {
                log.warn("Tavily search error: {}", e.getMessage());
                return Collections.emptyList();
            }
        }
    }
}
