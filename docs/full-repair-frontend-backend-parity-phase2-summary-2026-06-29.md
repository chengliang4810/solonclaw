# 阶段 2 前后端功能一致性收口记录

日期：2026-06-29

## 对应能力点

- Dashboard-first setup：渠道配置、二维码 setup、配置诊断和运行时诊断必须通过真实后端能力落地。
- 本地交互式 TUI：终端运行态必须有 Web 侧只读观察入口，便于排查连接、会话和运行状态。
- Agent 运行态观察：审批事件、默认配置、运行时配置和前端入口必须与后端控制器保持一致。

## 阶段 2.1 收口

阶段 2.1 处理“后端已有但前端完全未使用”的功能入口，并把阶段 1.1 确认的 BUG-008 纳入修复。

已完成：

1. 渠道响应策略字段对齐。
   - 提交：`8dafc5af8 fix: 对齐渠道响应策略配置 / Align channel response policy settings`
   - 结果：Dashboard 渠道设置写入后端真实读取的策略字段，避免 `require_mention`、`free_response_chats` 这类前端字段被保存但不生效。
   - 关联后端：`AppConfig.ChannelConfig`、`AppConfigLoader`、`RuntimeConfigResolver`、国内渠道适配器。
   - 关联前端：`PlatformSettings.vue`。

2. 终端运行时 Web 入口。
   - 提交：`2e2ed2338 feat: 补齐终端运行时 Web 入口 / Add TUI runtime web entry`
   - 结果：新增 TUI runtime 只读页面、路由、导航入口和静态覆盖测试，Web Dashboard 可以查看终端运行态。
   - 关联前端：`TuiRuntimeView.vue`、`tuiRuntime.ts`、`SystemNavItems.vue`、`tuiRuntimeUiStatic.test.ts`。

## 阶段 2.2 收口

阶段 2.2 处理“前端已有但后端完全没有”的功能缺口。

已完成：

- 提交：`aa93dbb22 docs: 记录前端超前接口审计 / Record frontend-backend API gap audit`
- 文档：`docs/full-repair-frontend-backend-parity-phase2-2-2026-06-29.md`
- 结论：当前 Web Dashboard 调用面未发现新的真实后端缺口；机器初筛候选均经动态路径和控制器映射复核后判定为误报或非 Web 运行时缺口。

## 阶段 2.3 收口

阶段 2.3 复核“后端已有但 UI 界面缺失”的情况，并补齐仍缺少入口的只读功能。

已完成：

1. 运行时审批事件只读入口。
   - 提交：`51e4f4c11 feat: 补齐后端只读功能入口 / Add read-only backend feature entries`
   - 后端入口：`/api/approval/events`
   - 前端入口：诊断页新增运行时审批事件面板，支持刷新、数量展示、决策状态和事件明细展示。
   - 覆盖测试：`web/tests/backendUiCoverageStatic.test.ts`

2. 默认配置只读展示。
   - 同一提交：`51e4f4c11`
   - 后端入口：`/api/config/defaults`
   - 前端入口：设置页配置诊断区域新增“默认配置”只读展示，与诊断、结构和原始配置一起加载。

## 已确认暂不扩张的项

- 工作区配置的 reveal 和 delete 操作：`AccountSettings.vue` 已有查看和清除 token 的真实入口，不重复新增页面。
- 定时任务 guide 和 policy：已有定时任务管理页面、状态和策略相关展示，本阶段不扩大为新的编辑体验。
- 模型 health 主动诊断：模型页面已有运行时状态展示，本阶段不把只读状态扩张为主动诊断工作流。
- TUI 专用 handshake 和 RPC 写入语义：TUI 客户端专用协议不作为 Web Dashboard 通用操作入口。

## 验证记录

阶段 2.3 收口提交前已执行：

- `npm --prefix web run test:backend-ui-coverage`：通过。
- `npm --prefix web run build`：通过。
- `git diff --check`：通过。
- TypeScript no-excuse 规则脚本：通过；脚本只报告识别到的 TypeScript 文件，Vue 文件由 `vue-tsc` 构建覆盖。
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`：通过。

阶段 2 汇总文档提交前已单独执行文档级 `git diff --check` 和命名门禁。

## 当前结论

阶段 2 已按 2.1、2.2、2.3 顺序完成当前源码面的前后端一致性修复与审计。后续如果新增 Dashboard API、前端 API 包装或页面直接请求，需要重新运行同类前后端映射检查。

下一阶段进入阶段 3.1：功能重复检测，重点从页面、组件、API 包装、国内渠道适配、二维码 setup、文件/人格视图、危险命令审批测试辅助方法等区域继续扫描并列出重复功能清单。
