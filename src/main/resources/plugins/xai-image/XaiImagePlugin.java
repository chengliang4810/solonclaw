import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.plugin.AgentPlugin;
import com.jimuqu.solon.claw.plugin.AgentPluginContext;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class XaiImagePlugin implements AgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(XaiImagePlugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        String apiKey = ctx.getEnv("XAI_API_KEY");
        ctx.registerImageGenProvider(new XaiImageProvider(apiKey));
    }

    private static class XaiImageProvider implements ImageGenProvider {
        private final String apiKey;

        XaiImageProvider(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public String name() {
            return "xai-image";
        }

        @Override
        public boolean isAvailable() {
            return StrUtil.isNotBlank(apiKey);
        }

        @Override
        public ImageGenResult generate(String prompt, String aspectRatio, Map<String, Object> options) {
            try {
                ONode body = new ONode();
                body.set("model", "grok-2-image");
                body.set("prompt", prompt);
                body.set("n", 1);

                HttpResponse response = HttpRequest.post("https://api.x.ai/v1/images/generations")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .body(body.toJson())
                        .timeout(60000)
                        .execute();

                if (!response.isOk()) {
                    return ImageGenResult.fail("xAI API error: " + response.getStatus());
                }

                ONode result = ONode.ofJson(response.body());
                String url = result.get("data").get(0).get("url").getString();
                return ImageGenResult.ok(url);
            } catch (Exception e) {
                log.warn("xAI image generation failed: {}", e.getMessage());
                return ImageGenResult.fail(e.getMessage());
            }
        }
    }
}
