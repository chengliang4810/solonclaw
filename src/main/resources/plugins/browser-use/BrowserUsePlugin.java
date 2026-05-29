import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.plugin.AgentPlugin;
import com.jimuqu.solon.claw.plugin.AgentPluginContext;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrowserUsePlugin implements AgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(BrowserUsePlugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        String apiKey = ctx.getEnv("BROWSER_USE_API_KEY");
        ctx.registerBrowserProvider(new BrowserUseProvider(apiKey));
    }

    private static class BrowserUseProvider implements BrowserProvider {
        private final String apiKey;

        BrowserUseProvider(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public String name() {
            return "browser-use";
        }

        @Override
        public boolean isAvailable() {
            return StrUtil.isNotBlank(apiKey);
        }

        @Override
        public BrowserSession createSession(String taskId) {
            try {
                ONode body = new ONode();
                body.set("taskId", taskId);

                HttpResponse response = HttpRequest.post("https://api.browser-use.com/v1/sessions")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .body(body.toJson())
                        .timeout(30000)
                        .execute();

                if (!response.isOk()) {
                    log.warn("Browser Use create session failed: {}", response.getStatus());
                    return null;
                }

                ONode result = ONode.ofJson(response.body());
                String sessionId = result.get("session_id").getString();
                String connectUrl = result.get("connect_url").getString();
                return new BrowserSession(sessionId, connectUrl);
            } catch (Exception e) {
                log.warn("Browser Use create session error: {}", e.getMessage());
                return null;
            }
        }

        @Override
        public void closeSession(String sessionId) {
            try {
                HttpResponse response = HttpRequest.delete("https://api.browser-use.com/v1/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(15000)
                        .execute();

                if (!response.isOk()) {
                    log.warn("Browser Use close session failed: {}", response.getStatus());
                }
            } catch (Exception e) {
                log.warn("Browser Use close session error: {}", e.getMessage());
            }
        }
    }
}
