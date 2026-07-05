# Web 聊天附件下载 Tooltip 缺失翻译缺陷报告

日期：2026-07-06

## 对应外部对标能力点

- Dashboard / Web UI 聊天交互：附件展示与下载入口应提供稳定、可本地化的用户提示。
- 国内用户优先体验：中文界面不得显示缺失的翻译 key。

## 现象

聊天消息里的文件附件下载区域使用 `t('download.downloadFile')` 作为 tooltip，但当前各 locale 的 `download` 分组只有通用 `download` 文案，没有 `downloadFile`。因此浏览器 tooltip 或 i18n fallback 可能显示原始缺失 key。

## 根因

组件引用了不存在的 i18n key。相同语义已有 `download.download`，无需新增多语言文案。

## 修复

- `MessageItem.vue` 的附件下载 tooltip 改为 `t('download.download')`。
- 新增静态测试，防止该组件再次引用缺失的 `download.downloadFile`。

## 验证

```powershell
node --experimental-strip-types tests/messageAttachmentDownloadTooltipStatic.test.ts
node --experimental-strip-types tests/i18nRegistrationStatic.test.ts
npx vue-tsc -p tsconfig.app.json --noEmit --incremental false
```

结果：新增测试在修复前失败，修复后上述验证均通过。
