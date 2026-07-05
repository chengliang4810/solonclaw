# TUI Setup 必要操作浮层不可见问题报告

## 问题

TUI 处于 `setup required` 状态时，界面提示可输入 `/model`、`/model pick`、`/setup`，命令提交后状态已打开对应 overlay，但模型选择器或设置面板没有可见渲染。用户只能看到命令回显，后续按 `q` 又会关闭不可见浮层。

## 根因

`FloatingOverlays` 以 `position="absolute"` 和 `bottom="100%"` 挂在 composer prompt 上方。在无会话 setup-required 屏幕中，浮层不参与正常布局，容易被 prompt/transcript 组合挤到不可见区域。

## 修复

将 `FloatingOverlays` 改为正常文档流渲染，并保持 `flexShrink={0}`，让模型选择器、设置面板、会话切换等命令浮层在 setup-required 屏幕也占据可见高度。

## 验证

- `npm test -- src/__tests__/appOverlaysLayoutStatic.test.ts src/__tests__/createSlashHandler.test.ts src/__tests__/setupPanel.test.ts`
- `npm run type-check`
