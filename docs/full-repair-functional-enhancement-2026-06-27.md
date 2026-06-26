# 阶段 3.4 功能增强记录

日期：2026-06-27

## 对应能力点

- 对应本地 Dashboard / Web 控制台能力：运行记录、事件时间线、分支树与 checkpoint 回滚。
- 本阶段只记录已经完成并提交的增强项。

## 已处理项

1. 运行视图失败反馈增强
   - 位置：`web/src/views/solonclaw/RunsView.vue`
   - 改造前：
     - 运行记录、事件详情或 checkpoint 回滚接口失败时，页面可能只保留旧数据或进入空状态，没有明确错误提示。
   - 改造后：
     - 加入 `useMessage()` 失败提示。
     - 运行详情加载失败时清理运行列表、事件、工具、分支树和 checkpoint 数据，避免旧数据误导用户。
     - 单条运行详情加载失败时清理事件和工具详情。
     - checkpoint 回滚失败时提示错误，并释放按钮 loading 状态。
   - 提交：`c43cba116`

## 验证

- `npm run build`：通过。
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`：通过。
- `git diff --check`：通过。

## 剩余风险

- 本文档只覆盖阶段 3.4 已完成的第一项，不代表阶段 3.4 全部完成。
- 当前工作树仍存在未纳入本阶段提交的 `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 本地改动。
