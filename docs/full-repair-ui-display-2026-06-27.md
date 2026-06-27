# 阶段 5 UI/UX 与数据显示优化记录

日期：2026-06-27

## 对应能力点

- 对应本地 TUI Markdown 渲染展示质量。
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

## 验证

- `npm test --prefix terminal-ui -- markdown.test.ts`：通过，43 个测试通过。
- `git diff --check -- terminal-ui/src/components/markdown.tsx terminal-ui/src/__tests__/markdown.test.ts`：通过。
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`：通过。

## 剩余风险

- 本次只修复无需换行的 Markdown 表格路径；窄宽度下的换行表格仍按纯文本换行，后续如要保留换行单元格内行内样式，需要单独处理 ANSI 换行。
- 当前工作树仍存在未纳入本阶段提交的 `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 版本号改动。
