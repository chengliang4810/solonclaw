# 项目全面修复预存问题清单

日期：2026-06-26

本文档对应阶段 0.2：检查项目中已有的预存问题清单，并把可验证的后续修复项纳入本次全面修复任务。

## 记忆读取与恢复范围

- 已读取当前会话提供的记忆摘要。
- 已读取记忆索引：`MEMORY.md`。
- 已读取本仓库直接相关的 rollout 摘要，覆盖命名迁移、TUI、配置写回、审批策略、主动协作、历史安全整治、大型 diff review、发布与推送失败经验。
- 已完成记忆库文件级盘点：`memory_summary.md`、`MEMORY.md`、`raw_memories.md`、`extensions/ad_hoc/*`、`rollout_summaries/*.md`，合计约 13,993 行。与本仓库无关的其他项目摘要不纳入本仓库修复范围。

## 当前工作树状态

- 分支：`work/full-repair-optimization`
- 已提交：
  - `abfddf0c fix: 调整 TUI 品牌展示 / Adjust TUI brand display`
  - `c6f86d20 docs: 恢复全面修复上下文 / Restore full repair context`
- 已归属并提交的既有改动：
  - `terminal-ui/package.json`
  - `terminal-ui/package-lock.json`
  - 两者仅表现为版本号 `0.0.6` 到 `0.0.7` 的变更，已作为 TUI 版本号更新单独提交。

## 已发现的预存问题与阶段归属

### P0-01：生产代码文件超过 4000 行

证据命令：

```bash
find src/main/java src/test/java web/src terminal-ui/src -type f \( -name '*.java' -o -name '*.ts' -o -name '*.tsx' -o -name '*.vue' \) -print0 | xargs -0 wc -l | sort -nr | sed -n '1,40p'
```

当前超过 4000 行的生产代码文件：

| 文件 | 行数 | 归属阶段 |
| --- | ---: | --- |
| `src/main/java/com/jimuqu/solon/claw/tool/runtime/SecurityPolicyService.java` | 6962 | 阶段 1.2 |
| `src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java` | 6949 | 阶段 1.2 |
| `src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandApprovalService.java` | 5512 | 阶段 1.2 |
| `src/main/java/com/jimuqu/solon/claw/web/DashboardSecurityProbeRunner.java` | 4999 | 阶段 1.2 |
| `src/main/java/com/jimuqu/solon/claw/config/AppConfig.java` | 4903 | 阶段 1.2 |

说明：测试文件中也存在多个超过 4000 行的文件，但阶段 1.2 明确要求“代码文件”拆分；优先处理生产代码，测试拆分可在对应功能重构时处理。

### P0-02：历史终端 setup 计划仍有未完成复选项

证据文件：`docs/superpowers/plans/2026-06-05-terminal-setup-commands.md`

未完成项包括：

- 顶层命令解析：`model`、`setup model`、`setup gateway`、`gateway setup`、`config path` 的解析测试与实现。
- 共享终端 setup service、CLI/TUI 接入、命令 registry/help 对齐。
- 真实命令验证：构建 jar 后运行 `model`、`setup`、`setup model`、`setup gateway`、`config path`、`config check`、`--cli -p /setup model`、`--tui -p /setup gateway`。

归属阶段：

- 阶段 1.1：先确认这些未完成项在当前源码中是否仍是 bug。
- 阶段 2.2 / 2.3：若当前 CLI/TUI 或 Dashboard 入口缺失，应补齐对应入口和交互。

### P0-03：历史主动协作计划仍有大量未完成复选项

证据文件：`docs/superpowers/plans/2026-06-16-proactive-collaboration.md`

当前计划中仍有大量未勾选任务，覆盖：

- proactive 配置模型与 runtime override。
- proactive persistence schema 和 repository。
- observation、candidate、decision、dispatch、diagnostics、dashboard、README 和命名 guard。

归属阶段：

- 阶段 1.1：对当前主动协作功能做原子级 bug 查找，确认哪些计划项已实现、哪些仍缺失。
- 阶段 2：核对后端 proactive 能力与前端 Dashboard 是否一致。
- 阶段 4：重点检查 LLM 决策是否真正结合数据、次数、真实聊天内容文本，而不是硬编码或浅层统计。
- 阶段 5：检查主动协作诊断、状态和历史记录在 UI 中是否持久显示并自动刷新。

### P0-04：Markdown 行内格式 TODO

状态：已处理，提交 `ad80c1fb7`、`d6f197c42`

证据文件：`terminal-ui/src/components/markdown.tsx`

发现：

- TUI Markdown 表格行内格式在非换行路径和窄宽度换行路径均已保留。

归属阶段：

- 阶段 1.1：判断是否存在真实渲染 bug。
- 阶段 5.3：若影响 TUI 文本视觉呈现，补齐格式保留与换行表现。

### P0-05：处理状态表情回应计划中 Dashboard 独立开关待确认

状态：已处理，提交 `6a86d4488`

证据文件：`docs/superpowers/plans/2026-06-04-processing-status-reactions.md`

发现：

- 文档记录“Dashboard 是否需要独立开关”仍属待确认范围。

归属阶段：

- 阶段 2.3：确认后端是否已有处理状态表情回应能力但 Dashboard 缺少配置入口。
- 阶段 5.2：如缺少 UI，应补充符合功能需求的开关和状态说明。

### P0-06：版本号改动未归属

状态：已处理，提交 `053a8a1c5`

证据：

```bash
git diff -- terminal-ui/package.json terminal-ui/package-lock.json
```

发现：

- `terminal-ui` 版本号从 `0.0.6` 改为 `0.0.7`。
- `terminal-ui/package.json`、`terminal-ui/package-lock.json` 和 lock 根 package 版本字段一致。

归属阶段：

- 阶段 7.1：已作为发布版本元数据单独提交。
- 未混入 bug、结构拆分或 UI 修复提交。

### P0-07：未使用变量门禁不一致

状态：已复核，提交 `f657b6594`

证据文件：

- `terminal-ui/package.json`
- `terminal-ui/eslint.config.mjs`
- `web/package.json`
- `web/tsconfig.app.json`
- `web/tsconfig.node.json`

发现：

- `terminal-ui` 有 `npm run lint`，并启用 `eslint-plugin-unused-imports`，但普通未使用变量规则被关闭，主要拦截未使用 import。
- `web` 通过 `vue-tsc -b && vite build` 验证，`noUnusedLocals` 与 `noUnusedParameters` 已启用，可作为前端未使用变量门禁。
- `npm run --prefix terminal-ui type-check` 通过，未发现 TypeScript 未使用变量错误。
- `npm run build --prefix web` 通过，未发现前端未使用变量错误。
- `npm run --prefix terminal-ui lint` 原有 2 个导入排序 error 已修复，当前剩余为 padding、hooks 与 react-compiler warning，后续按独立问题处理。

归属阶段：

- 阶段 1.3：现有未使用变量门禁已复核通过。
- 暂不扩大 `terminal-ui` ESLint 未使用变量规则，避免把普通变量策略调整与当前 lint error 修复混在同一项。

### P0-08：缺少明确的代码重复检测工具

状态：已补充，提交 `d3a0b00ae`

证据命令：

```bash
rg '\b(jscpd|pmd|cpd|duplication|重复代码|重复检测)\b' .
find . -iname '*jscpd*' -o -iname '*pmd*' -o -iname '*cpd*'
python3 scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages
```

发现：

- 已新增 `scripts/check-code-duplication.py`，使用 Python 标准库检测归一化后的精确重复代码块。
- 已新增 `scripts/check-code-duplication.selftest.py`，覆盖重复阻断、report-only 和唯一代码放行。
- 当前保守阈值 `--min-lines 40` 扫描出 5 组重复代码块，详见 `docs/full-repair-duplication-review-2026-06-27.md`。

归属阶段：

- 阶段 1.4：先用本地脚本做明确重复检测入口；不默认引入 jscpd、PMD CPD 等新依赖。

## 后续执行顺序

1. 阶段 1.1：基于上述清单和当前源码，对单个功能点做原子级 bug 查找，并把完整 bug 报告保存至 `docs/`。
2. 阶段 1.2：拆分当前工作树中超过 4000 行的生产代码文件，每个拆分都需要对应编译/测试验证。
3. 阶段 1.3：先定位现有 lint、compiler 或 IDE 可复现的“未使用变量”警告来源，再逐项修复。
4. 阶段 1.4：做重复检测与复用优化，优先从已存在的重复 helper、超大类内部重复逻辑和前后端重复 API 包装入手。
