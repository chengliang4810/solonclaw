# solon-claw

[English](README_EN.md) | 简体中文

solon-claw 是一个基于 Java、Solon 与 Solon AI 的单实例 Agent 服务。项目目标是以 Java / Solon 生态复刻 参考 Agent 的核心行为与能力，重点覆盖 Agent 主循环、工具调用、会话/记忆、技能、定时任务、国内消息渠道接入，以及 Dashboard-first 的配置与诊断体验。

> 当前项目仍处于快速迭代阶段，接口和配置项可能继续调整。欢迎试用、反馈问题和参与贡献。

## 特性

- **Agent 核心循环**：多轮会话、流式/非流式模型调用、工具调用、上下文压缩、重试、回滚与会话搜索。
- **模型协议**：支持 `openai`、`openai-responses`、`ollama`、`gemini`、`anthropic` 等通用接入面。
- **工具系统**：内置文件读写、搜索、补丁、Shell/Python/JavaScript 执行、Todo 任务规划、Memory、定时任务、Web search/fetch、消息发送等工具。
- **国内消息渠道**：聚焦飞书、钉钉、企业微信、微信；优先 websocket / stream，微信保留 iLink long-poll。
- **Dashboard-first**：提供状态查看、会话、配置、渠道诊断、运行配置、日志、技能等管理入口。
- **持久化**：使用 SQLite 保存会话、策略、定时任务、渠道状态等运行数据。
- **技能与记忆**：支持本地 Skills、Skills Hub 导入、长期记忆、用户画像和上下文文件协作。
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

### 环境要求

- JDK 8+（推荐 JDK 17）
- Maven 3.9+
- Node.js 20+ 与 npm
- 可访问目标大模型服务的网络环境

### 克隆与构建

```bash
git clone https://github.com/chengliang4810/solon-claw.git
cd solon-claw
mvn -DskipTests package
```

Maven 默认会在 `generate-resources` 阶段执行 `web` 前端的 `npm install` 与 `npm run build`。如果你只想构建后端，可使用：

```bash
mvn -DskipTests -Dskip.web.build=true package
```

### 运行

```bash
java -jar target/solon-claw-0.0.1.jar
```

服务默认监听：

```text
http://127.0.0.1:8080
```

运行后会在当前目录创建 `runtime/`，用于保存配置、SQLite 数据库、缓存、日志、技能和上下文文件。运行态子目录由程序内置派生：`context/`、`skills/`、`cache/`、`logs/` 和 `data/state.db`。

### Docker Compose

```bash
docker compose up -d
```

默认 Compose 会将本地 `./runtime` 挂载到容器内 `/app/runtime`，方便持久化运行数据。镜像内服务默认以非 root 用户 `solonclaw` 运行，UID/GID 为 `10000:10000`。如果从旧镜像或 root 容器迁移后看到 `/app/runtime is not writable`，先在服务器项目目录执行：

```bash
sudo mkdir -p runtime
sudo chown -R 10000:10000 runtime
sudo chmod -R u+rwX runtime
docker compose up -d
```

不要覆盖镜像 entrypoint 后直接以 root 启动 `java -jar /app/solon-claw.jar`。官方镜像的 `/app/docker-entrypoint.sh` 会先把进程降权到 `solonclaw` 用户，再启动服务；如果绕过这一步，程序会默认拒绝启动，避免在 `/app/runtime` 留下 root 拥有的状态文件。只有明确接受这个风险时，才设置 `SOLONCLAW_ALLOW_ROOT_GATEWAY=1`。

如果你希望容器内用户匹配宿主机当前用户，也可以在启动前设置：

```bash
export SOLONCLAW_UID="$(id -u)"
export SOLONCLAW_GID="$(id -g)"
docker compose up -d
```

也兼容常见 NAS 平台使用的 `PUID` / `PGID` 环境变量；同时设置时，`SOLONCLAW_UID` / `SOLONCLAW_GID` 优先生效。

## 配置

默认配置位于：

```text
src/main/resources/app.yml
```

模型提供方的标准配置使用 `providers` 与 `model` 结构，建议通过 Dashboard 或 `runtime/config.yml` 维护。

```text
runtime/config.yml
```

`runtime/config.yml` 不配置运行目录本身；运行目录在启动级配置中决定，默认使用当前目录下的 `runtime/`。

完整示例可参考仓库根目录的 `config.example.yml`。服务启动时也会把该示例同步到 `runtime/config.example.yml`，它只是只读参考模板；实际生效配置仍是 `runtime/config.yml`。

最小 `runtime/config.yml` 示例：

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
  guardrailMode: approval
  guardrailCronMode: approval
  guardrailCronScope: job
  hardlineAllowlist:
    - hardline_shutdown
    - hardline_windows_shutdown
approvals:
  timeoutSeconds: 60
  gatewayTimeoutSeconds: 300
  mcpReloadConfirm: true
solonclaw:
  dashboard:
    accessToken: "admin"
```

常用运行配置：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8080` | HTTP 服务端口 |
| `providers.<key>.baseUrl` | - | 模型服务基础地址 |
| `providers.<key>.apiKey` | - | 模型服务 API Key |
| `providers.<key>.defaultModel` | - | 该提供方默认模型 |
| `providers.<key>.dialect` | `openai` | 协议方言 |
| `model.providerKey` | `default` | 当前默认提供方 |
| `model.default` | 空 | 全局默认模型覆盖；为空时使用 provider 的 `defaultModel` |
| `solonclaw.llm.stream` | `true` | 是否启用流式输出 |
| `solonclaw.llm.reasoningEffort` | `medium` | 默认推理强度 |
| `solonclaw.scheduler.enabled` | `true` | 是否启用定时任务调度 |
| `solonclaw.browser.rewriteLoopbackUrls` | `false` | 容器内浏览器访问宿主机 loopback 服务时是否改写 URL |
| `security.allowPrivateUrls` | `true` | 是否允许 URL 工具访问 localhost / 内网地址；云元数据地址默认仍阻断 |
| `security.websiteBlocklist.enabled` | `false` | 是否启用 webfetch/websearch/codesearch 域名阻断列表 |
| `security.tirithEnabled` | `true` | 是否启用 Tirith 命令内容扫描 |
| `security.tirithFailOpen` | `true` | Tirith 不可用或超时时是否放行；设为 `false` 会 fail-closed |
| `security.guardrailMode` | `approval` | Agent 工具安全策略：`approval`、`strict`、`bypass`、`smart` |
| `security.guardrailCronMode` | `approval` | 定时任务安全策略：`approval`、`strict`、`bypass`、`approve` |
| `security.guardrailCronScope` | `job` | 定时任务审批记忆范围：`job`、`session`、`global` |
| `security.hardlineAllowlist` | `hardline_shutdown`, `hardline_windows_shutdown` | 允许跳过 hardline 硬阻断的类别；`*` 表示放行所有 hardline |
| `approvals.timeoutSeconds` | `60` | 本地/直接审批超时秒数 |
| `approvals.gatewayTimeoutSeconds` | `300` | 消息渠道审批超时秒数 |
| `approvals.mcpReloadConfirm` | `true` | `/reload-mcp` 是否需要确认 |
| `terminal.credentialFiles` | 空 | 可挂载到隔离执行环境的 runtime 相对凭据文件列表 |
| `terminal.envPassthrough` | 空 | 允许传给本地子进程的第三方环境变量名 |
| `terminal.sudoPassword` | 空 | 可选 sudo 密码，用于 `sudo -S` 改写 |
| `terminal.writeSafeRoot` | 空 | 非空时限制文件写入、patch 和 shell 写入根目录 |
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
| `solonclaw.web.searchBackend` | `solon-ai` | Web 搜索后端：`solon-ai`、`brave-free`、`ddgs` |
| `solonclaw.pricing.prices` | 空 | 模型价格配置；为空时只统计 token，不计算价格 |
| `solonclaw.plugins.enabled` / `disabled` | 空 | 插件启用/禁用列表 |

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
├── scheduler/      # Cron 与 heartbeat 调度
├── skillhub/       # Skills Hub、导入、校验与来源适配
├── storage/        # SQLite 仓储实现
├── support/        # 通用运行期支持类
├── tool/           # 内置工具注册与实现
└── web/            # Dashboard 后端服务与控制器
```

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
