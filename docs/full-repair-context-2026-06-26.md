# 项目全面修复与优化上下文

日期：2026-06-26

## 当前执行状态

- 工作树：`/Users/chengliang/.codex/worktrees/9018/jimuqu-agent`
- 分支：`work/full-repair-optimization`
- 当前阶段：阶段 0 准备工作
- 已独立提交：`fix: 调整 TUI 品牌展示 / Adjust TUI brand display`
- 当前仍存在的非本阶段改动：`terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 的版本号从 `0.0.6` 到 `0.0.7`，本阶段不纳入提交。

## 项目约束摘要

- 项目目标是基于 Java、Solon、Solon AI、Hutool、Snack4 复刻外部对标 Agent 的核心行为与能力，不扩展成泛用聊天应用或无关 AI Demo。
- 代码、配置、测试、文档、提交信息和用户可见输出必须使用当前 `solonclaw` 命名；若需要表达来源，只能使用“外部对标仓库”“对标实现”“旧项目关键词”等中性说法。
- 国内渠道范围仅保留 `feishu`、`dingtalk`、`wecom`、`weixin`、`qqbot`、`yuanbao`；明确不做 `sms`、`webhook` 和海外渠道。
- 模型协议范围为 `openai`、`openai-responses`、`ollama`、`gemini`、`anthropic`；其他 provider 默认不做。
- 本版主线包含多模态输入、图像理解/生成、TTS/独立语音转写、浏览器自动化内置实现、价格分析/价格计算、插件系统。
- 常规提交前命名检查使用：`python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`。
- Java 侧验证优先使用 `mvn -Dskip.web.build=true ...`，避免前端构建链干扰后端快速验证。

## 已恢复的历史上下文

### 命名与发布上下文

- README/README_EN 中可保留面向用户的 `Solon Claw` 品牌表达，技术标识、包名、镜像、JAR、UI 路由和配置统一使用 `solonclaw`。
- 历史兼容键、历史环境变量、历史命令名和历史字段不应新增兼容读取或隐藏回退。
- `terminal-ui/node_modules` 可能因本地包重命名出现旧软链；TUI 验证失败时先运行 `npm --prefix terminal-ui install`。
- GitHub 推送在本机可能受代理和 443 连接影响；重复推送失败前应先检查网络/代理状态。

### 安全与质量上下文

- 历史安全整治已覆盖 dashboard 弱口令、异常吞没、资源泄漏、fail-open、Docker compose 基础安全项、JaCoCo 和部分大类拆分。
- 历史最终 Java 大文件快照仍包含较大文件：`SecurityPolicyService.java`、`DefaultCommandService.java`、`DangerousCommandApprovalService.java`、`DashboardSecurityProbeRunner.java`，阶段 1.2 需按当前工作树重新统计并处理超过 4000 行的文件。
- 高风险验证门禁包括：
  - `git diff --check`
  - `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`
  - `docker compose config`
  - 相关 Maven 精准测试与必要的 broad gate。

### 配置、审批与 secret 上下文

- 配置写回类问题必须查真实写路径，不能只看脱敏显示层。
- secret 改写应走 `config_set_secret` / `setSecretValue()` 分流；whole-file write/patch 不能把脱敏占位符写回真实配置。
- `SecretValueGuard` 已将 `*`、`***`、`your-api-key`、`placeholder`、`example`、`dummy`、`null`、`none` 和含 `...` 的值视为占位 secret。
- 审批键当前应以 `policy:workspace_outside_write` / `policy:network_external_operation` 记忆，修改审批逻辑时需验证 `/approve session` 与 `/approve always` 是否持续生效。

### TUI 与工具上下文

- TUI 工作横跨 `terminal-ui/`、`src/main/java/com/jimuqu/solon/claw/tui/`、`support/TuiRuntimeProtocolService.java`、`bin/solonclaw` 和 `TerminalSetupCommands`。
- `/ws/tui` 是独立 trust boundary，安全判断不能只看 `/api/**` 过滤器。
- 工具结果 envelope 使用顶层 `status`，cron 和 process 子结构分别使用 `cron_status`、`process_status`。
- `file_read` 的 redaction 不是核心风险点；风险在 whole-file write/patch 把 placeholder secret 写回配置。

## 阶段 0 后续输入

- 阶段 0.2 需要把仓库中预存问题清单、计划文档、TODO/FIXME 和历史报告中仍未满足当前目标的事项汇总到 docs，并标明后续阶段归属。
- 阶段 1.1 应先输出原子级功能 bug 报告到 `docs/`，再进入结构拆分或修复。
- 阶段 1.2 需要以当前代码为准重新统计超过 4000 行的代码文件，不沿用旧快照。
