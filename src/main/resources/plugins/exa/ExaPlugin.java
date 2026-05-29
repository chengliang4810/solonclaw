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

public class ExaPlugin implements AgentPlugin {

    private static final Logger log = LoggerFactory.getLogger(ExaPlugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        ctx.registerWebSearchProvider(new ExaSearchProvider(ctx));
    }

    static class ExaSearchProvider implements WebSearchProvider {
        private final AgentPluginContext ctx;

        ExaSearchProvider(AgentPluginContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public String name() {
            return "exa";
        }

        @Override
        public boolean isAvailable() {
            return StrUtil.isNotBlank(ctx.getEnv("EXA_API_KEY"));
        }

        @Override
        public List<SearchResult> search(String query, int limit) {
            String apiKey = ctx.getEnv("EXA_API_KEY");
            if (StrUtil.isBlank(apiKey)) {
                return Collections.emptyList();
            }
            try {
                ONode body = new ONode();
                body.set("query", query);
                body.set("numResults", limit);

                HttpResponse response = HttpRequest.post("https://api.exa.ai/search")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .body(body.toJson())
                        .timeout(15000)
                        .execute();

                if (!response.isOk()) {
                    log.warn("Exa search failed with status: {}", response.getStatus());
                    return Collections.emptyList();
                }

                ONode root = ONode.ofJson(response.body());
                ONode results = root.get("results");
                List<SearchResult> list = new ArrayList<>();
                for (ONode item : results.ary()) {
                    String title = item.get("title").getString();
                    String url = item.get("url").getString();
                    String snippet = item.get("text").getString();
                    list.add(new SearchResult(
                            title != null ? title : "",
                            url,
                            snippet != null ? snippet : ""));
                }
                return list;
            } catch (Exception e) {
                log.warn("Exa search error: {}", e.getMessage());
                return Collections.emptyList();
            }
        }
    }
}
