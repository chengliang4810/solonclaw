# CLAUDE.md

本文件为在本仓库工作的代码代理提供补充说明；更完整的项目约束以 `AGENTS.md` 为准。

## 项目定位与硬约束

- 这是一个 Java 8 源码兼容的单实例 Agent 服务，基于 Solon、Solon AI、Hutool、Snack4 和 SQLite；不要按 Spring Boot / Spring AI / Jackson / LangChain4j 习惯实现。
- 项目目标是用 Java / Solon 生态对齐外部对标 Agent 的核心行为与能力，重点是 Agent 主循环、工具调用、会话/记忆、技能、定时任务、国内消息渠道、Dashboard-first 配置与诊断。
- 新任务开始前先明确它对应外部对标仓库的哪个能力点；如果对应不上，先暂停确认，不要扩展成泛用聊天应用、泛工作流平台或无关 AI Demo。
- 仓库文件、配置、测试、路由、环境变量、文档、提交信息、Release notes 和用户可见输出中不得写入旧项目关键词；本项目命名以 `solonclaw` 为准，代码包名可沿用当前 `com.jimuqu.solon.claw` 结构。
- 国内消息渠道范围：保留 `feishu`、`dingtalk`、`wecom`、`weixin`、`qqbot`、`yuanbao`；明确不做 `sms`、`webhook`；海外渠道默认不做。
- 模型协议范围：`openai`、`openai-responses`、`ollama`、`gemini`、`anthropic`；其他 provider 默认不做，除非用户明确要求。
- 已确认不做：研究实验能力、Docker 之外执行后端、运行时 worktree 执行后端、OpenAI 兼容 API Server、Profiles、多实例/多租户/多机器人隔离。
- 本版需要做：多模态模型输入、图像理解/生成、TTS/独立语音转写、浏览器自动化内置实现、价格分析/价格计算、插件系统。
## 常用命令

### 后端 / 整体构建

```bash
mvn -DskipTests package
```

Maven 默认在 `generate-resources` 阶段进入 `web/` 执行 `npm ci` 和 `npm run build`，再把 `web/dist` 复制到后端静态资源。

```bash
mvn -DskipTests -Dskip.web.build=true compile
mvn -DskipTests -Dskip.web.build=true package
mvn test
mvn "-Dtest=DashboardControllerHttpTest" test
mvn spotless:check
mvn spotless:apply
java -jar target/solonclaw-0.0.1.jar
```

Windows PowerShell 中 `-Dtest=...` 建议加引号。后端服务默认监听 `http://127.0.0.1:8080`，运行后在当前目录创建 `workspace/`。

### 前端 Dashboard

```bash
cd web
npm ci
npm run dev
npm run build
npm run preview
```

`web/vite.config.ts` 将 `/api`、`/health`、`/upload` 代理到 `http://127.0.0.1:8080`。开发前端时通常先在仓库根目录启动后端，再在 `web/` 启动 Vite。

### Docker 与命名检查

```bash
docker compose up -d
python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
python3 scripts/check-raw-exception-logging.py
```

常规提交前的旧项目命名检查只允许扫描当前工作树和当前分支相对默认分支新增的提交；全 Git refs 扫描只作为人工历史审计。

## 架构概览

- 启动入口是 `com.jimuqu.solon.claw.SolonClawApp`：解析 CLI 模式，启用 WebSocket；console 模式关闭 HTTP 并交给 `CliRunner`，服务模式会经过 Docker root guard。
- `bootstrap/` 是 Solon 装配层，集中创建配置、存储、工具、调度器、Dashboard、Gateway 等 Bean；业务逻辑不要塞进配置类。
- `config/` 负责 `app.yml`、`workspace/config.yml` 与工作区目录解析；默认工作区目录是 `workspace/`，运行态数据包括 `context/`、`skills/`、`cache/`、`logs/` 和 `data/state.db`。
- `core/` 定义会话、消息、工具、渠道、配置、审批等领域模型与仓储/服务接口；`storage/repository/` 是 SQLite 实现。
- `engine/` 承载 Agent 会话编排主链路：多轮会话、工具调用循环、上下文预算/压缩、委托、pending run 恢复和会话搜索。
- `llm/` 是模型协议边界，`SolonAiLlmGateway` 通过 Solon AI 接入 provider/dialect、流式输出、工具调用、失败分类和使用量统计。
- `tool/` 注册并实现内置工具，包括文件、Shell/Python/JavaScript、补丁、Todo、Memory、Web search/fetch、消息、配置、安全审批等；工具副作用要走现有审批/安全策略。
- `gateway/` 是国内消息渠道层，按授权、命令、投递、反馈、连接管理拆分；平台适配器在 `gateway/adapters/`，微信保留 long-poll，其他优先 websocket/stream。
- `web/` 是 Dashboard 后端 API，通常按 `DashboardXxxController` + `DashboardXxxService` 配对；统一返回类型是 `DashboardResponse`，鉴权由 Dashboard auth 相关类处理。
- `scheduler/` 负责 Cron、heartbeat 和 Skills curator；调度结果可投递到渠道和会话。
- `context/` 管理 AGENTS/MEMORY/USER/Skills、persona templates、长期记忆和技能导入/策展。
- `cli/` 承载本地 CLI 与本地终端 TUI 交互；不要把终端展示逻辑混入 Agent 编排或渠道适配。
- `web/src/` 是 Vue 3 + Vite + TypeScript Dashboard；路由在 `web/src/router/index.ts`，API 客户端在 `web/src/api/client.ts`，业务页面集中在 `web/src/views/`。

## Solon / Solon AI 开发注意事项

- 遇到 Solon 或 Solon AI API、注解、插件、配置键不确定时，优先根据远程仓库获取或更新参考源码后再查证：Solon `https://gitee.com/opensolon/solon.git`，Solon AI `https://gitee.com/opensolon/solon-ai.git`；以当前项目依赖版本 `3.10.4` 为准。
- Controller、DI、配置、Filter、RouterInterceptor、AOP、Plugin 等写法必须沿用项目现有 Solon 风格，不要使用 `@RestController`、`@Autowired`、Spring MVC 异常处理或 Spring Validator。
- AI 能力优先使用 Solon AI 官方模块和本项目已有 `ChatModel`、工具调用、MCP、Agent、Skills 接入方式；只有官方能力明确缺失且无法满足目标行为时才自研补充。
- JSON 序列化/反序列化优先使用 `snack4`，通用工具优先使用 Hutool。

## Git 与发布约定

- 频繁开发提交默认推送到 `dev` 分支，不要每次小改直接推送 `main`。
- `dev` 分支从上一次合并到 `main` 后开始计数，累计每 5 次有效开发 commit 后再合并到 `main` 并推送一次；未满 5 次只有用户明确要求发布或紧急修复才提前合并。
- 每次提交前运行与改动匹配的编译或测试；验证失败不得提交。
- commit message 必须中英双语，优先格式：`type: 中文说明 / English summary`。
- GitHub Releases 描述必须中英双语，并包含功能变更、缺陷修复或其他变更的具体说明。
