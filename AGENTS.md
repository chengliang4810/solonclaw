# AGENTS

## 目标

本文件面向当前 `SolonClaw` 仓库，帮助新的代理或开发者快速理解这套项目的真实结构、运行约束和协作规则。

这不是 Solon 教程摘录，而是“基于当前代码状态”的开发指南。

## 当前技术栈

- Java `17`
- Solon `3.9.5`
- `solon-web`
- `solon-ai`
- `solon-ai-agent`
- `solon-serialization-snack4`
- `solon-test`
- Hutool `5.8.44`
- 钉钉 Stream SDK：`com.dingtalk.open:dingtalk-stream:1.1.0`
- 钉钉 OpenAPI SDK：`com.aliyun:dingtalk:1.5.59`

参考资料仍然是 `docs/Solon-v3.9.4.md`，但仓库真实实现以代码为准。

## 项目入口与装配方式

应用入口是 [SolonClawApp.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/SolonClawApp.java)。

所有运行时 Bean 统一在 [SolonClawConfig.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/config/SolonClawConfig.java) 中装配，项目自定义配置通过：

- `@BindProps(prefix = "solonclaw")`
- [SolonClawProperties.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/config/SolonClawProperties.java)

默认约定：

- 新增组件、控制器、配置类放在 `com.jimuqu.claw` 包下
- 第三方对象或复杂对象优先用 `@Configuration + @Bean`
- 普通业务服务优先保持容器托管，不要手动 `new`

## 当前核心架构

项目已经不是单纯的 Solon Web Demo，而是一套“统一运行时 + 多渠道适配”的 Agent 服务。

### 1. 统一消息模型

位于 [agent/model](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/agent/model)：

- `InboundEnvelope`：标准化后的入站消息
- `OutboundEnvelope`：标准化后的出站消息
- `ReplyTarget`：唯一可信的回复路由
- `AgentRun`：一次消息处理任务
- `ConversationEvent`：会话事件
- `RunEvent`：任务过程事件

协作硬规则：

- 回复路由只能来自 `ReplyTarget`
- 不允许根据“当前上下文”猜回复目标
- 渠道之间的会话键必须隔离，不能共享命名空间

### 2. 运行时主链路

核心类位于 [agent/runtime](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/agent/runtime)：

- [AgentRuntimeService.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/agent/runtime/AgentRuntimeService.java)
- [ConversationScheduler.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/agent/runtime/ConversationScheduler.java)
- [SolonAiConversationAgent.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/agent/runtime/SolonAiConversationAgent.java)
- [HeartbeatService.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/agent/runtime/HeartbeatService.java)

当前实际流程：

1. 渠道把外部消息转成 `InboundEnvelope`
2. `AgentRuntimeService` 先做去重、写入会话事件、保存 `ReplyTarget`
3. 为该消息创建独立 `runId`
4. `ConversationScheduler` 按 `sessionKey` 控制单会话并发
5. `ConversationAgent` 基于历史和当前消息生成回复
6. 结果追加为会话事件和任务事件
7. 如果是外部渠道消息，则通过原渠道回发

### 3. 并发与一致性规则

这是当前项目最重要的行为约束：

- 每条消息都是独立 run
- 同一会话允许并行
- 单会话最大并发来自 `solonclaw.agent.scheduler.maxConcurrentPerConversation`，默认 `4`
- 当同一会话已有活跃任务时，可以立即回执“已收到”
- 历史构建按“用户消息顺序 + 已完成回复”重建，不按完成时间倒灌重排
- 重启后未完成任务会被标记为 `ABORTED`

任何后续扩展都不能把系统退回成“全局串行队列”。

## 文件持久化约定

运行时落盘统一由 [RuntimeStoreService.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/agent/store/RuntimeStoreService.java) 负责。

当前使用纯文件持久化，根目录由 `solonclaw.storage.runtimeDir` 控制，默认 `./runtime`。

当前目录语义：

- `runtime/runs`：run 明细与 run 事件
- `runtime/conversations`：会话事件和会话元数据
- `runtime/dedup`：消息去重标记
- `runtime/meta`：最近回复路由等元数据
- `runtime/media`：按渠道分目录的媒体缓存

协作规则：

- 会话历史只能通过 `RuntimeStoreService` 读取和追加
- 不要在别处自己拼 JSON 文件格式
- 如果新增事件类型，优先保持向后兼容，不要破坏已有 JSONL 结构

## 当前已落地渠道

### Debug Web

本地调试页已经接入，相关文件：

- [DebugChatController.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/web/DebugChatController.java)
- [RootController.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/web/RootController.java)
- [index.html](D:/IdeaProjects/SolonClaw/src/main/resources/static/index.html)

调试接口：

- `POST /api/debug/chat`
- `GET /api/debug/runs/{runId}`
- `GET /api/debug/runs/{runId}/events`

约定：

- `debug-web` 是独立渠道
- 调试页和钉钉共用同一个 Agent 运行时
- `debug-web` 不应污染外部渠道的路由记录

### 钉钉

钉钉实现位于 [channel/dingtalk](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/channel/dingtalk)：

- [DingTalkChannelAdapter.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/channel/dingtalk/DingTalkChannelAdapter.java)
- [DingTalkAccessTokenService.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/channel/dingtalk/DingTalkAccessTokenService.java)
- [DingTalkRobotSender.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/channel/dingtalk/DingTalkRobotSender.java)

当前钉钉方案已经固定为：

- 收消息：`DingTalkStreamTopics.BOT_MESSAGE_TOPIC`
- 回调类型：`OpenDingTalkCallbackListener<ChatbotMessage, JSONObject>`
- 发消息：官方机器人 OpenAPI
- 群聊发送：`orgGroupSend`
- 私聊发送：`batchSendOTO`

当前代码里的行为边界：

- 私聊白名单来自 `allowFrom`
- 群聊白名单来自 `groupAllowFrom`
- 白名单为空时默认拒绝该类型消息
- 群聊消息回群
- 私聊消息回原用户
- 回复内容只走 `ReplyTarget`，不走 webhook 猜测
- 附件当前只做文本化退化，不做复杂媒体回发

如果要新增企业微信、QQ 等渠道，应该直接复用：

- `ChannelAdapter`
- `ChannelRegistry`
- `InboundEnvelope / OutboundEnvelope / ReplyTarget`
- `AgentRuntimeService`

不要为新渠道绕开统一运行时单独写一套消息处理闭环。

## AI 模型约定

聊天模型由 [ChatModelConfig.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/llm/ChatModelConfig.java) 提供 Bean。

当前约定：

- 统一注入 `ChatModel`
- 具体模型参数来自 `solon.ai.chat.default`
- 当前默认偏向本地 Ollama
- Agent 执行层由 `SolonAiConversationAgent` 封装，不要在控制器或渠道层直接拼模型调用

协作建议：

- 若要扩展 tool、memory、prompt 策略，优先修改 `ConversationAgent` 实现层
- 不要在渠道代码里直接依赖具体 LLM 细节

## 配置规则

主配置文件是 [app.yml](D:/IdeaProjects/SolonClaw/src/main/resources/app.yml)。

当前关键配置：

- `solon.env=prod`
- `server.port=12345`
- `solon.config.add=./config.yml` 仅在 `prod` 段追加
- `solonclaw.storage.runtimeDir=./runtime`
- `solonclaw.agent.scheduler.maxConcurrentPerConversation=4`
- `solonclaw.agent.scheduler.ackWhenBusy=true`
- `solonclaw.agent.heartbeat.enabled=true`
- `solonclaw.agent.heartbeat.intervalSeconds=1800`
- `solonclaw.channels.dingtalk.*`

敏感信息约定：

- 真实钉钉密钥不进仓库
- 生产环境通过外部 `./config.yml` 注入
- 示例配置在 [scripts/config.example.yml](D:/IdeaProjects/SolonClaw/scripts/config.example.yml)

注意：

- [app-dev.yml](D:/IdeaProjects/SolonClaw/src/main/resources/app-dev.yml) 更像本地开发环境配置，不要随意覆盖他人的本地模型配置
- 测试配置在 [src/test/resources/app.yml](D:/IdeaProjects/SolonClaw/src/test/resources/app.yml)

## Solon 使用约定

结合当前代码，项目里优先遵守这些 Solon 规则：

- 业务服务优先 `@Bean` 或 `@Component`
- Web 接口优先 `@Controller + @Mapping`
- 配置聚合优先 `@BindProps`
- 生命周期资源优先 `@Bean(initMethod=..., destroyMethod=...)`

当前项目已经这样使用：

- `DingTalkAccessTokenService` 通过 `initMethod="start"` / `destroyMethod="stop"` 管理
- `DingTalkChannelAdapter` 通过 `initMethod="start"` / `destroyMethod="stop"` 管理
- `HeartbeatService` 通过 `initMethod="start"` / `destroyMethod="stop"` 管理

后续如果再加长连接、轮询器、定时器，优先复用这种方式。

## 测试与验证

当前测试覆盖了：

- 基础 Solon 启动与 HTTP 测试
- `ChatModel` Bean 装配
- 运行时落盘
- 并发/回执逻辑
- 心跳逻辑
- 钉钉入站转换和白名单行为

常用命令：

```bash
mvn -q -DskipTests compile
mvn -q test
mvn clean package -DskipTests
java -jar target/solonclaw.jar
java -jar target/solonclaw.jar --env=dev
```

说明：

- [ChatModelConfigTest.java](D:/IdeaProjects/SolonClaw/src/test/java/com/jimuqu/claw/llm/ChatModelConfigTest.java) 在本地 Ollama 不可达时会跳过真实对话测试
- 钉钉配置缺失时，钉钉渠道不会启动，但本地 debug-web 仍可工作

## 修改代码时的默认规则

1. 新增渠道先抽象成 `ChannelAdapter`，再注册到 `ChannelRegistry`
2. 回复必须绑定 `ReplyTarget`，不能临时猜测去向
3. 会话历史只能通过 `RuntimeStoreService` 维护
4. 长时运行资源必须显式接入 Solon 生命周期
5. 新增配置优先并入 `SolonClawProperties`
6. 调试能力优先接到现有 `debug-web` 入口，不要单独造第二套本地测试通道
7. 钉钉相关改动要同时考虑私聊、群聊、白名单、回执和心跳
8. Git 提交信息使用中英双语描述，推荐格式：`增加了xx功能 (Add xx feature)`

## 参考入口

- [SolonClawApp.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/SolonClawApp.java)
- [SolonClawConfig.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/config/SolonClawConfig.java)
- [SolonClawProperties.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/config/SolonClawProperties.java)
- [AgentRuntimeService.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/agent/runtime/AgentRuntimeService.java)
- [RuntimeStoreService.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/agent/store/RuntimeStoreService.java)
- [DingTalkChannelAdapter.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/channel/dingtalk/DingTalkChannelAdapter.java)
- [DingTalkRobotSender.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/channel/dingtalk/DingTalkRobotSender.java)
- [DebugChatController.java](D:/IdeaProjects/SolonClaw/src/main/java/com/jimuqu/claw/web/DebugChatController.java)
- [app.yml](D:/IdeaProjects/SolonClaw/src/main/resources/app.yml)
- [scripts/config.example.yml](D:/IdeaProjects/SolonClaw/scripts/config.example.yml)
