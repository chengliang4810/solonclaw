# Web 文件列表移动端操作按钮隐藏缺陷报告

日期：2026-07-06

## 对应外部对标能力点

- Dashboard / Web UI 文件管理：文件行应提供编辑、下载等直接操作入口。
- 移动端可用性：触屏设备不能依赖 hover 才暴露关键操作。

## 现象

文件列表行操作区域 `.file-actions` 默认 `opacity: 0`，只在 `.file-list-row:hover` 时显示。移动端媒体块隐藏了大小和时间列，但没有恢复操作按钮可见性，触屏用户难以发现编辑和下载入口。

## 根因

桌面 hover 交互被直接复用到移动端；移动端 CSS 没有为无 hover 场景提供显式可见状态。

## 修复

- 在 `@media (max-width: $breakpoint-mobile)` 中设置 `.file-actions { opacity: 1; }`。
- 新增静态测试，确保移动端媒体块继续显式显示文件行操作按钮。

## 验证

```powershell
node --experimental-strip-types tests/fileListMobileActionsStatic.test.ts
node --experimental-strip-types tests/fileContextMenuActions.test.ts
npx vue-tsc -p tsconfig.app.json --noEmit --incremental false
```

结果：新增测试在修复前失败，修复后上述验证均通过。
