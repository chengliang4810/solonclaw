# Solon Claw

[English](README_EN.md) | 简体中文

Solon Claw 是一个基于 Java、Solon 与 Solon AI 的单实例 Agent 服务。项目目标是以 Java / Solon 生态对齐外部对标 Agent 的核心行为与能力，重点覆盖 Agent 主循环、工具调用、会话/记忆、技能、定时任务、国内消息渠道接入，以及 Dashboard-first 的配置与诊断体验。

> 当前项目仍处于快速迭代阶段，接口和配置项可能继续调整。欢迎试用、反馈问题和参与贡献。

## 特性

- **Agent 核心循环**：多轮会话、流式/非流式模型调用、工具调用、上下文压缩、重试、回滚与会话搜索。
- **模型协议**：支持 `openai`、`openai-responses`、`ollama`、`gemini`、`anthropic` 等通用接入面。
- **工具系统**：内置文件读写、搜索、补丁、Shell/Python/JavaScript 执行、Todo 任务规划、Memory、定时任务、Web search/fetch、消息发送等工具。
- **国内消息渠道**：聚焦飞书、钉钉、企业微信、微信、QQBot、腾讯元宝；优先 websocket / stream，微信保留 iLink long-poll。
- **Dashboard-first**：提供状态查看、会话、配置、渠道诊断、运行配置、日志、技能等管理入口。
- **持久化**：使用 SQLite 保存会话、策略、定时任务、渠道状态等运行数据。
- **技能与记忆**：支持本地 Skills、Skills Hub 导入、长期记忆、用户画像和上下文文件协作。
- **主动提醒**：定时回顾主对话与记忆，由工作区 MD 规则控制联系频率和提醒内容。
- **部署方式**：支持 `java -jar` 与 Docker / Docker Compose 单实例部署。

## 技术栈

- Java 源码兼容级别：1.8
- 运行与构建：Maven、Node.js/npm（用于 Dashboard 前端构建）
- Web 框架：Solon
- AI 编排：Solon AI、Solon AI Agent、Solon AI Skills
- JSON：Snack4
- 工具库：Hutool
- 数据库：SQLite
- 前端：Vue / Vite
- 容器：Docker、Docker Compose

## 快速开始

### 一键安装

```bash
# Linux / macOS
curl -fsSL https://raw.githubusercontent.com/chengliang4810/solon-claw/main/scripts/install.sh | bash

# 国内用户（自动使用 GitHub 代理）
curl -fsSL https://ghfast.top/https://raw.githubusercontent.com/chengliang4810/solon-claw/main/scripts/install.sh | bash

# Windows PowerShell
irm https://raw.githubusercontent.com/chengliang4810/solon-claw/main/scripts/install.ps1 | iex
```

脚本支持两种部署方式：
- **原生安装**：自动安装 Java、Node.js，下载 jar，注册系统服务（systemd / launchd / NSSM），安装 TUI（`npm install -g solonclaw`）
- **Docker 部署**：自动拉取 `ghcr.io/chengliang4810/solonclaw` 镜像，创建 `docker-compose.yml` 并启动

安装完成后：
- 后端服务自动注册并运行在 `http://127.0.0.1:8080`
- TUI 通过 `solonclaw` 命令启动，连接后端进行交互
- 编辑 `~/.solonclaw/workspace/config.yml` 配置模型 API Key

### 环境要求

- JDK 8+（推荐 JDK 17）
- Maven 3.9+
- Node.js 20+ 与 npm
- 可访问目标大模型服务的网络环境

### 克隆与构建

```bash
git clone https://github.com/chengliang4810/solonclaw.git
cd solonclaw
mvn -DskipTests package
```

Maven 默认会在 `generate-resources` 阶段执行 `web` 前端的 `npm install` 与 `npm run build`。如果你只想构建后端，可使用：

```bash
mvn -DskipTests -Dskip.web.build=true package
```

### 运行

```bash
java -jar target/solonclaw-0.0.1.jar
```

服务默认监听：

```text
http://127.0.0.1:8080
```

首次打开 Dashboard 时输入一个新的访问令牌即可完成本机初始化；页面会通过本机限定的首次设置接口写入 `workspace/config.yml`。如果要提前固定访问令牌，也可以在启动时传入：

```bash
java -Dsolonclaw.dashboard.accessToken=your-token -jar target/solonclaw-0.0.1.jar
```

运行后会在当前目录创建 `workspace/`，用于保存 Agent 工作区、配置、SQLite 数据库、缓存、日志、技能和上下文文件。运行态子目录由程序内置派生：`context/`、`skills/`、`cache/`、`logs/` 和 `data/state.db`。

### 终端一次性命令与 Profile

```bash
solonclaw --cli -p /help
solonclaw --tui -p /help
solonclaw --profile work --cli -p /help
```

`--cli` 或 `--tui` 之后的 `-p` 表示一次性提示词，等同于 `--ask`；选择 Profile 请使用无歧义的 `--profile <name>`。短选项 `-p <name>` 仅在终端模式参数之前表示 Profile 选择。

### Docker Compose

```bash
docker compose up -d
```

默认 Compose 会将本地 `./workspace` 挂载到容器内 `/app/workspace`，方便持久化工作区、运行数据和可在线更新的 `solonclaw.jar`。`/app/docker-entrypoint.sh` 会在首次启动时复制镜像内置 JAR，之后从工作区 JAR 启动。镜像内已包含 `openssh-client`，容器内可以使用 `ssh`、`scp` 和 `sftp` 等基础远程连接命令。

## 配置

默认配置位于：

```text
src/main/resources/app.yml
```

模型提供方的标准配置使用 `providers` 与 `model` 结构，建议通过 Dashboard 或 `workspace/config.yml` 维护。

```text
workspace/config.yml
```

`workspace/config.yml` 不配置工作区目录本身；工作区目录由启动级 `solonclaw.workspace` 决定，默认使用当前目录下的 `workspace/`。

完整示例可参考仓库根目录的 `config.example.yml`。服务启动时也会把该示例同步到 `workspace/config.example.yml`，它只是只读参考模板；实际生效配置仍是 `workspace/config.yml`。

最小 `workspace/config.yml` 示例：

```yaml
providers:
  default:
    name: DefaultProvider
    baseUrl: https://api.openai.com
    apiKey: ""
    defaultModel: gpt-5.4
    dialect: openai
model:
  providerKey: default
  default: "gpt-5.4"
fallbackProviders: []
security:
  allowPrivateUrls: false
  fileGuardrailMode: strict
  urlGuardrailMode: strict
  guardrailMode: approval
  guardrailCronMode: strict
  guardrailCronScope: job
  hardlineAllowlist: []
approvals:
  subagentAutoApprove: false
  timeoutSeconds: 60
  mcpReloadConfirm: true
solonclaw:
  dashboard:
    accessToken: ""
```

常用运行配置：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8080` | HTTP 服务端口 |
| `solonclaw.workspace` | `./workspace` | Agent 工作区根目录；相对路径按运行 Jar 所在目录解析 |
| `providers.<key>.baseUrl` | - | 模型服务基础地址 |
| `providers.<key>.apiKey` | - | 模型服务 API Key |
| `providers.<key>.defaultModel` | - | 该提供方默认模型 |
| `providers.<key>.dialect` | `openai` | 协议方言 |
| `model.providerKey` | `default` | 当前默认提供方 |
| `model.default` | 空 | 全局默认模型覆盖；为空时使用 provider 的 `defaultModel` |
| `solonclaw.llm.stream` | `true` | 是否启用流式输出 |
| `solonclaw.llm.reasoningEffort` | `medium` | 默认推理强度 |
| `solonclaw.scheduler.enabled` | `true` | 是否启用定时任务调度 |
| `solonclaw.proactive.enabled` | `true` | 是否启用主动提醒 |
| `solonclaw.proactive.intervalHours` | `4` | 主动提醒检查间隔，单位小时 |
| `solonclaw.proactive.deliveryTarget` | `main` | 投递到最近的国内渠道主对话 |
| `solonclaw.proactive.topicCooldownHours` | `8` | 相同话题再次提醒前的最短间隔 |
| `solonclaw.proactive.quietHoursEnabled` | `true` | 是否启用免打扰时段 |
| `solonclaw.proactive.quietStart` / `quietEnd` | `22:00` / `08:00` | 本地免打扰起止时间 |
| `solonclaw.browser.rewriteLoopbackUrls` | `false` | 容器内浏览器访问宿主机 loopback 服务时是否改写 URL |
| `security.tirithEnabled` | `true` | 是否启用 Tirith 命令内容扫描 |
| `security.tirithFailOpen` | `true` | Tirith 不可用或超时时是否放行；设为 `false` 会 fail-closed |
| `security.allowPrivateUrls` | `false` | 是否允许 URL 工具访问内网/私有地址；云元数据地址仍阻断 |
| `security.fileGuardrailMode` | `strict` | 文件路径预检：`strict`、`bypass` |
| `security.urlGuardrailMode` | `strict` | URL 预检：`strict`、`bypass` |
| `security.guardrailMode` | `approval` | Agent 工具安全策略：`bypass`、`approval`、`smart` |
| `security.guardrailCronMode` | `strict` | 定时任务安全策略：`strict`、`approval`、`bypass`、`approve` |
| `security.guardrailCronScope` | `job` | 定时任务审批记忆范围：`job`、`session`、`global` |
| `security.hardlineAllowlist` | 空 | 显式允许跳过的 hardline 类别；默认不放行 |
| `approvals.subagentAutoApprove` | `false` | 子 Agent 是否自动批准一次可审批危险命令 |
| `approvals.timeoutSeconds` | `60` | 所有审批（包括消息渠道）的统一超时秒数 |
| `approvals.mcpReloadConfirm` | `true` | `/reload-mcp` 是否需要确认 |
| `solonclaw.terminal.credentialFiles` | 空 | 可挂载到隔离执行环境的 workspace 相对凭据文件列表 |
| `solonclaw.terminal.envPassthrough` | 空 | 允许传给本地子进程的第三方环境变量名 |
| `solonclaw.terminal.sudoPassword` | 空 | 可选 sudo 密码，用于 `sudo -S` 改写；也可通过 `SOLONCLAW_SUDO_PASSWORD` 提供 |
| `solonclaw.trace.retentionDays` | `14` | 运行轨迹保留天数 |
| `solonclaw.trace.maxAttempts` | `2` | 每个 run 最大外层 attempt 数 |
| `solonclaw.task.busyPolicy` | `interrupt` | 同一会话运行中收到新消息时的处理策略 |
| `solonclaw.task.subagentMaxConcurrency` | `3` | 子 Agent 最大并发数 |
| `solonclaw.task.subagentMaxDepth` | `1` | 子 Agent 最大 spawn 深度 |
| `solonclaw.task.toolOutputInlineLimit` | `50000` | 单个工具输出超过该字节数时写入缓存，仅回传预览 |
| `solonclaw.task.mediaCacheTtlHours` | `168` | 渠道媒体缓存 TTL，单位小时 |
| `solonclaw.skills.externalDirs` | 空 | 额外只读技能目录列表 |
| `solonclaw.skills.templateVars` | `true` | 是否启用 SKILL.md 模板变量替换 |
| `solonclaw.gateway.filterSilenceNarration` | `true` | 是否过滤短静默旁白，避免渠道收到无意义状态文本 |
| `solonclaw.mcp.enabled` | `false` | 是否启用 MCP 工具适配 |
| `solonclaw.web.searchBackend` | `solon-ai` | Web 搜索后端；当前使用 Solon AI 内置实现 |
| `solonclaw.pricing.prices` | 空 | 模型价格配置；为空时只统计 token，不计算价格 |

## 消息渠道

当前保留并优先建设的渠道：

| 渠道 | 配置前缀 | 入站方式 | 状态 |
| --- | --- | --- | --- |
| 飞书 | `solonclaw.channels.feishu.*` | websocket / 平台能力 | 建设中 |
| 钉钉 | `solonclaw.channels.dingtalk.*` | stream mode | 建设中 |
| 企业微信 | `solonclaw.channels.wecom.*` | websocket / 平台能力 | 建设中 |
| 微信 | `solonclaw.channels.weixin.*` | iLink long-poll | 建设中 |
| QQBot | `solonclaw.channels.qqbot.*` | websocket / REST | 建设中 |
| 腾讯元宝 | `solonclaw.channels.yuanbao.*` | websocket / REST | 建设中 |

Dashboard 提供渠道状态与 doctor 入口，建议优先通过 Dashboard 完成接入、诊断和排错。默认渠道示例仅开启微信：

```yaml
solonclaw:
  channels:
    weixin:
      enabled: true
```

## 主动提醒

主动提醒按配置周期读取最近的国内渠道主对话和三层记忆。`PROACTIVITY_ANALYSIS.md` 分析用户互动情况并调整活跃度，`PROACTIVE.md` 负责防重复、选择话题和生成最终可见消息。系统不再维护独立候选、决策或投递重试表。

常用控制命令：

- `/proactive status`：查看是否启用、检查间隔和同话题间隔。
- `/proactive pause` / `/proactive resume`：暂停或恢复主动提醒。

如果没有收到提醒，检查是否存在国内渠道主对话、当前是否处于免打扰时段，以及两个主动提醒 MD 文件是否可读。

## Slash Commands

常用对话内命令包括：

- `/new`：开启新会话
- `/retry`：重试上一轮
- `/undo`：撤销上一轮
- `/branch`：基于当前会话分支
- `/resume`：恢复会话
- `/status`：查看运行状态
- `/usage`：查看 token 使用量
- `/model`：查看或切换模型
- `/tools`：查看工具状态
- `/skills`：管理技能
- `/cron`：管理定时任务
- `/proactive`：查看、暂停或恢复主动提醒
- `/pairing`：渠道用户绑定与审批
- `/approve` / `/deny`：危险命令审批

## 目录结构

```text
src/main/java/com/jimuqu/solon/claw/
├── agent/          # Agent profile
├── bootstrap/      # Solon 启动、Bean 装配、HTTP 控制器
├── config/         # 配置文件加载、运行时覆盖、路径规范化
├── context/        # AGENTS / MEMORY / USER / Skills 上下文
├── core/           # 领域模型、仓储接口、服务接口
├── engine/         # Agent 主循环、上下文压缩、委托
├── gateway/        # 国内消息渠道、鉴权、投递和运行刷新
├── llm/            # 模型协议适配与 Solon AI 接入
├── proactive/      # 基于主对话、记忆和工作区 MD 的主动提醒
├── scheduler/      # Cron 与 heartbeat 调度
├── skillhub/       # Skills Hub、导入、校验与来源适配
├── storage/        # SQLite 仓储实现
├── support/        # 通用运行期支持类
├── tool/           # 内置工具注册与实现
└── web/            # Dashboard 后端服务与控制器
```

## 发布门禁

常规提交前请只扫描当前工作树和当前分支相对默认分支新增的提交：

```bash
python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
```

全 Git refs 扫描只适合人工历史审计，不作为当前源码或发布范围是否合格的常规门禁。

## 测试

运行后端与前端绑定构建测试：

```bash
mvn test
```

只做后端编译：

```bash
mvn -DskipTests -Dskip.web.build=true compile
```

运行指定测试：

```bash
mvn "-Dtest=DashboardControllerHttpTest" test
```

> Windows PowerShell 中 `-Dtest=...` 建议加引号，避免逗号被 PowerShell 解析。

## 贡献

欢迎提交 issue 和 pull request。建议在贡献前先阅读现有 issue、运行相关测试，并在 PR 中说明变更动机、主要实现和验证范围。

## 许可证

本项目使用 [MIT License](LICENSE)。
