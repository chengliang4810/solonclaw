# TUI Setup 初始配置状态提示修复

## 问题

TUI 执行 `/setup` 打开设置面板时，面板在 `setup.status` RPC 返回前会短暂显示：

- `模型：未配置`
- `提供方：（未设置）`
- `当前模型：（未设置）`
- `配置文件：（未知）`

如果后台随后返回正常配置，用户会看到一次错误的未配置闪烁。

## 根因

`setupStatusLines(null)` 把“尚未加载”的状态当成真实未配置状态渲染。`SetupPanel` 初始 `status` 为 `null`，所以 RPC 未返回前必然显示未配置。

## 修复

将 `null` 状态改为明确的加载提示：`正在读取配置状态...`。真实未配置状态仍由 `{ provider_configured: false }` 渲染，原有缺配置提示不变。

## 验证

```bash
npm test -- --run src/__tests__/setupPanel.test.ts
```
