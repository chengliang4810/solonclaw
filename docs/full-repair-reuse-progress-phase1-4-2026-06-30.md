# 阶段 1.4 代码重复复用优化进度记录

日期：2026-06-30

## 对应能力点

- Dashboard 展示层复用：把页面内重复的状态、时间、布尔、导航、传输方式等纯显示目录迁入 `web/src/shared/*`。
- 代码质量治理：页面只保留加载、保存、路由和交互流程，显示映射由共享 helper 或共享元数据目录维护。
- 阶段 7 记忆要求：记录本轮已提交原子项、验证命令、合并状态和剩余候选，便于后续上下文恢复。

## 本轮已完成原子项

### 1. 终端运行时显示目录复用

- 提交：`ef7d42fdd refactor: 复用终端运行时显示目录 / Reuse TUI runtime display catalog`
- 新增 `web/src/shared/tuiRuntimeDisplay.ts`，统一终端运行时页面的渠道状态、认证状态和必填配置摘要展示。
- `TuiRuntimeView.vue` 删除内联 `statusTone`、`statusLabel`、`providerAuthLabel`、`providerAuthColor`、`requiredSummary`。
- 新增 `web/tests/tuiRuntimeDisplay.test.ts` 和 `test:tui-runtime-display`。

### 2. 运行记录显示目录复用

- 提交：`9b3e33ff0 refactor: 复用运行记录显示目录 / Reuse run display catalog`
- 新增 `web/src/shared/runsDisplay.ts`，统一运行记录页面的时间、状态、布尔审计字段和会话产物展示。
- `RunsView.vue` 删除内联 `time`、`statusLabel`、`booleanLabel`、`artifactText`。
- 新增 `web/tests/runsDisplay.test.ts` 和 `test:runs-display`。

### 3. 系统导航目录复用

- 提交：`1e5340482 refactor: 复用系统导航目录 / Reuse system navigation catalog`
- `SYSTEM_NAV_ITEMS` 移入 `web/src/shared/sidebarNav.ts`，与主侧栏、监控入口和人格入口元数据同目录维护。
- `SystemNavItems.vue` 仅保留按钮壳、选中态和导航事件。
- 更新 `web/tests/systemNavItemsMetadataStatic.test.ts`，锁定系统导航不再内联路由键。

### 4. MCP 显示目录复用

- 提交：`fa768504f refactor: 复用 MCP 显示目录 / Reuse MCP display catalog`
- 新增 `web/src/shared/mcpDisplay.ts`，统一 MCP 传输方式选项、服务/OAuth 状态色和时间展示。
- `McpView.vue` 删除内联 `transportOptions`、`statusType`、`formatTime`。
- 新增 `web/tests/mcpDisplay.test.ts` 和 `test:mcp-display`。
- 由于 `antdv-next` 的 `Select` 需要可变 options 数组，共享层提供 `mcpTransportOptions()` 返回拷贝，避免组件改写共享常量。

## 验证记录

本轮每个原子项提交前均执行了对应窄测试、类型检查、前端构建、diff 检查和命名门禁。覆盖过的命令包括：

```bash
npm --prefix web run test:tui-runtime-display
npm --prefix web run test:tui-runtime-ui
npm --prefix web run test:runs-display
node --experimental-strip-types web/tests/runDetailExtendedStatic.test.ts
node --experimental-strip-types web/tests/sessionArtifactsUiStatic.test.ts
npm --prefix web run test:system-nav-items-metadata
npm --prefix web run test:app-sidebar-nav-metadata
node --experimental-strip-types web/tests/gatewayNavigationStatic.test.ts
npm --prefix web run test:mcp-display
node --experimental-strip-types web/tests/mcpAsyncReloadStatic.test.ts
npx vue-tsc -b --noEmit --pretty false
npm --prefix web run build
git diff --check
python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
```

验证结论：

- 相关静态测试通过。
- `vue-tsc` 类型检查通过。
- `web` 构建通过；构建过程中出现过 Vite 插件耗时提示，不影响构建结果。
- whitespace 检查通过。
- 项目命名门禁通过。

## 分支同步状态

- 工作分支：`work/full-repair-optimization`
- 本轮最新提交：`fa768504f`
- 本地 `dev` 已通过 `git merge --ff-only <worktree HEAD>` 快进到同一提交。
- `/Users/chengliang/code-projects/jimuqu-agent` 的 `dev` checkout 仍有用户既有未提交的 TUI/Java 改动，本轮未修改、未暂存、未回退这些文件。
- `.omo/`、`.playwright-cli/`、`output/`、`web/dist/` 属于本轮中间状态或构建产物，不纳入提交。

## 剩余候选

| 候选 | 当前判断 | 建议 |
| --- | --- | --- |
| `web/src/components/solonclaw/jobs/JobCard.vue` | 状态、动作摘要、别名摘要、徽标和投递目标展示仍有可抽取逻辑。 | 下一轮可继续扩展 `web/src/shared/jobsDisplay.ts`，每次只抽一类纯展示 helper。 |
| `web/src/views/solonclaw/AgentsView.vue`、`ChannelsView.vue`、`CuratorView.vue` | 多处仍有本地 `formatTime`。 | 需要先确认各页面空态、秒/毫秒和本地化展示语义，再决定是否统一。 |
| `web/src/views/solonclaw/McpView.vue` | 页面仍偏大，但本轮只抽纯显示目录。 | 后续若继续处理，优先抽工具卡片展示或 OAuth 表单字段元数据，避免一次性拆页面结构。 |
| `web/src/components/solonclaw/models/*LoginModal.vue` | 设备码登录流程相似。 | 需要 UI 交互验证，建议作为独立高风险一点的复用项处理。 |

## 阶段状态

阶段 1.4 已在前端展示层连续完成四个低风险复用原子项，并全部提交、验证、快进合并到本地 `dev`。后续可继续沿 `JobCard.vue` 的展示 helper 做小步复用，或按阶段顺序进入后续已规划的前后端一致性和功能融合项。
