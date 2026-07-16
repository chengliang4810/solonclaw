package com.jimuqu.solon.claw.provider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 浏览器自动化后端接口。 */
public interface BrowserProvider {
    /**
     * 执行名称相关逻辑。
     *
     * @return 返回名称结果。
     */
    String name();

    /**
     * 判断是否Available。
     *
     * @return 如果Available满足条件则返回 true，否则返回 false。
     */
    boolean isAvailable();

    /**
     * 创建会话。
     *
     * @param taskId 任务标识。
     * @return 返回创建好的会话。
     */
    BrowserSession createSession(String taskId);

    /**
     * 关闭会话。
     *
     * @param sessionId 当前会话标识。
     */
    void closeSession(String sessionId);

    /**
     * 导航浏览器会话到目标地址。
     *
     * @param sessionId 当前会话标识。
     * @param url 待校验或访问的 URL。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回navigate结果。
     */
    default BrowserActionResult navigate(String sessionId, String url, int timeoutSeconds) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support navigate");
    }

    /**
     * 点击浏览器页面中的目标元素。
     *
     * @param sessionId 当前会话标识。
     * @param selector 浏览器元素选择器。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回click结果。
     */
    default BrowserActionResult click(String sessionId, String selector, int timeoutSeconds) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support click");
    }

    /**
     * 向浏览器页面中的目标元素输入文本。
     *
     * @param sessionId 当前会话标识。
     * @param selector 浏览器元素选择器。
     * @param text 待处理文本。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回类型结果。
     */
    default BrowserActionResult type(
            String sessionId, String selector, String text, int timeoutSeconds) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support type");
    }

    /**
     * 截取浏览器页面截图。
     *
     * @param sessionId 当前会话标识。
     * @param path 文件或目录路径。
     * @param fullPage fullPage 参数。
     * @return 返回screenshot结果。
     */
    default BrowserActionResult screenshot(String sessionId, String path, boolean fullPage) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support screenshot");
    }

    /**
     * 从浏览器页面提取指定内容。
     *
     * @param sessionId 当前会话标识。
     * @param selector 浏览器元素选择器。
     * @param format 格式参数。
     * @return 返回extract结果。
     */
    default BrowserActionResult extract(String sessionId, String selector, String format) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support extract");
    }

    /**
     * 获取页面可访问性快照和交互元素引用。
     *
     * @param sessionId 当前会话标识。
     * @param full 是否返回完整页面文本。
     * @return 页面快照结果。
     */
    default BrowserActionResult snapshot(String sessionId, boolean full) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support snapshot");
    }

    /**
     * 滚动当前页面。
     *
     * @param sessionId 当前会话标识。
     * @param direction 滚动方向，支持 up/down。
     * @param pixels 滚动像素数。
     * @return 页面滚动结果。
     */
    default BrowserActionResult scroll(String sessionId, String direction, int pixels) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support scroll");
    }

    /**
     * 返回浏览器历史中的上一页。
     *
     * @param sessionId 当前会话标识。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 历史导航结果。
     */
    default BrowserActionResult back(String sessionId, int timeoutSeconds) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support back");
    }

    /**
     * 向当前页面派发键盘按键。
     *
     * @param sessionId 当前会话标识。
     * @param key 按键名称。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 按键动作结果。
     */
    default BrowserActionResult press(String sessionId, String key, int timeoutSeconds) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support key input");
    }

    /**
     * 枚举当前页面中的图片。
     *
     * @param sessionId 当前会话标识。
     * @return 图片列表结果。
     */
    default BrowserActionResult getImages(String sessionId) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support image discovery");
    }

    /**
     * 读取控制台消息或执行受控 JavaScript 表达式。
     *
     * @param sessionId 当前会话标识。
     * @param clear 读取后是否清空控制台缓冲。
     * @param expression 可选 JavaScript 表达式。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 控制台或表达式结果。
     */
    default BrowserActionResult console(
            String sessionId, boolean clear, String expression, int timeoutSeconds) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support console inspection");
    }

    /**
     * 发送原始 Chrome DevTools Protocol 命令。
     *
     * @param sessionId 当前会话标识。
     * @param method CDP 方法名。
     * @param params CDP 参数。
     * @param targetId 可选页面 Target 标识；为空时在浏览器级执行。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 原始 CDP 结果。
     */
    default BrowserActionResult cdp(
            String sessionId,
            String method,
            Map<String, Object> params,
            String targetId,
            int timeoutSeconds) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support raw CDP commands");
    }

    /**
     * 响应当前页面阻塞中的 JavaScript 对话框。
     *
     * @param sessionId 当前会话标识。
     * @param action accept 或 dismiss。
     * @param promptText prompt 对话框输入文本。
     * @param dialogId 可选对话框标识。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 对话框处理结果。
     */
    default BrowserActionResult dialog(
            String sessionId,
            String action,
            String promptText,
            String dialogId,
            int timeoutSeconds) {
        return BrowserActionResult.fail(
                "unsupported_action", "Browser provider does not support dialogs");
    }

    /** 承载浏览器会话相关状态和辅助逻辑。 */
    class BrowserSession {
        /** 记录浏览器会话中的会话标识。 */
        private final String sessionId;

        /** 记录浏览器会话中的connectURL。 */
        private final String connectUrl;

        /**
         * 创建浏览器会话实例，并注入运行所需依赖。
         *
         * @param sessionId 当前会话标识。
         * @param connectUrl 待校验或访问的地址参数。
         */
        public BrowserSession(String sessionId, String connectUrl) {
            this.sessionId = sessionId;
            this.connectUrl = connectUrl;
        }

        /**
         * 读取会话标识。
         *
         * @return 返回读取到的会话标识。
         */
        public String getSessionId() {
            return sessionId;
        }

        /**
         * 读取Connect URL。
         *
         * @return 返回读取到的Connect URL。
         */
        public String getConnectUrl() {
            return connectUrl;
        }
    }

    /** 表示浏览器Action结果，携带调用方后续判断所需信息。 */
    class BrowserActionResult {
        /** 是否启用success。 */
        private final boolean success;

        /** 记录浏览器Action中的状态。 */
        private final String status;

        /** 记录浏览器Action中的当前URL。 */
        private final String currentUrl;

        /** 保存details映射，便于按键快速查询。 */
        private final Map<String, Object> details;

        /** 记录浏览器Action中的错误Code。 */
        private final String errorCode;

        /** 记录浏览器Action中的错误消息。 */
        private final String errorMessage;

        /**
         * 创建浏览器Action结果实例，并注入运行所需依赖。
         *
         * @param success success 参数。
         * @param status 状态参数。
         * @param currentUrl 待校验或访问的地址参数。
         * @param details details 参数。
         * @param errorCode 错误Code参数。
         * @param errorMessage 错误消息参数。
         */
        private BrowserActionResult(
                boolean success,
                String status,
                String currentUrl,
                Map<String, Object> details,
                String errorCode,
                String errorMessage) {
            this.success = success;
            this.status = status;
            this.currentUrl = currentUrl;
            this.details =
                    details == null
                            ? Collections.<String, Object>emptyMap()
                            : new LinkedHashMap<String, Object>(details);
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        /**
         * 构造成功结果。
         *
         * @param status 状态参数。
         * @param currentUrl 待校验或访问的地址参数。
         * @return 返回ok结果。
         */
        public static BrowserActionResult ok(String status, String currentUrl) {
            return ok(status, currentUrl, Collections.<String, Object>emptyMap());
        }

        /**
         * 构造成功结果。
         *
         * @param status 状态参数。
         * @param currentUrl 待校验或访问的地址参数。
         * @param details details 参数。
         * @return 返回ok结果。
         */
        public static BrowserActionResult ok(
                String status, String currentUrl, Map<String, Object> details) {
            return new BrowserActionResult(true, status, currentUrl, details, null, null);
        }

        /**
         * 构造失败结果并携带安全错误信息。
         *
         * @param errorCode 错误Code参数。
         * @param errorMessage 错误消息参数。
         * @return 返回fail结果。
         */
        public static BrowserActionResult fail(String errorCode, String errorMessage) {
            return new BrowserActionResult(
                    false,
                    "error",
                    null,
                    Collections.<String, Object>emptyMap(),
                    errorCode,
                    errorMessage);
        }

        /**
         * 判断是否Success。
         *
         * @return 如果Success满足条件则返回 true，否则返回 false。
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 读取状态。
         *
         * @return 返回读取到的状态。
         */
        public String getStatus() {
            return status;
        }

        /**
         * 读取当前URL。
         *
         * @return 返回读取到的当前URL。
         */
        public String getCurrentUrl() {
            return currentUrl;
        }

        /**
         * 读取Details。
         *
         * @return 返回读取到的Details。
         */
        public Map<String, Object> getDetails() {
            return new LinkedHashMap<String, Object>(details);
        }

        /**
         * 读取Error Code。
         *
         * @return 返回读取到的Error Code。
         */
        public String getErrorCode() {
            return errorCode;
        }

        /**
         * 读取Error消息。
         *
         * @return 返回读取到的Error消息。
         */
        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
