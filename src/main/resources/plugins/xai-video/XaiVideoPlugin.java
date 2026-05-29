import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.plugin.AgentPlugin;
import com.jimuqu.solon.claw.plugin.AgentPluginContext;
import com.jimuqu.solon.claw.plugin.provider.VideoGenProvider;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class XaiVideoPlugin implements AgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(XaiVideoPlugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        String apiKey = ctx.getEnv("XAI_API_KEY");
        ctx.registerVideoGenProvider(new XaiVideoProvider(apiKey));
    }

    private static class XaiVideoProvider implements VideoGenProvider {
        private final String apiKey;

        XaiVideoProvider(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public String name() {
            return "xai-video";
        }

        @Override
        public boolean isAvailable() {
            return StrUtil.isNotBlank(apiKey);
        }

        @Override
        public VideoGenResult generate(String prompt, Map<String, Object> options) {
            try {
                ONode body = new ONode();
                body.set("model", "grok-2-video");
                body.set("prompt", prompt);
                body.set("n", 1);

                HttpResponse response = HttpRequest.post("https://api.x.ai/v1/images/generations")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .body(body.toJson())
                        .timeout(120000)
                        .execute();

                if (!response.isOk()) {
                    return VideoGenResult.fail("xAI API error: " + response.getStatus());
                }

                ONode result = ONode.ofJson(response.body());
                String url = result.get("data").get(0).get("url").getString();
                return VideoGenResult.ok(url);
            } catch (Exception e) {
                log.warn("xAI video generation failed: {}", e.getMessage());
                return VideoGenResult.fail(e.getMessage());
            }
        }
    }
}
