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

public class FalVideoPlugin implements AgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(FalVideoPlugin.class);
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_ATTEMPTS = 60;

    @Override
    public void register(AgentPluginContext ctx) {
        String apiKey = ctx.getEnv("FAL_KEY");
        ctx.registerVideoGenProvider(new FalVideoProvider(apiKey));
    }

    private static class FalVideoProvider implements VideoGenProvider {
        private final String apiKey;

        FalVideoProvider(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public String name() {
            return "fal-video";
        }

        @Override
        public boolean isAvailable() {
            return StrUtil.isNotBlank(apiKey);
        }

        @Override
        public VideoGenResult generate(String prompt, Map<String, Object> options) {
            try {
                ONode body = new ONode();
                body.set("prompt", prompt);
                body.set("num_frames", 85);
                body.set("fps", 25);

                HttpResponse response = HttpRequest.post("https://queue.fal.run/fal-ai/ltx-video/v0.9.1")
                        .header("Authorization", "Key " + apiKey)
                        .header("Content-Type", "application/json")
                        .body(body.toJson())
                        .timeout(30000)
                        .execute();

                if (!response.isOk()) {
                    return VideoGenResult.fail("FAL API error: " + response.getStatus());
                }

                ONode queueResult = ONode.ofJson(response.body());
                String statusUrl = queueResult.get("status_url").getString();

                if (StrUtil.isBlank(statusUrl)) {
                    // Synchronous response - try to get video directly
                    String videoUrl = queueResult.get("video").get("url").getString();
                    if (StrUtil.isNotBlank(videoUrl)) {
                        return VideoGenResult.ok(videoUrl);
                    }
                    return VideoGenResult.fail("No status_url or video in response");
                }

                // Poll for completion
                return pollForResult(statusUrl);
            } catch (Exception e) {
                log.warn("FAL video generation failed: {}", e.getMessage());
                return VideoGenResult.fail(e.getMessage());
            }
        }

        private VideoGenResult pollForResult(String statusUrl) {
            for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return VideoGenResult.fail("Interrupted while waiting for video");
                }

                try {
                    HttpResponse pollResponse = HttpRequest.get(statusUrl)
                            .header("Authorization", "Key " + apiKey)
                            .timeout(15000)
                            .execute();

                    if (!pollResponse.isOk()) {
                        continue;
                    }

                    ONode pollResult = ONode.ofJson(pollResponse.body());
                    String status = pollResult.get("status").getString();

                    if ("COMPLETED".equals(status)) {
                        String videoUrl = pollResult.get("response").get("video").get("url").getString();
                        if (StrUtil.isNotBlank(videoUrl)) {
                            return VideoGenResult.ok(videoUrl);
                        }
                        return VideoGenResult.fail("Completed but no video URL found");
                    } else if ("FAILED".equals(status)) {
                        String error = pollResult.get("error").getString();
                        return VideoGenResult.fail(StrUtil.blankToDefault(error, "Generation failed"));
                    }
                } catch (Exception e) {
                    log.debug("Poll attempt {} failed: {}", i, e.getMessage());
                }
            }
            return VideoGenResult.fail("Timed out waiting for video generation");
        }
    }
}
