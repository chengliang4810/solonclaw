# Web 运行记录保存轨迹空状态缺陷报告

日期：2026-07-06

## 对应能力点

- Agent 核心与会话：运行记录、会话轨迹查看与保存。
- Dashboard-first 诊断与操作入口：空状态下的操作按钮不能表现为无响应。

## 现象

在 Dashboard 的运行记录页面没有选中会话时，右侧“会话轨迹”区域仍显示可点击的“保存轨迹”按钮。点击后不会发起请求，也没有错误提示或状态变化，用户会看到一个看似失效的按钮。

## 根因

`RunsView.vue` 的 `handleSaveTrajectory()` 在 `selectedSessionId` 为空时直接返回，但模板中的按钮只绑定了 loading 状态，没有绑定禁用状态。

## 修复

给“保存轨迹”按钮增加 `:disabled="!selectedSessionId"`，使空状态与实际可执行条件一致。

## 验证

- 红测：`node --experimental-strip-types tests\sessionArtifactsUiStatic.test.ts` 在修复前因缺少禁用条件失败。
- 修复后需通过同一静态测试，并运行 Web 构建确认模板可编译。
