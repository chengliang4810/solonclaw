# Web 输入框清空按钮属性 Bug 报告

## 对应能力

- Web UI / UX：搜索框和配置输入框应展示可用的清空交互，避免用户手动删除已有输入。

## 现象

- 多个 Web 页面在 `Input` 或 `InputNumber` 上使用 `clearable`。
- 当前 `antdv-next` 组件使用 `allowClear` / `allow-clear`，`clearable` 不会生效。
- 用户在技能搜索、会话搜索、模型搜索和平台配置输入框中看不到预期的清空按钮。

## 根因

- `clearable` 是错误组件属性名。
- `InputNumber` 在当前 `antdv-next` 类型中也不支持 `allowClear`，因此原 `clearable` 是无效属性。

## 修复

- 将 `Input` 上的 `clearable` 替换为 `allow-clear`。
- 删除 `InputNumber` 上无效的 `clearable`。
- 扩展 `antdDeprecatedPropsStatic.test.ts`，让静态测试可以扫描多行组件标签，防止同类属性再次进入源码。

## 验证

- `node --experimental-strip-types tests/antdDeprecatedPropsStatic.test.ts`
- `rg -n "\\bclearable\\b|allow-clear|allowClear" web/src web/tests`
