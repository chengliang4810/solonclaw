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

5. 文件右键菜单打开模式统一
   - 位置：
     - `web/src/components/solonclaw/files/FileContextMenu.vue`
     - `web/src/shared/fileDisplay.ts`
     - `web/tests/fileDisplay.test.ts`
   - 改造前：
     - 文件列表双击已使用统一打开模式，但右键菜单仍单独判断文本、图片、Markdown。
     - Markdown 文件右键会同时出现编辑和预览，和双击优先预览不一致。
   - 改造后：
     - 新增 `fileContextPrimaryAction`，复用 `fileOpenMode` 生成右键菜单主操作。
     - 目录显示打开，图片和 Markdown 显示预览，普通文本显示编辑，二进制文件只保留下载等通用操作。
   - 提交：`16075d8d3`

6. 文件列表行内编辑按钮显示统一
   - 位置：
     - `web/src/components/solonclaw/files/FileList.vue`
     - `web/src/shared/fileDisplay.ts`
     - `web/tests/fileDisplay.test.ts`
   - 改造前：
     - 文件列表行内编辑按钮仍使用文本扩展名判断，Markdown 文件会显示编辑按钮。
     - 双击和右键菜单已优先预览 Markdown，行内按钮与主打开行为不一致。
   - 改造后：
     - 新增 `shouldShowInlineEditAction`，复用 `fileOpenMode` 判断行内编辑按钮是否显示。
     - Markdown、图片和二进制文件不显示编辑按钮，普通文本保留编辑按钮。
   - 提交：`b7429a50a`

7. TUI Markdown 换行表格行内格式保留
   - 预存问题：`docs/full-repair-bug-report-2026-06-26.md` 中 `BUG-002` 的窄宽度剩余路径。
   - 位置：
     - `terminal-ui/src/components/markdown.tsx`
     - `terminal-ui/src/__tests__/markdown.test.ts`
   - 改造前：
     - 表格换行路径先把单元格内容转成纯文本字符串，再按视觉行渲染。
     - 窄宽度下的表格单元格会丢失加粗、链接、代码等行内 Markdown 样式。
   - 改造后：
     - 表格换行仍使用脱标记文本计算宽度，保持 CJK 对齐和换行行为。
     - 每个视觉行保留原始 Markdown token 并交给 `MdInline` 渲染，换行表格也能保留行内样式。
   - 提交：`d6f197c42`

8. 用量统计页自动刷新
   - 位置：`web/src/views/solonclaw/UsageView.vue`
   - 改造前：
     - 用量统计、运行洞察和技能使用明细只在页面首次进入或手动点击刷新时加载。
     - 运行期间产生新的 usage event 后，页面会持续显示旧数据，不符合阶段 5.1 的自动刷新要求。
   - 改造后：
     - 页面挂载后每 15 秒在可见状态下刷新一次用量数据。
     - 页面从后台切回可见时立即刷新。
     - 页面卸载时清理定时器和可见性监听，避免后台残留请求。
   - 提交：`1a4fe6b6a`

9. 人格日记加载失败反馈
   - 位置：`web/src/views/solonclaw/PersonaDiaryView.vue`
   - 改造前：
     - 日记列表加载失败时只结束 loading，页面显示空状态但没有错误提示。
     - 切换日记详情失败时，可能出现新标题配旧正文，容易误导用户。
   - 改造后：
     - 日记列表加载失败时清空列表、选中路径和正文，并展示错误提示。
     - 切换日记失败时清空正文、回退到原选中路径，并展示错误提示。
   - 提交：`ce17ec7ba`

10. 日志页失败后清理旧数据
    - 位置：`web/src/views/solonclaw/LogsView.vue`
    - 改造前：
      - 日志刷新失败时会继续展示上一轮日志数据和搜索结果，用户难以判断当前筛选是否真的生效。
      - 日志文件列表初始化失败没有捕获，会中断页面初始加载流程。
    - 改造后：
      - 日志刷新失败时清空当前日志条目和已应用搜索条件，并展示错误提示。
      - 日志文件列表初始化失败时清空列表并提示错误，后续仍尝试加载当前默认日志。
    - 提交：`390c958fe`

11. 人格文件切换失败清理旧正文
    - 位置：`web/src/views/solonclaw/PersonaFileView.vue`
    - 改造前：
      - 从一个人格文件切换到另一个人格文件时，如果新文件加载失败，页面标题会变成新文件但正文仍可能保留旧文件内容。
    - 改造后：
      - 切换到不同文件 key 时先清空旧正文和编辑内容。
      - 异步返回时确认仍处于同一文件 key，避免慢请求覆盖当前页面。
      - 新文件加载失败时保持空正文并展示错误提示，不再混用旧内容。

## 验证

- `npm test --prefix terminal-ui -- markdown.test.ts`：通过，44 个测试通过，覆盖换行表格行内格式。
- `npm test --prefix terminal-ui -- banner.test.ts`：通过，2 个测试通过。
- `npm run --prefix terminal-ui type-check`：通过。
- `mvn -Dskip.web.build=true -Dtest=GatewayProcessingReactionLifecycleTest,ProactiveDashboardDiagnosticTest,RuntimeConfigResolverTest test`：通过，16 个测试通过。
- `npm run --prefix web test:file-display`：通过。
- `npm run build --prefix web`：通过。
- `npm run build --prefix web`：2026-06-27 用量页自动刷新改造后通过。
- `npm run build --prefix web`：2026-06-27 人格日记加载失败反馈改造后通过。
- `npm run build --prefix web`：2026-06-27 日志页失败状态清理改造后通过。
- `npm run build --prefix web`：2026-06-27 人格文件切换失败清理旧正文改造后通过。
- `git diff --check -- terminal-ui/src/components/markdown.tsx terminal-ui/src/__tests__/markdown.test.ts`：通过。
- `git diff --check -- web/src/shared/fileDisplay.ts web/tests/fileDisplay.test.ts web/src/components/solonclaw/files/FileContextMenu.vue`：通过。
- `git diff --check -- web/src/views/solonclaw/UsageView.vue`：通过。
- `git diff --check -- web/src/views/solonclaw/PersonaDiaryView.vue`：通过。
- `git diff --check -- web/src/views/solonclaw/LogsView.vue`：通过。
- `git diff --check -- web/src/views/solonclaw/PersonaFileView.vue`：通过。
- `node -e "const fs=require('fs'); const p=JSON.parse(fs.readFileSync('terminal-ui/package.json','utf8')); const l=JSON.parse(fs.readFileSync('terminal-ui/package-lock.json','utf8')); console.log(JSON.stringify({package:p.version, lock:l.version, root:l.packages[''].version}, null, 2)); if (p.version!==l.version || p.version!==l.packages[''].version) process.exit(1)"`：通过，三处版本号均为 `0.0.7`。
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`：通过。
