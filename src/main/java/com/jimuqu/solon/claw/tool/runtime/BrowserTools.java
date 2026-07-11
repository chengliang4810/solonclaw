package com.jimuqu.solon.claw.tool.runtime;

import java.util.Map;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供浏览器工具能力，供 Agent 运行时按安全策略调用。 */
public class BrowserTools {
    /** 注入浏览器运行时服务，用于调用对应业务能力。 */
    private final BrowserRuntimeService browserRuntimeService;

    /**
     * 创建浏览器工具实例，并注入运行所需依赖。
     *
     * @param browserRuntimeService 浏览器运行时服务依赖。
     */
    public BrowserTools(BrowserRuntimeService browserRuntimeService) {
        this.browserRuntimeService = browserRuntimeService;
    }

    /**
     * 执行create，服务于浏览器主流程相关逻辑。
     *
     * @param taskId 任务标识。
     * @return 返回create结果。
     */
    @ToolMapping(name = "browser_create", description = "创建受管浏览器会话。")
    public BrowserRuntimeService.BrowserResult create(
            @Param(name = "taskId", required = false, description = "任务标识") String taskId) {
        return browserRuntimeService.create(taskId);
    }

    /**
     * 导航浏览器会话到目标地址。
     *
     * @param sessionId 当前会话标识。
     * @param url 待校验或访问的 URL。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回navigate结果。
     */
    @ToolMapping(name = "browser_navigate", description = "在受管浏览器会话中访问 URL。")
    public BrowserRuntimeService.BrowserResult navigate(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "url", description = "目标 URL") String url,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒")
                    Integer timeoutSeconds) {
        return browserRuntimeService.navigate(sessionId, url, timeoutSeconds);
    }

    /**
     * 点击浏览器页面中的目标元素，优先使用页面快照返回的 ref，同时兼容原有 CSS 选择器。
     *
     * @param sessionId 当前会话标识。
     * @param ref 页面快照中的元素引用，例如 @e5。
     * @param selector 兼容调用方传入的 CSS 选择器。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回点击结果。
     */
    @ToolMapping(
            name = "browser_click",
            description = "点击页面元素；优先传入 browser_snapshot 返回的 @eN ref，也兼容 CSS selector。")
    public BrowserRuntimeService.BrowserResult click(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "ref", required = false, description = "页面快照中的元素引用，例如 @e5") String ref,
            @Param(name = "selector", required = false, description = "兼容使用的 CSS 选择器")
                    String selector,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒")
                    Integer timeoutSeconds) {
        return browserRuntimeService.click(
                sessionId, selectElementReference(ref, selector), timeoutSeconds);
    }

    /**
     * 兼容 Java 调用方原有的 CSS 选择器点击签名。
     *
     * @param sessionId 当前会话标识。
     * @param selector CSS 选择器或页面快照 ref。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回点击结果。
     */
    public BrowserRuntimeService.BrowserResult click(
            String sessionId, String selector, Integer timeoutSeconds) {
        return browserRuntimeService.click(sessionId, selector, timeoutSeconds);
    }

    /**
     * 向浏览器页面中的目标元素输入文本，优先使用页面快照返回的 ref。
     *
     * @param sessionId 当前会话标识。
     * @param ref 页面快照中的元素引用，例如 @e3。
     * @param selector 兼容调用方传入的 CSS 选择器。
     * @param text 待输入文本。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回输入结果。
     */
    @ToolMapping(
            name = "browser_type",
            description = "清空并向页面元素输入文本；优先传入 browser_snapshot 返回的 @eN ref，也兼容 CSS selector。")
    public BrowserRuntimeService.BrowserResult type(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "ref", required = false, description = "页面快照中的元素引用，例如 @e3") String ref,
            @Param(name = "selector", required = false, description = "兼容使用的 CSS 选择器")
                    String selector,
            @Param(name = "text", description = "输入文本") String text,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒")
                    Integer timeoutSeconds) {
        return browserRuntimeService.type(
                sessionId, selectElementReference(ref, selector), text, timeoutSeconds);
    }

    /**
     * 兼容 Java 调用方原有的 CSS 选择器输入签名。
     *
     * @param sessionId 当前会话标识。
     * @param selector CSS 选择器或页面快照 ref。
     * @param text 待输入文本。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回输入结果。
     */
    public BrowserRuntimeService.BrowserResult type(
            String sessionId, String selector, String text, Integer timeoutSeconds) {
        return browserRuntimeService.type(sessionId, selector, text, timeoutSeconds);
    }

    /**
     * 截取浏览器页面截图。
     *
     * @param sessionId 当前会话标识。
     * @param path 文件或目录路径。
     * @param fullPage fullPage 参数。
     * @return 返回screenshot结果。
     */
    @ToolMapping(name = "browser_screenshot", description = "生成页面截图。")
    public BrowserRuntimeService.BrowserResult screenshot(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "path", required = false, description = "截图输出路径") String path,
            @Param(name = "fullPage", required = false, description = "是否截取完整页面")
                    Boolean fullPage) {
        return browserRuntimeService.screenshot(sessionId, path, fullPage);
    }

    /**
     * 从浏览器页面提取指定内容。
     *
     * @param sessionId 当前会话标识。
     * @param selector 浏览器元素选择器。
     * @param format 格式参数。
     * @return 返回extract结果。
     */
    @ToolMapping(name = "browser_extract", description = "提取页面内容。")
    public BrowserRuntimeService.BrowserResult extract(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "selector", required = false, description = "CSS 选择器") String selector,
            @Param(name = "format", required = false, description = "提取格式") String format) {
        return browserRuntimeService.extract(sessionId, selector, format);
    }

    /**
     * 获取页面文本快照和可交互元素引用。
     *
     * @param sessionId 当前会话标识。
     * @param full 是否包含完整页面正文。
     * @return 返回页面快照结果。
     */
    @ToolMapping(name = "browser_snapshot", description = "获取页面文本快照和 @eN 元素引用；full=true 时包含有界完整正文。")
    public BrowserRuntimeService.BrowserResult snapshot(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "full", required = false, description = "是否包含完整页面正文") Boolean full) {
        return browserRuntimeService.snapshot(sessionId, full);
    }

    /**
     * 按方向滚动当前页面。
     *
     * @param sessionId 当前会话标识。
     * @param direction 滚动方向，仅支持 up 或 down。
     * @param pixels 可选滚动像素数，默认 500。
     * @return 返回滚动结果。
     */
    @ToolMapping(name = "browser_scroll", description = "向上或向下滚动当前页面。")
    public BrowserRuntimeService.BrowserResult scroll(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "direction", description = "滚动方向：up 或 down") String direction,
            @Param(name = "pixels", required = false, description = "滚动像素数，默认 500")
                    Integer pixels) {
        return browserRuntimeService.scroll(sessionId, direction, pixels);
    }

    /**
     * 返回浏览器历史中的上一页。
     *
     * @param sessionId 当前会话标识。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回历史导航结果。
     */
    @ToolMapping(name = "browser_back", description = "返回浏览器历史中的上一页。")
    public BrowserRuntimeService.BrowserResult back(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒")
                    Integer timeoutSeconds) {
        return browserRuntimeService.back(sessionId, timeoutSeconds);
    }

    /**
     * 向当前页面派发键盘按键。
     *
     * @param sessionId 当前会话标识。
     * @param key 按键名称，例如 Enter、Tab 或 Escape。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回按键结果。
     */
    @ToolMapping(name = "browser_press", description = "向当前页面派发单个键盘按键。")
    public BrowserRuntimeService.BrowserResult press(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "key", description = "按键名称，例如 Enter、Tab、Escape、ArrowDown") String key,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒")
                    Integer timeoutSeconds) {
        return browserRuntimeService.press(sessionId, key, timeoutSeconds);
    }

    /**
     * 枚举当前页面中的图片。
     *
     * @param sessionId 当前会话标识。
     * @return 返回图片 URL、替代文本和自然尺寸。
     */
    @ToolMapping(name = "browser_get_images", description = "枚举当前页面图片及其 URL、alt 和自然尺寸。")
    public BrowserRuntimeService.BrowserResult getImages(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId) {
        return browserRuntimeService.getImages(sessionId);
    }

    /**
     * 截取页面并返回真实视觉分析能力状态。
     *
     * @param sessionId 当前会话标识。
     * @param question 希望视觉能力回答的问题。
     * @param annotate 是否请求标注交互元素。
     * @param path 可选截图输出路径。
     * @return 返回截图路径和当前视觉分析能力状态。
     */
    @ToolMapping(
            name = "browser_vision",
            description = "截取当前页面并返回视觉能力状态；未接入视觉模型时明确返回 capture_only，不伪造分析结论。")
    public BrowserRuntimeService.BrowserResult vision(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "question", description = "希望视觉能力回答的具体问题") String question,
            @Param(name = "annotate", required = false, description = "是否请求标注交互元素")
                    Boolean annotate,
            @Param(name = "path", required = false, description = "截图输出路径") String path) {
        return browserRuntimeService.vision(sessionId, question, annotate, path);
    }

    /**
     * 读取浏览器控制台消息或执行受控 JavaScript 表达式。
     *
     * @param sessionId 当前会话标识。
     * @param clear 读取后是否清空控制台缓冲。
     * @param expression 可选 JavaScript 表达式。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回控制台消息或表达式结果。
     */
    @ToolMapping(
            name = "browser_console",
            description = "读取控制台消息和 JavaScript 异常，或执行经过敏感能力检查的页面表达式。")
    public BrowserRuntimeService.BrowserResult console(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "clear", required = false, description = "读取后是否清空消息缓冲") Boolean clear,
            @Param(name = "expression", required = false, description = "受控 JavaScript 表达式")
                    String expression,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒")
                    Integer timeoutSeconds) {
        return browserRuntimeService.console(sessionId, clear, expression, timeoutSeconds);
    }

    /**
     * 发送通过安全校验的原始 Chrome DevTools Protocol 命令。
     *
     * @param sessionId 当前会话标识。
     * @param method CDP 方法名。
     * @param params 方法参数对象。
     * @param targetId 可选页面 Target 标识；传 page 使用当前页面。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回有界递归脱敏后的 CDP 结果。
     */
    @ToolMapping(name = "browser_cdp", description = "发送原始 CDP 命令；敏感凭据/存储方法和危险表达式会被阻断。")
    public BrowserRuntimeService.BrowserResult cdp(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "method", description = "CDP 方法名，例如 Target.getTargets") String method,
            @Param(name = "params", required = false, description = "CDP 方法参数对象")
                    Map<String, Object> params,
            @Param(name = "targetId", required = false, description = "页面 Target ID；page 表示当前页面")
                    String targetId,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒")
                    Integer timeoutSeconds) {
        return browserRuntimeService.cdp(sessionId, method, params, targetId, timeoutSeconds);
    }

    /**
     * 响应当前页面阻塞中的原生 JavaScript 对话框。
     *
     * @param sessionId 当前会话标识。
     * @param action accept 或 dismiss。
     * @param promptText prompt 对话框输入文本。
     * @param dialogId 页面快照返回的可选对话框标识。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回对话框处理结果。
     */
    @ToolMapping(name = "browser_dialog", description = "接受或关闭当前阻塞中的原生 JavaScript 对话框。")
    public BrowserRuntimeService.BrowserResult dialog(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "action", description = "处理方式：accept 或 dismiss") String action,
            @Param(name = "promptText", required = false, description = "prompt 对话框输入文本")
                    String promptText,
            @Param(name = "dialogId", required = false, description = "快照中的对话框 ID") String dialogId,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒")
                    Integer timeoutSeconds) {
        return browserRuntimeService.dialog(
                sessionId, action, promptText, dialogId, timeoutSeconds);
    }

    /**
     * 关闭当前组件持有的运行资源。
     *
     * @param sessionId 当前会话标识。
     * @return 返回close结果。
     */
    @ToolMapping(name = "browser_close", description = "关闭受管浏览器会话。")
    public BrowserRuntimeService.BrowserResult close(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId) {
        return browserRuntimeService.close(sessionId);
    }

    /**
     * 在新 ref 和旧 selector 同时存在时优先使用 ref。
     *
     * @param ref 页面快照元素引用。
     * @param selector 兼容 CSS 选择器。
     * @return Provider 可直接解析的元素定位值。
     */
    private String selectElementReference(String ref, String selector) {
        return ref == null || ref.trim().length() == 0 ? selector : ref;
    }
}
