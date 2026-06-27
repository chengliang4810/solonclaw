# 全面修复阶段 1.1 原子级功能缺陷报告

生成时间：2026-06-26

本报告只记录当前源码可以直接证明的功能缺陷或功能缺口。历史计划中已经被当前实现覆盖的事项，不在本报告中重复列为 bug。

## BUG-001：处理状态表情回应缺少 Dashboard 配置入口

状态：已修复，提交 `6a86d4488`

影响范围：

- 飞书、钉钉等渠道的消息处理状态反馈。
- Dashboard 配置与渠道能力可见性。

当前事实：

- 后端已经存在处理状态表情回应生命周期：`ChannelAdapter.onProcessingStart`、`ChannelAdapter.onProcessingComplete`、`DefaultGatewayService.safeProcessingStart`、`DefaultGatewayService.safeProcessingComplete`。
- 飞书和钉钉适配器已经实现了处理开始、处理完成后的表情回应逻辑。
- Web 侧只存在 `reactions` / `reactionsHint` 翻译文案，没有找到对应的配置表单、开关或运行时配置字段。
- `DashboardConfigService` 已暴露 `proactive` 等配置分类，但没有处理状态表情回应相关字段。

可复现现象：

1. 打开 Dashboard 设置或渠道配置。
2. 无法看到“处理状态表情回应”的启用/禁用入口。
3. 渠道侧如果平台权限、审计策略或用户偏好不允许表情回应，只能依赖后端固定行为，无法从 Dashboard 调整。

源码证据：

- `src/main/java/com/jimuqu/solon/claw/core/service/ChannelAdapter.java`
- `src/main/java/com/jimuqu/solon/claw/gateway/service/DefaultGatewayService.java`
- `src/main/java/com/jimuqu/solon/claw/gateway/platform/feishu/FeishuChannelAdapter.java`
- `src/main/java/com/jimuqu/solon/claw/gateway/platform/dingtalk/DingTalkChannelAdapter.java`
- `web/src/i18n/locales/zh.ts`
- `src/main/java/com/jimuqu/solon/claw/web/DashboardConfigService.java`

建议修复阶段：阶段 2.3 或阶段 5.2。已在阶段 5.2 增加 Dashboard schema 开关和网关运行时开关。

最小修复方向：

- 在后端配置 schema 中增加处理状态表情回应开关，至少支持全局启停。
- 如需按渠道细分，应复用现有渠道平台配置结构，不新增平行配置体系。
- Dashboard 继续走现有动态配置 schema，不单独硬编码一个新设置页。

## BUG-002：TUI Markdown 表格单元格会丢失行内格式

状态：已修复，提交 `ad80c1fb7`、`d6f197c42`

影响范围：

- TUI 中模型输出、工具输出、文档摘要等 Markdown 表格展示。
- 表格单元格内的加粗、代码、链接、删除线、数学片段等行内格式。

当前事实：

- 表格渲染为了计算列宽，会先对单元格调用 `stripInlineMarkup`。
- 非换行表格路径已复用 `MdInline` 渲染单元格内容，只用 `stripInlineMarkup` 计算可见宽度。
- 换行路径已按可见宽度拆分单元格，同时保留原始 Markdown token 交给 `MdInline` 渲染。
- 窄宽度和非换行路径均已有回归测试覆盖。

可复现现象：

输入：

```markdown
| 名称 | 说明 |
| --- | --- |
| `solonclaw` | **重要** [文档](https://solon.noear.org) |
```

修复前 TUI 表格会显示纯文本，代码样式、加粗样式和链接样式丢失。当前已覆盖非换行和换行表格路径。

源码证据：

- `terminal-ui/src/components/markdown.tsx`
  - `stripInlineMarkup` 删除行内 Markdown 标记。
  - `renderTable` 非换行路径通过 `MdInline` 渲染单元格内容。
  - `wrapCell` 换行路径保留原始 Markdown token，并用脱标记文本做宽度计算。

建议修复阶段：阶段 5.3。已在无需换行和窄宽度换行表格路径保留行内 Markdown 渲染。

最小修复方向：

- 已保留现有 CJK 宽度和表格对齐逻辑。
- 已替换单元格内容渲染路径，使非换行和换行表格都保留行内格式。
- 已补充表格单元格内 `code` / `bold` / `link` 不丢失的 TUI 测试。

## 不列为当前 bug 的历史计划项

以下历史待办已经被当前源码覆盖或需要后续更窄范围验证，本轮不按 bug 处理：

- 顶层 setup/config 命令：`CliModeParser`、`TerminalSetupCommands` 和 `LocalTerminalHelp` 已包含 setup/config 相关入口。
- 主动协作基础能力：`config.example.yml`、`AppConfig.ProactiveConfig`、`DashboardConfigService`、`DashboardStatusService`、`ProactiveDiagnosticsService` 以及多组 `Proactive*Test` 已覆盖主要配置、诊断、调度和命令路径。
- 主动协作 Dashboard 配置入口：后端 schema 已包含 `proactive` 分类和安全字段，不能简单按“旧计划未勾选”判定为缺 UI。

## 后续处理顺序建议

1. 阶段 2.3 / 5.2：先补处理状态表情回应的 Dashboard 配置入口，因为它是后端已有能力但 UI 不可见。
2. 阶段 5.3：再修 TUI Markdown 表格行内格式丢失，因为它属于展示层质量问题，不影响核心处理链。
