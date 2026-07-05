# Web Node 版本警告缺失多语言翻译缺陷报告

日期：2026-07-06

## 对应外部对标能力点

- Dashboard / Web UI 运行环境提示：低版本 Node.js 的风险提示应在所有已注册语言中稳定显示。
- 多语言界面一致性：非中文语言不能显示原始 i18n key。

## 现象

`App.vue` 的 Node 版本警告条使用 `sidebar.nodeVersionWarning`。中文 locale 已定义该 key，但英文、日文、韩文、法文、西班牙文、德文和葡萄牙文缺失，切换语言后警告条会显示缺失 key 或 fallback 文本。

## 根因

新增警告条时只补了中文文案，没有同步所有已注册 locale。

## 修复

- 为 `en`、`ja`、`ko`、`fr`、`es`、`de`、`pt` 补齐 `sidebar.nodeVersionWarning`。
- 新增静态测试，确保 `App.vue` 引用的 Node 版本警告 key 在所有 locale 中存在并保留 `{version}` 占位符。

## 验证

```powershell
node --experimental-strip-types tests/nodeVersionWarningI18nStatic.test.ts
node --experimental-strip-types tests/i18nRegistrationStatic.test.ts
npx vue-tsc -p tsconfig.app.json --noEmit --incremental false
```

结果：新增测试在修复前失败，修复后上述验证均通过。
