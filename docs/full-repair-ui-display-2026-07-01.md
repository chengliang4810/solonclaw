# 阶段 5 UI/UX 与数据显示增量记录

日期：2026-07-01

## 对应外部对标能力点

- 定时任务管理：Dashboard 应优先展示可操作的任务列表、即将运行项和失败摘要。
- 数据呈现优化：同一页内避免重复展示同一批下次运行数据，减少用户扫读成本。

## 已完成项

### 1. 定时任务页摘要去重

- 位置：
  - `web/src/views/solonclaw/JobsView.vue`
  - `web/src/i18n/locales/zh.ts`
  - `web/src/i18n/locales/en.ts`
  - `web/tests/jobsDisplay.test.ts`
- 改造前：
  - 页面顶部保留教学式 hero，占用首屏空间。
  - 状态摘要中展示“下次运行”，下方 `JobsPanel` 又有即将运行列表，信息重复。
- 改造后：
  - 删除教学式 hero，页面直接进入状态摘要和任务列表。
  - 状态摘要只保留任务计数与最近失败；下次运行明细交给即将运行列表展示。
  - 删除中英文无用文案键，并用静态测试防止重复摘要回流。

## 验证

```bash
node --experimental-strip-types web/tests/jobsDisplay.test.ts
npm --prefix web run build
git diff --check -- web/src/views/solonclaw/JobsView.vue web/src/i18n/locales/zh.ts web/src/i18n/locales/en.ts web/tests/jobsDisplay.test.ts
```
