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

public class BraveFreePlugin implements AgentPlugin {

    private static final Logger log = LoggerFactory.getLogger(BraveFreePlugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        ctx.registerWebSearchProvider(new BraveSearchProvider(ctx));
    }

    static class BraveSearchProvider implements WebSearchProvider {
        private final AgentPluginContext ctx;

        BraveSearchProvider(AgentPluginContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public String name() {
            return "brave-free";
        }

        @Override
        public boolean isAvailable() {
            return StrUtil.isNotBlank(ctx.getEnv("BRAVE_SEARCH_API_KEY"));
        }

        @Override
        public List<SearchResult> search(String query, int limit) {
            String apiKey = ctx.getEnv("BRAVE_SEARCH_API_KEY");
            if (StrUtil.isBlank(apiKey)) {
                return Collections.emptyList();
            }
            try {
                HttpResponse response = HttpRequest.get("https://api.search.brave.com/res/v1/web/search")
                        .header("Accept", "application/json")
                        .header("Accept-Encoding", "gzip")
                        .header("X-Subscription-Token", apiKey)
                        .form("q", query)
                        .form("count", String.valueOf(limit))
                        .timeout(15000)
                        .execute();

                if (!response.isOk()) {
                    log.warn("Brave search failed with status: {}", response.getStatus());
                    return Collections.emptyList();
                }

                ONode root = ONode.ofJson(response.body());
                ONode results = root.get("web").get("results");
                List<SearchResult> list = new ArrayList<>();
                for (ONode item : results.ary()) {
                    String title = item.get("title").getString();
                    String url = item.get("url").getString();
                    String description = item.get("description").getString();
                    list.add(new SearchResult(title, url, description != null ? description : ""));
                }
                return list;
            } catch (Exception e) {
                log.warn("Brave search error: {}", e.getMessage());
                return Collections.emptyList();
            }
        }
    }
}
