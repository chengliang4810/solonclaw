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

public class OpenAIImagePlugin implements AgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(OpenAIImagePlugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        String apiKey = ctx.getEnv("OPENAI_API_KEY");
        ctx.registerImageGenProvider(new OpenAIImageProvider(apiKey));
    }

    private static class OpenAIImageProvider implements ImageGenProvider {
        private final String apiKey;

        OpenAIImageProvider(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public String name() {
            return "openai-image";
        }

        @Override
        public boolean isAvailable() {
            return StrUtil.isNotBlank(apiKey);
        }

        @Override
        public ImageGenResult generate(String prompt, String aspectRatio, Map<String, Object> options) {
            try {
                String size = mapAspectRatioToSize(aspectRatio);
                ONode body = new ONode();
                body.set("model", "dall-e-3");
                body.set("prompt", prompt);
                body.set("size", size);
                body.set("n", 1);

                HttpResponse response = HttpRequest.post("https://api.openai.com/v1/images/generations")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .body(body.toJson())
                        .timeout(60000)
                        .execute();

                if (!response.isOk()) {
                    return ImageGenResult.fail("OpenAI API error: " + response.getStatus());
                }

                ONode result = ONode.ofJson(response.body());
                String url = result.get("data").get(0).get("url").getString();
                return ImageGenResult.ok(url);
            } catch (Exception e) {
                log.warn("OpenAI image generation failed: {}", e.getMessage());
                return ImageGenResult.fail(e.getMessage());
            }
        }

        private String mapAspectRatioToSize(String aspectRatio) {
            if (StrUtil.isBlank(aspectRatio)) {
                return "1024x1024";
            }
            switch (aspectRatio) {
                case "16:9":
                    return "1792x1024";
                case "9:16":
                    return "1024x1792";
                default:
                    return "1024x1024";
            }
        }
    }
}
