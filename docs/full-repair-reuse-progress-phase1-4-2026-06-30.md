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

### 5. 任务徽标展示复用

- 提交：`342451475 refactor: 复用任务徽标显示 / Reuse job badge display`
- `web/src/shared/jobsDisplay.ts` 新增 `jobBadges`，统一任务卡片徽标的触发来源、技能数、上游任务数、工具集数和模型固定展示。
- `JobCard.vue` 删除本地徽标数组构造，保留模板展示结构不变。
- `web/tests/jobsDisplay.test.ts` 增加顺序、计数、空值和 `provider:model` 拼接断言。

### 6. 会话切换展示规则复用

- 提交：`5509fc5e8 refactor: 复用会话切换展示规则 / Reuse session switcher display rules`
- 新增 `terminal-ui/src/components/activeSessionDisplay.ts`，集中维护会话切换器计数文案、提示段、提示颜色和选中行样式。
- `activeSessionSwitcher.tsx` 通过 re-export 保持原测试与外部引用路径稳定，只移出纯展示规则。

### 7. 通道设置展示文案复用

- 提交：`fff9b034c refactor: 复用通道设置展示文案 / Reuse channel setup display labels`
- `channelSetupViews.tsx` 新增 `channelSetupListRowLabel` 和 `channelSetupFieldValueLabel`，统一通道配置状态、QR 能力标签和密文字段遮罩。
- `channelQr.test.ts` 增加通道行文案、密文字段空值和 40 字符遮罩上限断言。

### 8. MCP 摘要显示复用

- 提交：`f44d37b11 refactor: 复用 MCP 摘要显示 / Reuse MCP summary display`
- `branding.tsx` 新增 `connectedMcpServerCount` 和 `mcpHeadlineSuffix`，确保标题摘要只统计已连接 MCP 服务。
- `brandingMcpCount.test.ts` 增加纯 helper 断言，并保留 SessionPanel 渲染回归。

### 9. 折叠标题元信息复用

- 提交：`66c656066 refactor: 复用折叠标题元信息 / Reuse collapse title metadata`
- `branding.tsx` 新增 `collapseToggleMeta`，统一折叠标题的 `count + suffix` 文本拼接。
- `CollapseToggle` 不改变布局和状态，仅改为调用纯展示 helper。

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
node --experimental-strip-types web/tests/jobsDisplay.test.ts
npm --prefix web run test:job-form-options
npm --prefix terminal-ui test -- activeSessionSwitcher.test.ts
npm --prefix terminal-ui test -- channelQr.test.ts
npm --prefix terminal-ui test -- brandingMcpCount.test.ts
npm --prefix terminal-ui run type-check
npm --prefix terminal-ui run build
mvn -Dskip.web.build=true -DskipTests compile
npx vue-tsc -b --noEmit --pretty false
npm --prefix web run build
git diff --check
python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
```

验证结论：

- 相关静态测试通过。
- `vue-tsc` 类型检查通过。
- `terminal-ui` type-check 和本轮 TUI 聚焦测试通过。
- Java 编译通过；仅保留既有 deprecation / unchecked 提示。
- `web` 构建通过；构建过程中出现过 Vite 插件耗时提示，不影响构建结果。
- whitespace 检查通过。
- 项目命名门禁通过。

## 分支同步状态

- 工作分支：`work/full-repair-optimization`
- 本轮最新开发提交：`66c656066`
- 本地 `dev` 已通过 `git merge --ff-only <worktree HEAD>` 快进到同一提交，并已推送到 `origin/dev`。
- 达到 5 次有效开发提交后，已通过临时干净 worktree 将 `dev` 合并到 `main`。
- `origin/main` 最新 merge commit：`95ab67312 merge: 合并 dev 阶段修复 / Merge dev phase fixes`
- `main` 与 `dev` 的文件树一致；合并 commit 不计入下一轮 5 次开发提交。
- `/Users/chengliang/code-projects/jimuqu-agent` 的 `dev` checkout 只剩 `.omo/` 未跟踪目录，本轮未纳入提交。
- `gitee/dev` 与本地 `dev` 存在分叉，本轮未执行非快进推送。
- `.omo/`、`.playwright-cli/`、`output/`、`web/dist/` 属于本轮中间状态或构建产物，不纳入提交。

## 剩余候选

| 候选 | 当前判断 | 建议 |
| --- | --- | --- |
| `web/src/components/solonclaw/jobs/JobCard.vue` | 状态、动作摘要、别名摘要、徽标和投递目标展示仍有可抽取逻辑。 | 下一轮可继续扩展 `web/src/shared/jobsDisplay.ts`，每次只抽一类纯展示 helper。 |
| `web/src/views/solonclaw/AgentsView.vue`、`ChannelsView.vue`、`CuratorView.vue` | 多处仍有本地 `formatTime`。 | 需要先确认各页面空态、秒/毫秒和本地化展示语义，再决定是否统一。 |
| `web/src/views/solonclaw/McpView.vue` | 页面仍偏大，但本轮只抽纯显示目录。 | 后续若继续处理，优先抽工具卡片展示或 OAuth 表单字段元数据，避免一次性拆页面结构。 |
| `web/src/components/solonclaw/models/*LoginModal.vue` | 设备码登录流程相似。 | 需要 UI 交互验证，建议作为独立高风险一点的复用项处理。 |
| `terminal-ui/src/components/channelSetupViews.tsx` | 仍可抽通用展示容器，但会触碰多处 JSX 结构。 | 若继续处理，先只迁移 Error / Empty / Saved 三个低风险视图，并补渲染断言。 |
| `terminal-ui/src/components/branding.tsx` | Banner、列表截断和面板 body 仍有可读性优化空间。 | 不建议在 phase 1.4 中继续抽布局；后续只做有测试支撑的纯文本 helper。 |

## 阶段状态

阶段 1.4 已在 Web Dashboard 和 TUI 展示层连续完成低风险复用原子项，并全部提交、验证、快进合并到 `dev`。2026-06-30 15:35 左右已按 5 次有效开发提交规则合并并推送到 `origin/main`。下一轮开发提交计数从 `95ab67312` 之后重新开始；后续可继续做剩余小型展示 helper，或收口阶段 1.4 后进入阶段 2。
