# Web 语言切换持久化问题报告

## 问题

Web 侧已有 `LanguageSwitch` 会把用户选择写入 `solonclaw_locale`，但 i18n 初始化固定使用 `zh`，且侧边栏未挂载语言切换控件。用户刷新页面后语言选择丢失，也无法从主界面直接切换语言。

## 根因

`web/src/i18n/index.ts` 未读取已持久化的 locale；`web/src/components/layout/AppSidebar.vue` 只展示主题切换，没有复用已有语言切换组件。

## 修复

- i18n 初始化时读取并校验 `solonclaw_locale`，无效值回退中文。
- 在侧边栏状态行复用已有 `LanguageSwitch`。
- 扩展 `i18nRegistrationStatic`，覆盖持久化读取与可达入口。

## 验证

- `node --experimental-strip-types tests\i18nRegistrationStatic.test.ts`
- `npx vue-tsc -p tsconfig.app.json --noEmit --incremental false`
