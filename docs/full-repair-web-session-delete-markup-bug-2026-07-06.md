# Web 会话删除按钮嵌套交互元素缺陷报告

日期：2026-07-06

## 对应外部对标能力点

- Dashboard / Web UI 会话管理：会话列表选择与删除操作必须稳定可用。
- 可访问性与浏览器兼容性：交互元素结构应符合 HTML 语义，避免浏览器自动修复导致点击目标不稳定。

## 现象

`SessionListItem.vue` 使用外层 `<button class="session-item">` 包裹整个会话行，同时内部删除确认入口又渲染 `<button class="session-item-delete">`。按钮嵌套按钮是无效 HTML，不同浏览器可能重排 DOM，影响删除 popconfirm 和点击命中。

## 根因

会话行选择和删除都使用原生 `button`，但没有把外层行改为可包含按钮的语义容器。

## 修复

- 外层会话行改为 `div role="button"`，保留 `tabindex="0"`、点击、右键、Enter 和 Space 键选择能力。
- 内部删除按钮保留原生 `<button>`，并补充 `type="button"`。
- 新增静态测试，禁止会话列表行再次出现嵌套按钮结构。

## 验证

```powershell
node --experimental-strip-types tests/sessionListItemMarkupStatic.test.ts
node --experimental-strip-types tests/sessionSelection.test.ts
npx vue-tsc -p tsconfig.app.json --noEmit --incremental false
```

结果：新增测试在修复前失败，修复后上述验证均通过。
