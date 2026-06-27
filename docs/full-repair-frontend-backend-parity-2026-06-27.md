# 阶段 2 前后端功能一致性修复记录

日期：2026-06-27

## 处理范围

- 阶段 2.1：后端已有但前端未使用的功能。
- 阶段 2.2：前端已有但后端没有的功能。
- 阶段 2.3：后端功能缺少 UI 入口的情况，已合并到 2.1 逐项处理。

## 已补齐的后端功能入口

1. 运行时审批事件
   - 后端接口：`/api/approval/events`、`/api/approval/stats`
   - 前端入口：诊断页新增运行时审批事件卡片。
   - 提交：`5b580abcf`

2. 运行时模型状态
   - 后端接口：`/api/models`、`/api/models/health`
   - 前端入口：模型页新增运行时模型状态面板。
   - 提交：`159d8a794`

3. 运行洞察统计
   - 后端接口：`/api/insights/overview`、`/api/insights/skills`
   - 前端入口：用量统计页新增运行洞察和技能使用明细。
   - 提交：`02bc0a945`

4. 技能改进记录
   - 后端接口：`/api/curator/improvements`、`/api/curator/apply`、`/api/curator/ignore`
   - 前端入口：Curator 页面新增技能改进记录，支持标记已应用或忽略。
   - 说明：后端 `apply/ignore` 当前语义是记录建议状态，不直接修改技能文件；前端文案按“标记”表达。
   - 提交：`8dafb8740`、本次提交

5. 平台工具集策略
   - 后端接口：`/api/tools/toolsets`、`/api/tools/platform-toolsets`、`/api/tools/platform-toolsets/{platform}`
   - 前端入口：渠道页新增平台工具集策略面板。
   - 提交：`c10a76358`

6. 通用会话搜索
   - 后端接口：`/api/search`
   - 前端入口：全局会话搜索弹窗改用通用搜索接口，保留原有弹窗交互。
   - 提交：`c0d8fe09e`

7. 媒体缓存下载与引用
   - 后端接口：`/api/media/{mediaId}/download`、`/api/media/{mediaId}/reference`
   - 前端入口：渠道页媒体详情抽屉新增下载引用和生成引用操作。
   - 提交：`bd0d53272`

8. 运行记录基础控制
   - 后端接口：`/api/runs/{runId}/control`
   - 前端入口：运行页详情面板新增停止、取消和可恢复运行的恢复按钮。
   - 说明：本次只接入 `stop`、`cancel`、`resume` 三个基础动作；子代理控制和 steer 文本输入后续单独处理。
   - 提交：`559a8c149`

9. 子代理运行查看与中断
   - 后端接口：`/api/runs/{runId}/subagents`、`/api/runs/subagents/active`、`/api/runs/subagents/{subagentId}/control`
   - 前端入口：运行页新增当前运行的子代理记录、右侧活跃子代理列表，以及活跃子代理中断操作。
   - 说明：本次只接入后端已有的 `interrupt` 控制，不新增子代理生成、暂停生成或调度策略入口。
   - 提交：`bb2e53036`

10. 模型运行时富状态
    - 后端接口：`/api/models`
    - 前端入口：模型页新增运行时模型状态区块，展示 provider、模型、协议、状态、上下文窗口、最大输出和价格字段。
    - 提交：`552a5397`

11. 检查点预览
    - 后端接口：`/api/checkpoints/{id}/preview`
    - 前端入口：运行页检查点列表新增预览按钮，右侧抽屉展示后端返回的检查点预览内容。
    - 提交：`cab1f2178`

12. 会话轨迹保存
    - 后端接口：`/api/sessions/{id}/trajectory/save`
    - 前端入口：运行页会话轨迹区块新增保存轨迹按钮，用于把当前轨迹样本追加到工作区 artifacts。
    - 提交：`6818f2edd`

13. 日志组件过滤
    - 后端接口：`/api/logs?component=...`
    - 前端入口：日志页新增组件过滤输入，前端日志 API 透传 `component` 参数。
    - 提交：`b8aad00f9`

14. 配置诊断只读入口
    - 后端接口：`/api/config/diagnostics`、`/api/config/schema`、`/api/config/raw`
    - 前端入口：设置页新增配置诊断卡片式页签，只读展示诊断结果、配置结构和原始配置。
    - 说明：本次只补齐只读诊断入口，不提供原始配置编辑或保存能力，避免绕过现有配置写入边界。
    - 提交：`b31691fc2`

15. 子代理生成暂停与恢复
    - 后端接口：`/api/runs/subagents/active`、`/api/runs/subagents/{subagentId}/control`
    - 前端入口：运行页活跃子代理面板新增暂停生成和恢复生成按钮，并展示当前暂停状态。
    - 说明：后端已有 `pause_spawn` / `resume_spawn` 控制，本次只补齐 Web 操作入口和状态透传。
    - 提交：本次提交

## 已补齐的后端接口缺口

1. 工作区文件下载
   - 前端引用：`/api/solonclaw/download`
   - 后端补齐：新增受控下载接口，只允许下载 `PersonaWorkspaceService` 暴露的固定工作区文件 key 或文件名。
   - 提交：`38d9fcdfd`

## 已收敛的前端超前入口

1. 文件页未支持写操作入口
   - 前端入口：文件工具栏的新建文件、新建文件夹、上传；文件右键菜单的重命名、删除。
   - 后端现状：`/api/workspace/files` 当前只开放受控工作区文件读取、保存、恢复、日记读取和下载；`web/src/api/solonclaw/files.ts` 对新建、上传、重命名、删除明确返回 unsupported。
   - 处理结果：文件页只保留读取、预览、编辑保存、刷新、复制路径和下载等已接通能力，不再展示会必然失败的写操作入口。

## 已确认无需新增 UI 的接口

- `/api/gateway/doctor`：与 `/api/diagnostics/doctor` 使用同一 doctor 服务，诊断页已通过后者接入。
- `/api/gateway/message`：消息网关注入入口，供外部渠道/签名调用，不属于 Dashboard UI 操作入口。
- `/api/tui/handshake`、`/api/tui/rpc`：TUI 专用接口，不属于 Web Dashboard 阶段 2 UI 缺口。
- `/api/chat/uploads`：前端已通过 `fetch(getBaseUrlValue() + "/api/chat/uploads")` 直接使用，脚本初筛误判。

## 验证

- 多次执行 `npm run build`，通过。
- `npm run build --prefix web`：2026-06-27 文件页未支持写操作入口收敛后通过。
- `npm run build --prefix web`：2026-06-27 媒体缓存下载与引用入口补齐后通过。
- `npm run build --prefix web`：2026-06-27 运行记录基础控制入口补齐后通过。
- `npm run build --prefix web`：2026-06-28 子代理运行查看与中断入口补齐后通过。
- `npm run build --prefix web`：2026-06-28 模型运行时富状态入口补齐后通过。
- `npm run build --prefix web`：2026-06-28 检查点预览入口补齐后通过。
- `npm run build --prefix web`：2026-06-28 会话轨迹保存入口补齐后通过。
- `npm run build --prefix web`：2026-06-28 日志组件过滤入口补齐后通过。
- `npm run build --prefix web`：2026-06-28 配置诊断只读入口补齐后通过。
- `npm run build --prefix web`：2026-06-28 子代理生成暂停与恢复入口补齐后通过。
- 多次执行 `git diff --check`，通过。
- `git diff --check -- web/src/components/solonclaw/files/FileToolbar.vue web/src/components/solonclaw/files/FileContextMenu.vue web/src/views/solonclaw/FilesView.vue docs/full-repair-frontend-backend-parity-2026-06-27.md`：通过。
- 执行 `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`，通过。
- 针对后端下载接口执行 `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DashboardControllerHttpTest test`，通过。

## 剩余风险

- 阶段 2 的接口差异扫描基于源码中的静态路径和人工复核，动态拼接路径已补充抽查；后续阶段如果新增 API，需要重新跑同类扫描。
- 当前工作树仍存在未纳入本阶段提交的 `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 本地改动，未被本阶段提交带入。
