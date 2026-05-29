import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.plugin.AgentPlugin;
import com.jimuqu.solon.claw.plugin.AgentPluginContext;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrowserbasePlugin implements AgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(BrowserbasePlugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        String apiKey = ctx.getEnv("BROWSERBASE_API_KEY");
        String projectId = ctx.getEnv("BROWSERBASE_PROJECT_ID");
        ctx.registerBrowserProvider(new BrowserbaseProvider(apiKey, projectId));
    }

    private static class BrowserbaseProvider implements BrowserProvider {
        private final String apiKey;
        private final String projectId;

        BrowserbaseProvider(String apiKey, String projectId) {
            this.apiKey = apiKey;
            this.projectId = projectId;
        }

        @Override
        public String name() {
            return "browserbase";
        }

        @Override
        public boolean isAvailable() {
            return StrUtil.isNotBlank(apiKey) && StrUtil.isNotBlank(projectId);
        }

        @Override
        public BrowserSession createSession(String taskId) {
            try {
                ONode body = new ONode();
                body.set("projectId", projectId);

                HttpResponse response = HttpRequest.post("https://api.browserbase.com/v1/sessions")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .body(body.toJson())
                        .timeout(30000)
                        .execute();

                if (!response.isOk()) {
                    log.warn("Browserbase create session failed: {}", response.getStatus());
                    return null;
                }

                ONode result = ONode.ofJson(response.body());
                String sessionId = result.get("id").getString();
                String connectUrl = result.get("connectUrl").getString();
                return new BrowserSession(sessionId, connectUrl);
            } catch (Exception e) {
                log.warn("Browserbase create session error: {}", e.getMessage());
                return null;
            }
        }

        @Override
        public void closeSession(String sessionId) {
            try {
                HttpResponse response = HttpRequest.post("https://api.browserbase.com/v1/sessions/" + sessionId + "/stop")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .timeout(15000)
                        .execute();

                if (!response.isOk()) {
                    log.warn("Browserbase close session failed: {}", response.getStatus());
                }
            } catch (Exception e) {
                log.warn("Browserbase close session error: {}", e.getMessage());
            }
        }
    }
}
