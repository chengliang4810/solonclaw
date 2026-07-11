import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

import com.jimuqu.solon.claw.plugin.AgentPlugin;
import com.jimuqu.solon.claw.plugin.AgentPluginContext;
import com.jimuqu.solon.claw.plugin.provider.CdpBrowserProvider;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 注册 Browser Use 云浏览器 Provider。 */
public class BrowserUsePlugin implements AgentPlugin {
    /** Provider 生命周期诊断日志，不记录密钥、连接地址或响应正文。 */
    private static final Logger log = LoggerFactory.getLogger(BrowserUsePlugin.class);

    /** Browser Use 当前浏览器会话 API 根地址。 */
    private static final String BASE_URL = "https://api.browser-use.com/api/v3";

    /**
     * 注册由环境变量配置的 Browser Use Provider。
     *
     * @param ctx 插件注册上下文。
     */
    @Override
    public void register(AgentPluginContext ctx) {
        String apiKey = ctx.getEnv("BROWSER_USE_API_KEY");
        ctx.registerBrowserProvider(new BrowserUseProvider(apiKey));
    }

    /** 使用 Browser Use API 管理远端会话，并复用共享 CDP 实现执行页面动作。 */
    private static class BrowserUseProvider extends CdpBrowserProvider {
        /** Browser Use API 密钥。 */
        private final String apiKey;

        /**
         * 创建 Browser Use Provider。
         *
         * @param apiKey Browser Use API 密钥。
         */
        BrowserUseProvider(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * 读取 Provider 稳定名称。
         *
         * @return Provider 名称。
         */
        @Override
        public String name() {
            return "browser-use";
        }

        /**
         * 判断 Browser Use API 密钥是否已配置。
         *
         * @return 已配置返回 true。
         */
        @Override
        public boolean isAvailable() {
            return StrUtil.isNotBlank(apiKey);
        }

        /**
         * 创建 Browser Use 远端浏览器会话并登记 CDP 地址。
         *
         * @param taskId 任务标识，仅用于运行时关联，不发送到第三方 API。
         * @return 已创建的浏览器会话，失败返回 null。
         */
        @Override
        public BrowserSession createSession(String taskId) {
            try {
                ONode body = new ONode();

                try (HttpResponse response =
                        HttpRequest.post(BASE_URL + "/browsers")
                                .header("X-Browser-Use-API-Key", apiKey)
                                .header("Content-Type", "application/json")
                                .body(body.toJson())
                                .timeout(30000)
                                .execute()) {
                    if (!response.isOk()) {
                        log.warn("Browser Use create session failed: {}", response.getStatus());
                        return null;
                    }

                    ONode result = ONode.ofJson(response.body());
                    String sessionId = result.get("id").getString();
                    String connectUrl = result.get("cdpUrl").getString();
                    if (StrUtil.isBlank(connectUrl)) {
                        connectUrl = result.get("connectUrl").getString();
                    }
                    return registerCdpSession(sessionId, connectUrl);
                }
            } catch (Exception e) {
                log.warn("Browser Use create session error: {}", e.getClass().getSimpleName());
                return null;
            }
        }

        /**
         * 先释放本地 CDP 连接，再停止 Browser Use 远端会话。
         *
         * @param sessionId Browser Use 会话标识。
         */
        @Override
        public void closeSession(String sessionId) {
            releaseCdpSession(sessionId);
            try {
                ONode body = new ONode();
                body.set("action", "stop");
                try (HttpResponse response =
                        HttpRequest.patch(BASE_URL + "/browsers/" + sessionId)
                                .header("X-Browser-Use-API-Key", apiKey)
                                .header("Content-Type", "application/json")
                                .body(body.toJson())
                                .timeout(15000)
                                .execute()) {
                    if (!response.isOk()) {
                        log.warn("Browser Use close session failed: {}", response.getStatus());
                    }
                }
            } catch (Exception e) {
                log.warn("Browser Use close session error: {}", e.getClass().getSimpleName());
            }
        }
    }
}
