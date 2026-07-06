# MCP 新增服务 JSON 占位符触发 i18n 编译错误

## 范围

- Dashboard 的 MCP 页面。
- “新增服务”弹窗中工具声明 JSON 示例占位符。
- 中文与英文界面的 `mcp.placeholders.toolsJson` 文案。

## 现象

进入 MCP 页面并打开“新增服务”弹窗后，工具 JSON 示例作为 `TextArea` 占位符渲染时会触发 Vue i18n 消息编译异常。用户虽然仍能看到弹窗，但控制台持续输出 `Invalid token in placeholder` 一类错误，影响真实浏览器端诊断与后续错误排查。

## 根因

`mcp.placeholders.toolsJson` 直接写入了 `[{...}]` JSON 示例。Vue i18n 会把 `{...}` 当作命名插值语法解析，而 JSON 对象中的 `"name":"docs_search"` 不是合法占位符名称，因此在 `t('mcp.placeholders.toolsJson')` 编译阶段报错。

## 修复

- 使用 Vue i18n 的 literal interpolation 写法包住完整 JSON 示例，让编译器把内容视为普通文本。
- 中文与英文 locale 同步修复，避免切换语言后复现同一错误。
- 新增 `mcpI18nPlaceholdersStatic.test.ts`，通过 `vue-i18n` 的 `t()` 覆盖真实编译和渲染路径。

## 验证

```bash
node --experimental-strip-types web\tests\mcpI18nPlaceholdersStatic.test.ts
node --experimental-strip-types web\tests\mcpDisplay.test.ts
npm --prefix web run build
```
