# 预存问题清单复核基线

日期：2026-07-17

## 复核范围

本清单复核以下预存输入，并以当前提交 `f274d8399` 的代码和测试结果为准：

- `docs/project-suggestions-2026-07-16.md`
- `docs/personal-assistant-optimization-2026-07-16.md`
- 当前 Web 与 Terminal UI 原子功能审计结果
- 当前分支、工作树、CI 工作流和 2026-07-16 之后的提交记录

状态定义：

- **纳入修复**：当前仍可复现，进入本轮后续阶段。
- **已由当前树解决**：旧报告成立，但后续提交已经覆盖。
- **已过时或明确不采纳**：前提已变化，或与当前架构、安全边界冲突。
- **待证据或决策**：属于增强建议，不能当作已确认 Bug 直接实现。

## 一、纳入修复

| 编号 | 优先级 | 当前问题 | 证据 | 后续阶段 |
|---|---|---|---|---|
| PRE-01 | P1 | Web 会话缓存未按认证主体和服务端隔离，退出、401 或切换服务端后可能显示旧会话 | `web/src/stores/solonclaw/chat.ts`、`web/src/api/sessionAuth.ts` | 1.1、5.1 |
| PRE-02 | P1 | 切换 Profile 后聊天 Store 可能沿用旧 Profile 的活动会话，旧异步响应还可能覆盖新状态 | `web/src/App.vue`、`web/src/views/solonclaw/ChatView.vue`、`web/src/stores/solonclaw/chat.ts` | 1.1、2.3、5.1 |
| PRE-03 | P1 | Files 与 Jobs 的 Pinia 状态未按 Profile 隔离，切换后可能把旧内容保存到新 Profile | `web/src/stores/solonclaw/files.ts`、`web/src/stores/solonclaw/jobs.ts` 及对应页面 | 1.1、2.3、5.1 |
| PRE-04 | P1 | Persona 文件快速切换存在旧响应覆盖新文件并写错目标的竞态 | `web/src/views/solonclaw/PersonaFileView.vue` | 1.1、5.1 |
| PRE-05 | P1 | Runs 页面快速切换会话时，旧响应可覆盖当前 Run 数据并诱发错误控制操作 | `web/src/views/solonclaw/RunsView.vue` | 1.1、5.1 |
| PRE-06 | P1 | MCP OAuth 请求未绑定发起时的 Server，旧响应可能污染当前 Server 表单和状态 | `web/src/views/solonclaw/McpView.vue` | 1.1、5.1 |
| PRE-07 | P2 | API 返回 401 后跳转登录页没有保留原始页面，重登录固定回到聊天页 | `web/src/api/client.ts` | 1.1、5.2 |
| PRE-08 | P2 | TUI 多组轮询直接进入串行 RPC 队列，慢请求期间会持续积压并阻塞用户操作 | `terminal-ui/src/gatewayClient.ts`、`terminal-ui/src/app/useMainApp.ts`、`terminal-ui/src/components/activeSessionSwitcher.tsx`、`terminal-ui/src/app/useConfigSync.ts` | 1.1、5.1 |
| PRE-09 | P2 | Profile UI 静态契约仍要求已删除的终端初始化能力，当前 `npm run test:profiles` 失败 | `web/tests/profileUiStatic.test.ts` 与 `web/src/api/solonclaw/profiles.ts` | 1.1 |
| PRE-10 | P2 | TUI resize 测试无条件要求 Apple Terminal 深度清屏序列，与当前按终端分支的实现不一致 | `terminal-ui/packages/solonclaw-ink/src/ink/ink-resize.test.ts`、`ink.tsx` | 1.1 |
| PRE-11 | P2 | CI 只运行命名与日志门禁，发布构建仍使用 `-DskipTests`，没有后端和前端测试门禁 | `.github/workflows/naming.yml`、`release.yml`、`packages.yml` | 1.1 |
| PRE-12 | P2 | 默认 HEARTBEAT 模板仍为空，没有旧建议所述的定期跨会话反思任务 | `src/main/resources/persona-templates/HEARTBEAT.md` | 4.1、4.3 |
| PRE-13 | P2 | 没有自动归并历史日记的记忆压缩任务，当前只有文件数量/大小约束和手动清理指引 | `src/main/resources/persona-templates/AGENTS.md`、`src/main/java/com/jimuqu/solon/claw/context/FileMemoryService.java` | 4.1、5.1 |
| PRE-14 | P3 | Skill Curator 仍以使用次数和时间阈值标记 stale/archived，没有结合真实内容做 LLM 合并评估 | `src/main/java/com/jimuqu/solon/claw/context/SkillCuratorService.java` | 4.1、4.3 |

## 二、已由当前树解决

| 旧问题 | 当前结论与证据 |
|---|---|
| 六项核心能力状态未知 | 已完成代码盘点。多模态、图像生成/理解、TTS/STT、CDP 浏览器自动化、价格计算均有运行服务和测试；插件模块是明确移除，不属于未知状态。 |
| `dev` 超前 `main` 8 个提交 | 已过期。当前 `git rev-list --left-right --count main...dev` 为 `11 0`，不存在旧报告所述的合并积压。 |
| 依赖文件存在未暂存改动 | 已解决。阶段 0.2 复核时工作树干净，依赖锁文件已由 `a4ed9d8b0` 同步。 |
| 多模态、图像、语音能力缺失 | 已解决。`media/` 下存在图像生成、视觉分析、OpenAI 兼容 TTS/STT，且 `MediaImageToolsTest`、`MediaSpeechServiceTest` 覆盖主要行为。 |
| 内置浏览器自动化缺失 | 已解决。`BrowserRuntimeService` 与 `CdpBrowserProvider` 已覆盖导航、点击、输入、截图、文本提取和扩展 CDP 操作，`BrowserRuntimeServiceTests` 有对应测试。 |
| 价格分析与计算缺失 | 已解决。`PriceCatalog`、`UsageCostCalculator`、用量事件持久化和 Dashboard 统计已经形成完整链路，`UsagePricingTest` 覆盖定价计算。 |
| Memory 页面、后端转发路由和 Run commands 脱节 | 旧路径结论已被后续路由统一覆盖；Runs 页面通过 detail 契约展示 commands，当前 `test:backend-ui-coverage` 通过。 |

## 三、已过时或明确不采纳

| 旧建议 | 当前决策 |
|---|---|
| 恢复通用插件运行时 | 不采纳。`6cd73e827` 明确移除插件模块并把仍需能力收口到 Provider、Skill、MCP 与内置工具边界；恢复旧插件框架会重新引入 7,000 余行已删除复杂度。 |
| 删除完整 Profile 体系 | 已过时。当前主线随后新增 Profile 协作任务和 Agents Dashboard，Profile 已成为当前产品能力；本轮应修复隔离问题，不能按旧的单 Profile 假设删除。 |
| 个人模式下绕过 Memory、文件与技能审批 | 不直接采纳。项目规范要求工具副作用经过现有安全、审批和审计边界；是否新增可配置的低风险自动批准，需要单独威胁模型和产品决策，不能作为 Bug 修复旁路安全机制。 |
| 新建独立 `REFLECTION.md` 槽位 | 暂不作为缺陷。现有记忆分层已经把 `MEMORY.md` 用作提炼层、`memory/*.md` 用作原始/专题层；先修复反思调度和压缩闭环，再用实际上下文质量证明是否需要新增文件类型。 |

## 四、待证据或决策

1. 是否在不削弱默认安全策略的前提下，增加可审计、可撤销的低风险操作授权记忆。需要先梳理现有 always-allow、TUI 审批和渠道审批的共同语义。
2. 定期反思应复用 Heartbeat/Cron，还是单独建立调度器。优先验证现有调度链路能否提供最近会话、真实聊天文本和幂等执行证据。
3. 日记压缩是否允许删除原始文件。默认应保留可恢复归档，除非仓库现有数据生命周期策略明确允许删除。
4. Skill Curator 的 LLM 评估成本、失败回退和可撤销合并流程尚无验证，不能只把一次模型输出直接写回技能文件。

## 五、阶段 0.2 验证记录

- 当前分支：`work/comprehensive-repair-20260717`。
- 当前基线：`f274d8399`，阶段 0.1 提交为 `b6e931d9d`。
- Web 构建通过；`test:backend-ui-coverage` 通过。
- `test:profiles` 失败，首个失败点为缺少 `fetchProfileSetupCommand`。
- Terminal UI 类型检查通过；测试结果为 `1132 passed / 1 skipped / 1 failed`，失败点为 resize 清屏断言。
- 本清单只建立后续工作的权威输入；完整原子 Bug 报告在阶段 1.1 单独生成并提交。
