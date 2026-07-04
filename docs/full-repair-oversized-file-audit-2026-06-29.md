# 全面修复阶段 1.2 超大文件拆分审计记录

生成时间：2026-06-29

复核时间：2026-07-04

## 对应外部对标能力点

- 代码质量治理：限制单个代码文件继续膨胀，避免命令、审批、安全、诊断和模型协议主链难以审查。
- 长期可维护性：后续新增功能优先在已有职责边界内提取模块，而不是继续向临界文件追加逻辑。

## 结论

当前受版本控制的代码文件中，没有超过 4000 行的文件。因此阶段 1.2 不需要执行拆分提交，也不能为了满足流程强行拆分未越线文件。

本轮仍记录接近阈值的文件，作为后续阶段修改时的串行处理名单。任何后续功能如果需要触碰这些文件，应优先拆出已有职责，避免新增逻辑后越过 4000 行。

2026-07-04 复核后，`DefaultCommandService.java` 已在命令服务构造器链精简后降至 2633 行，不再属于接近阈值文件。

## 检查口径

本轮使用两个口径交叉确认：

- `git ls-files`：只统计受版本控制的代码文件，排除 `node_modules`、`dist`、`target` 等生成目录。
- 源码目录扫描：统计 `src/main/java`、`src/test/java`、`web/src`、`terminal-ui/src`、`terminal-ui/packages` 下的 Java、TypeScript、Vue 和 JavaScript 文件，并显式跳过生成目录。

直接扫描 `web/` 或 `terminal-ui/` 会包含已安装依赖中的 `.d.ts` 文件，不属于本项目源码，不作为阶段 1.2 拆分对象。

## 超过 4000 行文件检查

命令：

```bash
git ls-files '*.java' '*.ts' '*.tsx' '*.vue' '*.js' '*.jsx' \
  | xargs wc -l \
  | awk '$2 != "total" && $1 > 4000 { print }'
```

结果：无输出。

命令：

```bash
find src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages \
  -path '*/node_modules' -prune -o \
  -path '*/dist' -prune -o \
  -type f \( -name '*.java' -o -name '*.ts' -o -name '*.tsx' -o -name '*.vue' -o -name '*.js' -o -name '*.jsx' \) \
  -print0 \
  | xargs -0 wc -l \
  | awk '$2 != "total" && $1 > 4000 { print }'
```

结果：无输出。

## 接近阈值的文件

| 行数 | 文件 | 风险判断 |
| ---: | --- | --- |
| 3765 | `src/main/java/com/jimuqu/solon/claw/tool/runtime/SecurityPolicyService.java` | 安全策略核心类；后续改动必须串行验证，避免把策略拆分和行为修复混在同一提交。 |
| 3713 | `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java` | Dashboard 诊断聚合服务；适合后续按诊断域拆出小服务。 |
| 3653 | `src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandApprovalService.java` | 审批主链；和 TUI 审批、危险命令策略、测试族耦合高。 |
| 3616 | `src/main/java/com/jimuqu/solon/claw/llm/SolonAiLlmGateway.java` | 模型协议主链；涉及流式输出、工具调用和 Solon AI 调用边界，拆分需要单独验证。 |
| 3465 | `src/test/java/com/jimuqu/solon/claw/DashboardControllerHttpTest.java` | Dashboard HTTP 行为回归测试；后续新增场景应按接口域拆分测试类。 |
| 3425 | `src/test/java/com/jimuqu/solon/claw/DefaultCronSchedulerTest.java` | 测试文件接近阈值；后续新增 cron 场景应按行为拆分测试类或抽测试夹具。 |

## 阶段处理决定

- 本阶段不拆分任何文件，因为没有超过 4000 行的受控源码文件。
- 对 3400 行以上文件建立风险记录，后续只要触碰对应功能，应优先按职责拆分。
- 若后续提交使任一文件超过 4000 行，应在该功能提交前先拆分或把拆分作为独立前置提交。

## 验证记录

已执行：

```bash
git ls-files '*.java' '*.ts' '*.tsx' '*.vue' '*.js' '*.jsx' | xargs wc -l | awk '$2 != "total" && $1 > 4000 { print }'
git ls-files '*.java' '*.ts' '*.tsx' '*.vue' '*.js' '*.jsx' | xargs wc -l | awk '$2 != "total" { print $1, $2 }' | sort -nr | head -25
find src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages -path '*/node_modules' -prune -o -path '*/dist' -prune -o -type f \( -name '*.java' -o -name '*.ts' -o -name '*.tsx' -o -name '*.vue' -o -name '*.js' -o -name '*.jsx' \) -print0 | xargs -0 wc -l | awk '$2 != "total" && $1 > 4000 { print }'
find src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages -path '*/node_modules' -prune -o -path '*/dist' -prune -o -type f \( -name '*.java' -o -name '*.ts' -o -name '*.tsx' -o -name '*.vue' -o -name '*.js' -o -name '*.jsx' \) -print0 | xargs -0 wc -l | awk '$2 != "total" { print $1, $2 }' | sort -nr | head -25
```

以上检查均未发现超过 4000 行的项目代码文件。
