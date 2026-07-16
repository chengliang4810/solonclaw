# 预存问题清单核验报告

日期：2026-07-17

## 核验范围

本报告核验以下预存清单在当前基线 `f274d8399` 上的状态：

- `docs/project-suggestions-2026-07-16.md`
- `docs/personal-assistant-optimization-2026-07-16.md`

状态只使用四类：

- `仍存在`：当前代码或流水线可直接证明问题仍在。
- `已修复`：当前实现、入口或提交历史可直接证明旧缺口已经关闭。
- `已过时`：旧建议与后续明确架构决策、安全边界或产品方向冲突。
- `证据不足`：旧报告是推断或优化设想，当前没有可复现缺陷证据。

## 工程与功能问题核验

| 编号 | 预存问题 | 当前状态 | 当前证据与处置 |
|---|---|---|---|
| P-01 | 六项核心能力状态未知 | 已修复 | 当前源码已存在媒体、语音、浏览器、价格、技能/MCP 和前端诊断入口；本报告以下条目逐项给出证据。旧问题是盘点缺失，不是功能缺陷。 |
| P-02 | `dev` 超前 `main` 8 个提交 | 已修复 | 当前 `main...dev` 为 `11 0`，`main...origin/dev` 为 `14 0`；旧积压已进入主线，当前反而是 `dev` 落后。 |
| P-03 | 依赖文件有未暂存改动 | 已修复 | 本轮起点工作树干净，依赖升级与锁文件已由 `a4ed9d8b0` 等提交固化。 |
| P-04 | 被移除的插件模块必须重建 | 已过时 | `6cd73e827` 明确完成“移除插件模块”；当前扩展边界是内置工具、Skills、MCP 与 Provider。恢复旧插件模块会重新引入已经删除的重复扩展体系。 |
| P-05 | 多模态输入、图像理解/生成缺失 | 已修复 | `media/ImageInputService`、`ImageGenerationService`、`MediaSpeechTools.image_generate`、`DashboardMediaController` 及 Dashboard 多模态诊断均已存在。 |
| P-06 | TTS 与独立语音转写缺失 | 已修复 | `SpeechService`、`SpeechProvider`、`TranscriptionProvider` 以及 `text_to_speech`、`speech_transcribe` 工具均已存在。 |
| P-07 | 内置浏览器自动化缺失 | 已修复 | `BrowserRuntimeService` 与 `BrowserTools` 已提供创建、导航、截图、提取、快照、滚动、键盘、图片枚举和 CDP 等受管能力。 |
| P-08 | 价格分析和费用展示缺失 | 已修复 | `PriceCatalog`、用量价格记录、`/api/analytics/usage`、`UsageView`、模型价格展示及 Dashboard 价格诊断均已存在；`36caaf610` 完成在线价格与异步用量记录。 |
| P-09 | `MemoryView` 是死文件、前后端路由脱节、运行命令无前端调用 | 已修复 | `MemoryView.vue` 已删除；当前路由已扁平化并注册 `/agents`、`/runs`、`/files` 等真实页面；`RunsView` 已调用 `controlRun` 并展示命令记录。旧 `/memory`、`/workspace` 等转发清单已失效。 |
| P-10 | CI 没有自动化测试门禁 | 仍存在 | `.github/workflows/naming.yml` 只执行静态脚本自测；`release.yml` 仍以 `mvn -B -DskipTests package` 构建，当前没有执行 `mvn test` 的工作流。纳入后续工程修复。 |

## 个人助手优化建议核验

| 编号 | 预存建议 | 当前状态 | 当前证据与处置 |
|---|---|---|---|
| A-01 | 新增 `agent.personal-mode` 并绕过记忆、文件审批 | 已过时 | 当前项目规范要求工具副作用经过安全、审批和审计边界；`MemoryApprovalCoordinator` 只对前台暂存写入做一次性确认。用总开关旁路审批会破坏既定安全边界，不纳入修复。 |
| A-02 | 配对完成后仍有不必要认证摩擦 | 证据不足 | 旧报告明确为推断，当前没有可复现步骤证明已绑定管理员消息仍被重复拦截。阶段 1 审计只在获得触发证据后立项。 |
| A-03 | 缺少定期跨会话反思 | 仍存在 | `HEARTBEAT.md` 默认模板为空，`AsyncSkillLearningService` 是事件驱动；当前没有批量读取近期真实会话并提炼长期规律的默认任务。作为阶段 4 数据/AI 驱动增强项评估。 |
| A-04 | 缺少旧每日记忆滚动压缩 | 仍存在 | 当前 `FileMemoryService` 提供读写、检索和索引维护，但未发现按保留期归纳并归档 `memory/YYYY-MM-DD.md` 的任务。作为阶段 4 数据治理项评估，禁止未经确认删除原始记忆。 |
| A-05 | Curator 只按规则标记，不做 LLM 内容评估 | 仍存在 | `SkillCuratorService.reviewSkill` 仍按使用次数、时间和静态内容标记生成建议，没有结合真实会话内容的 LLM 合并评估。纳入阶段 4.3。 |
| A-06 | 新增独立 `REFLECTION.md` 槽位 | 仍存在 | `ContextFileConstants` 目前没有 `REFLECTION.md`。这是信息架构增强，不是现有功能 Bug；需与反思任务一起设计，避免再增加无消费方的上下文文件。 |
| A-07 | system prompt 应按内容类型动态分配预算 | 仍存在 | `FileContextService` 默认单文件 12,000 字符、总计 48,000 字符，虽然可配置，但没有按静态人格、长期记忆和近期记忆分别设优先级。纳入阶段 4/5 的效果与截断风险评估。 |
| A-08 | 清理完整 Profile 子容器体系 | 已过时 | `5bbbd2e1a` 明确完成 Profiles 全量实现，`444866ff8` 又增加 Profile 协作任务；Profile 已是当前产品能力而非无主死代码。后续只修具体缺陷，不做整包删除。 |

## 纳入本轮的预存工作

1. 阶段 1/7：为 CI 增加真实、稳定的自动化测试门禁，避免发布流程长期只编译不测试。
2. 阶段 4：设计基于真实会话内容的定期反思，并评估独立反思存储、记忆归档和 prompt 预算优先级。
3. 阶段 4：让 Curator 的增强建立在真实技能内容、使用记录和会话证据上，确定性生命周期规则保留为兜底。

以下内容不纳入：恢复旧插件模块、删除 Profile 体系、以 personal-mode 总开关绕过审批。它们不是当前 Bug，且会违反现有架构或安全边界。

## 基线验证

- `mvn -Dskip.web.build=true test`：通过，2414 个测试，0 失败，0 错误，3 跳过。
- 当前审计只修改文档，没有改动运行代码。
