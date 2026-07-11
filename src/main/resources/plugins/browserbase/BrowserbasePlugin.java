import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

import com.jimuqu.solon.claw.plugin.AgentPlugin;
import com.jimuqu.solon.claw.plugin.AgentPluginContext;
import com.jimuqu.solon.claw.plugin.provider.CdpBrowserProvider;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 注册 Browserbase 云浏览器 Provider。 */
public class BrowserbasePlugin implements AgentPlugin {
    /** Provider 生命周期诊断日志，不记录密钥、连接地址或响应正文。 */
    private static final Logger log = LoggerFactory.getLogger(BrowserbasePlugin.class);

    /** Browserbase 会话 API 根地址。 */
    private static final String BASE_URL = "https://api.browserbase.com/v1/sessions";

    /**
     * 注册由环境变量配置的 Browserbase Provider。
     *
     * @param ctx 插件注册上下文。
     */
    @Override
    public void register(AgentPluginContext ctx) {
        String apiKey = ctx.getEnv("BROWSERBASE_API_KEY");
        String projectId = ctx.getEnv("BROWSERBASE_PROJECT_ID");
        ctx.registerBrowserProvider(new BrowserbaseProvider(apiKey, projectId));
    }

    /** 使用 Browserbase API 管理远端会话，并复用共享 CDP 实现执行页面动作。 */
    private static class BrowserbaseProvider extends CdpBrowserProvider {
        /** Browserbase API 密钥。 */
        private final String apiKey;

        /** Browserbase 项目标识。 */
        private final String projectId;

        /**
         * 创建 Browserbase Provider。
         *
         * @param apiKey Browserbase API 密钥。
         * @param projectId Browserbase 项目标识。
         */
        BrowserbaseProvider(String apiKey, String projectId) {
            this.apiKey = apiKey;
            this.projectId = projectId;
        }

        /**
         * 读取 Provider 稳定名称。
         *
         * @return Provider 名称。
         */
        @Override
        public String name() {
            return "browserbase";
        }

        /**
         * 判断 Browserbase 所需凭据是否完整。
         *
         * @return API 密钥和项目标识均存在时返回 true。
         */
        @Override
        public boolean isAvailable() {
            return StrUtil.isNotBlank(apiKey) && StrUtil.isNotBlank(projectId);
        }

        /**
         * 创建 Browserbase 远端浏览器会话并登记 CDP 地址。
         *
         * @param taskId 任务标识，仅用于运行时关联，不发送到第三方 API。
         * @return 已创建的浏览器会话，失败返回 null。
         */
        @Override
        public BrowserSession createSession(String taskId) {
            try {
                ONode body = new ONode();
                body.set("projectId", projectId);

                try (HttpResponse response =
                        HttpRequest.post(BASE_URL)
                                .header("X-BB-API-Key", apiKey)
                                .header("Content-Type", "application/json")
                                .body(body.toJson())
                                .timeout(30000)
                                .execute()) {
                    if (!response.isOk()) {
                        log.warn("Browserbase create session failed: {}", response.getStatus());
                        return null;
                    }

                    ONode result = ONode.ofJson(response.body());
                    String sessionId = result.get("id").getString();
                    String connectUrl = result.get("connectUrl").getString();
                    return registerCdpSession(sessionId, connectUrl);
                }
            } catch (Exception e) {
                log.warn("Browserbase create session error: {}", e.getClass().getSimpleName());
                return null;
            }
        }

        /**
         * 先释放本地 CDP 连接，再请求 Browserbase 释放远端会话。
         *
         * @param sessionId Browserbase 会话标识。
         */
        @Override
        public void closeSession(String sessionId) {
            releaseCdpSession(sessionId);
            try {
                ONode body = new ONode();
                body.set("projectId", projectId);
                body.set("status", "REQUEST_RELEASE");
                try (HttpResponse response =
                        HttpRequest.post(BASE_URL + "/" + sessionId)
                                .header("X-BB-API-Key", apiKey)
                                .header("Content-Type", "application/json")
                                .body(body.toJson())
                                .timeout(15000)
                                .execute()) {
                    if (!response.isOk()) {
                        log.warn("Browserbase close session failed: {}", response.getStatus());
                    }
                }
            } catch (Exception e) {
                log.warn("Browserbase close session error: {}", e.getClass().getSimpleName());
            }
        }
    }
}
