import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.plugin.AgentPlugin;
import com.jimuqu.solon.claw.plugin.AgentPluginContext;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mem0Plugin implements AgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(Mem0Plugin.class);

    @Override
    public void register(AgentPluginContext ctx) {
        String apiKey = ctx.getEnv("MEM0_API_KEY");
        ctx.registerMemoryProvider(new Mem0Provider(apiKey));
    }

    private static class Mem0Provider implements MemoryProvider {
        private static final String BASE_URL = "https://api.mem0.ai/v1";
        private final String apiKey;

        Mem0Provider(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public String name() {
            return "mem0";
        }

        @Override
        public String systemPromptBlock(String sourceKey) throws Exception {
            return "Long-term memory is available. Relevant memories will be automatically retrieved based on the conversation context.";
        }

        @Override
        public String prefetch(String sourceKey, String userMessage) throws Exception {
            if (StrUtil.isBlank(apiKey)) {
                return "";
            }
            try {
                ONode body = new ONode();
                body.set("query", userMessage);
                body.set("user_id", sourceKey);

                HttpResponse response = HttpRequest.post(BASE_URL + "/memories/search")
                        .header("Authorization", "Token " + apiKey)
                        .header("Content-Type", "application/json")
                        .body(body.toJson())
                        .timeout(10000)
                        .execute();

                if (!response.isOk()) {
                    log.debug("Mem0 search failed: {}", response.getStatus());
                    return "";
                }

                ONode results = ONode.ofJson(response.body());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < results.size(); i++) {
                    ONode memory = results.get(i);
                    String memoryText = memory.get("memory").getString();
                    if (StrUtil.isNotBlank(memoryText)) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append("- ").append(memoryText);
                    }
                }

                if (sb.length() == 0) {
                    return "";
                }
                return "[Recalled memories]\n" + sb.toString();
            } catch (Exception e) {
                log.debug("Mem0 prefetch error: {}", e.getMessage());
                return "";
            }
        }

        @Override
        public void syncTurn(String sourceKey, String userMessage, String assistantMessage) throws Exception {
            if (StrUtil.isBlank(apiKey)) {
                return;
            }
            try {
                ONode messages = new ONode().asArray();
                ONode userMsg = new ONode().asObject();
                userMsg.set("role", "user");
                userMsg.set("content", userMessage);
                messages.add(userMsg);

                ONode assistantMsg = new ONode().asObject();
                assistantMsg.set("role", "assistant");
                assistantMsg.set("content", assistantMessage);
                messages.add(assistantMsg);

                ONode body = new ONode();
                body.set("messages", messages);
                body.set("user_id", sourceKey);

                HttpResponse response = HttpRequest.post(BASE_URL + "/memories")
                        .header("Authorization", "Token " + apiKey)
                        .header("Content-Type", "application/json")
                        .body(body.toJson())
                        .timeout(10000)
                        .execute();

                if (!response.isOk()) {
                    log.debug("Mem0 add memory failed: {}", response.getStatus());
                }
            } catch (Exception e) {
                log.debug("Mem0 syncTurn error: {}", e.getMessage());
            }
        }
    }
}
