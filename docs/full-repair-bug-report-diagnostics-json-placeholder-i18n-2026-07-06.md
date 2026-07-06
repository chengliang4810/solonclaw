# 诊断页 JSON 参数占位符触发 i18n 编译错误

## 范围

- Dashboard 的系统诊断页面。
- 安全审计表单中的参数 JSON 输入框。
- 中文与英文界面的 `diagnostics.auditArgsPlaceholder` 文案。

## 现象

系统诊断页面渲染安全审计表单时，参数 JSON 输入框的占位符会经过 Vue i18n 编译。占位符直接包含 `{"url":"https://example.com"}` 时，控制台会输出 `Invalid token in placeholder` 编译错误，导致真实浏览器调试时出现噪声并掩盖页面上的其他问题。

## 根因

Vue i18n 会把消息文本中的 `{...}` 解析为命名插值。JSON 示例中的 `"url":"https://example.com"` 不是合法的插值名称，所以 `t('diagnostics.auditArgsPlaceholder')` 在编译该消息时失败。

## 修复

- 使用 Vue i18n literal interpolation 包住完整 JSON 示例，保留用户看到的占位符文本不变。
- 中文与英文 locale 同步修复。
- 新增全 locale 消息编译静态测试，确保所有已注册语言的消息都能被 `vue-i18n` 编译。

## 验证

```bash
node --experimental-strip-types web\tests\i18nMessagesCompileStatic.test.ts
node --experimental-strip-types web\tests\mcpI18nPlaceholdersStatic.test.ts
npm --prefix web run build
```
