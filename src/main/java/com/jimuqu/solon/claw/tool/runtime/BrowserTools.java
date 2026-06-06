package com.jimuqu.solon.claw.tool.runtime;

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
     * 点击浏览器页面中的目标元素。
     *
     * @param sessionId 当前会话标识。
     * @param selector 浏览器元素选择器。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回click结果。
     */
    @ToolMapping(name = "browser_click", description = "点击页面元素。")
    public BrowserRuntimeService.BrowserResult click(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "selector", description = "CSS 选择器") String selector,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒")
                    Integer timeoutSeconds) {
        return browserRuntimeService.click(sessionId, selector, timeoutSeconds);
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
    @ToolMapping(name = "browser_type", description = "向页面元素输入文本。")
    public BrowserRuntimeService.BrowserResult type(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "selector", description = "CSS 选择器") String selector,
            @Param(name = "text", description = "输入文本") String text,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒")
                    Integer timeoutSeconds) {
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
}
