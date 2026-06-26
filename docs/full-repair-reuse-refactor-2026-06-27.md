# 阶段 3.3 功能复用改造记录

日期：2026-06-27

## 对应能力点

- 对应本地 Dashboard / Web 控制台能力：渠道运行时配置。
- 本阶段只记录已经完成并提交的复用改造；后续复用候选继续逐项处理。

## 已处理项

1. 渠道凭证展示与保存共用字段映射
   - 位置：`web/src/api/solonclaw/config.ts`
   - 改造前：
     - `saveCredentials()` 已使用 `CHANNEL_CREDENTIAL_FIELDS` 保存凭证。
     - `fetchConfig()` 仍手写各平台 `app_id`、`app_secret`、`client_secret`、`token` 等展示字段。
   - 改造后：
     - 新增 `platformCredentials()`，读取展示也复用 `CHANNEL_CREDENTIAL_FIELDS`。
     - 保持渠道 `enabled` 展示仍来自结构化配置。
     - 保持凭证预览仍来自 `/api/workspace-config` 的脱敏值。
   - 提交：`626834703`

2. 用量 Token 展示格式复用
   - 位置：
     - `web/src/shared/usageFormat.ts`
     - `web/src/components/solonclaw/usage/StatCards.vue`
     - `web/src/components/solonclaw/usage/DailyTrend.vue`
     - `web/src/components/solonclaw/usage/ModelBreakdown.vue`
   - 改造前：
     - 三个用量组件各自实现相同的 `formatTokens()`。
   - 改造后：
     - 新增 `formatUsageTokens()`，三个组件统一复用。
     - 暂不合并 `formatCost()`，因为统计卡片和趋势/模型列表对零成本的展示语义不同。
   - 提交：`06da95f76`

3. 聊天附件展示格式复用
   - 位置：
     - `web/src/shared/attachmentFormat.ts`
     - `web/src/components/solonclaw/chat/ChatInput.vue`
     - `web/src/components/solonclaw/chat/MessageItem.vue`
   - 改造前：
     - 输入区和消息项组件分别实现相同的附件大小格式化与图片 MIME 判断。
   - 改造后：
     - 新增 `formatAttachmentSize()` 与 `isImageMimeType()`，两个聊天组件统一复用。
     - 暂不合并 `FileList.vue` 的文件大小格式化，因为空值和 0 字节展示语义不同。
   - 提交：`3b85d545b`

4. 粘贴图片类型判断复用
   - 位置：`web/src/components/solonclaw/chat/ChatInput.vue`
   - 改造前：
     - 附件预览已复用 `isImageMimeType()`，粘贴图片过滤仍直接使用 `startsWith('image/')`。
   - 改造后：
     - 粘贴图片过滤统一复用 `isImageMimeType()`。
   - 提交：`17318e76d`

## 验证

- `npm run build`：阶段内多次通过。
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`：通过。
- `git diff --check`：通过。

## 剩余风险

- 本文档只覆盖阶段 3.3 已完成的复用改造项，不代表阶段 3.3 全部完成。
- 当前工作树仍存在未纳入本阶段提交的 `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 本地改动。
