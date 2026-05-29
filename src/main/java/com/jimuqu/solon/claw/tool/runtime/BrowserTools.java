package com.jimuqu.solon.claw.tool.runtime;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** Built-in browser automation tool surface. */
public class BrowserTools {
    private final BrowserRuntimeService browserRuntimeService;

    public BrowserTools(BrowserRuntimeService browserRuntimeService) {
        this.browserRuntimeService = browserRuntimeService;
    }

    @ToolMapping(name = "browser_create", description = "创建受管浏览器会话。")
    public BrowserRuntimeService.BrowserResult create(
            @Param(name = "taskId", required = false, description = "任务标识") String taskId) {
        return browserRuntimeService.create(taskId);
    }

    @ToolMapping(name = "browser_navigate", description = "在受管浏览器会话中访问 URL。")
    public BrowserRuntimeService.BrowserResult navigate(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "url", description = "目标 URL") String url,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒") Integer timeoutSeconds) {
        return browserRuntimeService.navigate(sessionId, url, timeoutSeconds);
    }

    @ToolMapping(name = "browser_click", description = "点击页面元素。")
    public BrowserRuntimeService.BrowserResult click(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "selector", description = "CSS 选择器") String selector,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒") Integer timeoutSeconds) {
        return browserRuntimeService.click(sessionId, selector, timeoutSeconds);
    }

    @ToolMapping(name = "browser_type", description = "向页面元素输入文本。")
    public BrowserRuntimeService.BrowserResult type(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "selector", description = "CSS 选择器") String selector,
            @Param(name = "text", description = "输入文本") String text,
            @Param(name = "timeoutSeconds", required = false, description = "超时时间，单位秒") Integer timeoutSeconds) {
        return browserRuntimeService.type(sessionId, selector, text, timeoutSeconds);
    }

    @ToolMapping(name = "browser_screenshot", description = "生成页面截图。")
    public BrowserRuntimeService.BrowserResult screenshot(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "path", required = false, description = "截图输出路径") String path,
            @Param(name = "fullPage", required = false, description = "是否截取完整页面") Boolean fullPage) {
        return browserRuntimeService.screenshot(sessionId, path, fullPage);
    }

    @ToolMapping(name = "browser_extract", description = "提取页面内容。")
    public BrowserRuntimeService.BrowserResult extract(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId,
            @Param(name = "selector", required = false, description = "CSS 选择器") String selector,
            @Param(name = "format", required = false, description = "提取格式") String format) {
        return browserRuntimeService.extract(sessionId, selector, format);
    }

    @ToolMapping(name = "browser_close", description = "关闭受管浏览器会话。")
    public BrowserRuntimeService.BrowserResult close(
            @Param(name = "sessionId", description = "浏览器会话 ID") String sessionId) {
        return browserRuntimeService.close(sessionId);
    }
}
