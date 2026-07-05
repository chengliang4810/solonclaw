# MCP OAuth 授权链接复制失败问题修复报告

## 问题现象

MCP 页面生成 OAuth 授权链接后，用户点击“复制授权链接”时，页面直接调用浏览器 Clipboard API。若当前环境不是安全上下文、浏览器不支持 `navigator.clipboard`、权限被拒，或测试环境没有该 API，复制操作会失败并可能抛出未处理错误。

## 影响范围

- 影响 Web MCP 管理页面的 OAuth 授权链接复制功能。
- 影响无 Clipboard API 或权限受限环境下的用户反馈。
- 不影响其它已经使用 `copyToClipboard()` 的复制入口。

## 根因

`web/src/views/solonclaw/McpView.vue` 的 `copy()` 函数绕过了项目已有的 `web/src/utils/clipboard.ts` 安全封装，直接执行 `navigator.clipboard.writeText(text)`。该直接调用没有安全上下文检查，也没有把权限失败转换为可控的用户提示。

## 修复方案

复用现有 `copyToClipboard()`：

- 成功时显示 `common.copied`。
- 失败时显示复制失败提示。
- MCP 页面不再直接调用 `navigator.clipboard.writeText()`。

## 回归测试

更新 `web/tests/mcpDisplay.test.ts`，静态检查 MCP 页面必须复用 `@/utils/clipboard`，并禁止直接调用 `navigator.clipboard.writeText`。

验证命令：

```powershell
npm run test:mcp-display
npx vue-tsc -p tsconfig.app.json --noEmit --incremental false
npm run build
```

结果：三个验证命令均通过。
