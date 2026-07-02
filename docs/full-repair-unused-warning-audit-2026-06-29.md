# 全面修复阶段 1.3 未使用变量警告审计记录

生成时间：2026-06-29
最新复核时间：2026-07-02

## 对应外部对标能力点

- 代码质量治理：确保 Java、Dashboard Web 和本地 TUI 的当前门禁不会留下可复现的未使用变量或未使用导入问题。
- 持续工作流：把预存“未使用变量门禁不一致”问题收口到当前可执行检查，避免后续重复排查。

## 结论

当前工作树未复现未使用变量或未使用导入告警，因此阶段 1.3 没有需要修改的生产代码。

`terminal-ui` 的 lint 当前仍失败，但失败原因是 `terminal-ui/src/components/prompts.tsx:28` 的 `no-control-regex`，不是未使用变量或未使用导入。本阶段不把该问题混入 unused 清理提交，避免扩大子任务范围。

## 2026-07-02 复核结论

当前工作树仍未复现未使用变量或未使用导入告警；`terminal-ui` lint 现在为 0 error、58 warning，warning 类型为 padding、React hooks 或 react-compiler，不属于阶段 1.3 的 unused 清理范围。

| 命令 | 结果 | 和未使用变量的关系 |
| --- | --- | --- |
| `mvn "-Dskip.web.build=true" "-DskipTests" compile` | 通过 | Java 编译未报告未使用变量或导入问题。 |
| `npm --prefix terminal-ui run type-check` | 通过 | `noUnusedLocals`、`noUnusedParameters` 未发现问题。 |
| `npm --prefix web run build` | 通过 | `vue-tsc -b` 未发现 Web 未使用变量问题。 |
| `npm --prefix terminal-ui run lint` | 通过，0 error、58 warning | 没有 `unused-imports` error；剩余 warning 不属于未使用变量或导入。 |

## 预存问题复核

历史清单中的 P0-07 指向“未使用变量门禁不一致”。本轮复核结果如下：

- Web：`vue-tsc -b` 由 `npm run build --prefix web` 触发，当前通过；`web/tsconfig.app.json` 与 `web/tsconfig.node.json` 启用 `noUnusedLocals`、`noUnusedParameters`。
- terminal-ui：`npm run type-check --prefix terminal-ui` 当前通过；`terminal-ui/tsconfig.json` 启用 `noUnusedLocals`、`noUnusedParameters`。
- terminal-ui ESLint：启用 `unused-imports/no-unused-imports`，本轮 lint 输出没有 unused-imports error。
- Java：`mvn -Dskip.web.build=true -DskipTests compile` 当前通过，没有可执行门禁报告未使用变量或未使用导入。

## 已执行检查

```bash
mvn -Dskip.web.build=true -DskipTests compile
npm run type-check --prefix terminal-ui
npm run build --prefix web
npm run lint --prefix terminal-ui
```

结果：

| 命令 | 结果 | 和未使用变量的关系 |
| --- | --- | --- |
| `mvn -Dskip.web.build=true -DskipTests compile` | 通过 | 未报告 Java 未使用变量或导入问题。 |
| `npm run type-check --prefix terminal-ui` | 通过 | `noUnusedLocals`、`noUnusedParameters` 未发现问题。 |
| `npm run build --prefix web` | 通过 | `vue-tsc -b` 未发现 Web 未使用变量问题。 |
| `npm run lint --prefix terminal-ui` | 失败 | 失败点是 2 个 `no-control-regex` error 和 56 个 warning；没有 unused-imports error。 |

## 未纳入本阶段的问题

- `terminal-ui/src/components/prompts.tsx:28` 的 `no-control-regex` 属于 lint 规则问题，不是未使用变量或未使用导入。建议后续作为独立质量修复处理，并验证 `npm run lint --prefix terminal-ui`。
- `react-hooks/exhaustive-deps`、`react-compiler/react-compiler`、`padding-line-between-statements` 等 warning 需要单独评估语义或格式策略，不能作为 unused 清理顺手自动修复。

## 阶段处理决定

- 不修改生产代码，因为当前没有可复现的 unused 现症。
- 保留现有 TypeScript unused 门禁，不扩大 ESLint 普通未使用变量规则，避免把规则策略调整和现存 lint error 混在一起。
- 后续若新增或修改 Web/TUI 代码，仍必须运行对应 type-check 或 build，确保 noUnused 规则继续生效。
