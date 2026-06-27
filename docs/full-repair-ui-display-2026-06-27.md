# 阶段 5 UI/UX 与数据显示优化记录

日期：2026-06-27

## 对应能力点

- 对应本地 TUI Markdown 渲染展示质量。
- 对应本地 TUI 会话首屏品牌图案展示质量。
- 本文只记录当前已经完成并提交的阶段 5.3 展示修复项，不把阶段 5 标记为完成。

## 已处理项

1. TUI Markdown 表格行内格式保留
   - 预存问题：`docs/full-repair-bug-report-2026-06-26.md` 中的 `BUG-002`。
   - 位置：`terminal-ui/src/components/markdown.tsx`
   - 改造前：
     - 表格非换行路径直接输出 `stripInlineMarkup(cell)` 的纯文本。
     - 单元格里的加粗、链接等行内 Markdown 样式会丢失。
   - 改造后：
     - 表格宽度计算仍使用 `stripInlineMarkup`，保留现有 CJK 宽度和列对齐逻辑。
     - 非换行表格单元格内容改为复用 `MdInline` 渲染，padding 和列间距仍按可见宽度计算。
   - 提交：`ad80c1fb7`

2. 处理状态表情回应 Dashboard 配置入口
   - 预存问题：`docs/full-repair-bug-report-2026-06-26.md` 中的 `BUG-001`。
   - 位置：
     - `src/main/java/com/jimuqu/solon/claw/config/AppConfig.java`
     - `src/main/java/com/jimuqu/solon/claw/gateway/service/DefaultGatewayService.java`
     - `src/main/java/com/jimuqu/solon/claw/web/DashboardConfigService.java`
   - 改造前：
     - 网关会无条件触发渠道处理状态表情回应生命周期。
     - Dashboard 配置 schema 没有处理状态表情回应开关，用户无法按环境或权限关闭。
   - 改造后：
     - 新增 `gateway.processingReactionsEnabled` 配置，默认启用。
     - Dashboard schema 在 messaging 分类暴露该开关。
     - 网关在触发处理开始/完成表情回应前读取该开关，关闭时不调用渠道适配器生命周期。
   - 提交：`6a86d4488`

3. 文件列表 Markdown 双击预览
   - 位置：
     - `web/src/components/solonclaw/files/FileList.vue`
     - `web/src/shared/fileDisplay.ts`
   - 改造前：
     - Markdown 文件同时被识别为文本文件，双击时先进入编辑器，Markdown 预览路径实际不会触发。
   - 改造后：
     - 新增文件打开模式判断，目录进入导航，图片和 Markdown 进入预览，普通文本进入编辑器。
     - 文件 store 和列表组件复用同一组文件类型判断，避免展示路径和操作路径不一致。
   - 提交：`00f9bfbdd`

4. TUI 会话品牌图案替换
   - 位置：
     - `terminal-ui/src/banner.ts`
     - `terminal-ui/src/__tests__/banner.test.ts`
   - 改造前：
     - 顶部标题源码已保持 `SOLON CLAW` 的空格版，但 `Available Tools` 左侧默认会话 hero 仍接近旧螺旋状块图。
   - 改造后：
     - 保留顶部默认标题的无连字符空格版。
     - 默认会话 hero 改为 Solon favicon 的终端块状近似图案，不新增运行时图片或网络依赖。
     - 单测改为约束 favicon 轮廓关键行，并继续排除旧图案关键行。
   - 提交：`bae710bca`

## 验证

- `npm test --prefix terminal-ui -- markdown.test.ts`：通过，43 个测试通过。
- `npm test --prefix terminal-ui -- banner.test.ts`：通过，2 个测试通过。
- `npm run --prefix terminal-ui type-check`：通过。
- `mvn -Dskip.web.build=true -Dtest=GatewayProcessingReactionLifecycleTest,ProactiveDashboardDiagnosticTest,RuntimeConfigResolverTest test`：通过，16 个测试通过。
- `npm run --prefix web test:file-display`：通过。
- `npm run build --prefix web`：通过。
- `git diff --check -- terminal-ui/src/components/markdown.tsx terminal-ui/src/__tests__/markdown.test.ts`：通过。
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`：通过。

## 剩余风险

- 本次只修复无需换行的 Markdown 表格路径；窄宽度下的换行表格仍按纯文本换行，后续如要保留换行单元格内行内样式，需要单独处理 ANSI 换行。
- 当前工作树仍存在未纳入本阶段提交的 `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 版本号改动。
