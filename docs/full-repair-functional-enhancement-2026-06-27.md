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

2. 会话搜索失败旧结果清理
   - 位置：`web/src/components/solonclaw/chat/SessionSearchModal.vue`
   - 改造前：
     - 最近会话或全文搜索请求失败后，弹窗可能继续展示上一轮旧结果。
   - 改造后：
     - 最近会话加载失败时清空最近会话列表并重置选中位置。
     - 全文搜索失败时清空搜索结果并重置选中位置。
   - 提交：`643888b0d`

3. 技能页加载失败提示增强
   - 位置：`web/src/views/solonclaw/SkillsView.vue`
   - 改造前：
     - 技能列表加载失败只写入控制台，用户看不到明确反馈。
     - 技能改进记录加载失败没有提示，刷新按钮只结束 loading。
   - 改造后：
     - 技能列表加载失败时展示错误提示，同时保留已有列表数据。
     - 技能改进记录加载失败时展示错误提示，同时保留已有改进记录。
   - 提交：`0959f176b`

4. MCP 服务切换 OAuth 状态清理
   - 位置：`web/src/views/solonclaw/McpView.vue`
   - 改造前：
     - 切换 MCP 服务时，OAuth 状态、授权链接和表单字段会短暂沿用上一个服务的数据。
   - 改造后：
     - 新增 `resetOAuthState()`，切换服务或无选中服务时先清理 OAuth 状态和表单。
     - 新服务的 OAuth 状态加载完成后再显示对应配置，避免旧状态误导。
   - 提交：`d5d456bd3`

5. 智能体页加载失败提示增强
   - 位置：`web/src/views/solonclaw/AgentsView.vue`
   - 改造前：
     - 页面初始化、刷新、选择智能体或切换会话重新加载智能体失败时，用户侧没有明确错误提示。
   - 改造后：
     - 页面加载、选择智能体和会话切换刷新失败时展示错误提示。
     - 失败时保留现有表单和列表状态，避免刷新失败导致已有数据被清空。
   - 提交：`3e58fe328`

## 验证

- `npm run build`：通过。
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`：通过。
- `git diff --check`：通过。

## 剩余风险

- 本文档只覆盖阶段 3.4 已完成的增强项，不代表阶段 3.4 全部完成。
- 当前工作树仍存在未纳入本阶段提交的 `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 本地改动。
