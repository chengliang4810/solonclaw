# solonclaw 项目建议清单（2026-07-16）

> **背景**：基于仓库现状自动审计生成，覆盖 git 健康、功能缺口、前端完整度、工程规范等维度。
> 结构审计和功能状态两个子任务因推理网关超时未能完成，部分结论基于其余探测结果推断。

---

## 🔴 立即处理（阻塞后续工作）

### 1. 完整功能现状盘点

本版必须实现的 6 项能力完成状态完全未知，所有功能排期都缺乏依据：

| 能力 | 状态 |
|------|------|
| 多模态模型输入 | 待盘点 |
| 图像理解/生成 | 待盘点 |
| TTS / 独立语音转写 | 待盘点 |
| 浏览器自动化（内置实现）| 待盘点 |
| 价格分析/价格计算 | 待盘点 |
| 插件系统 | ⚠️ 疑似已被移除 |

**下一步**：逐目录快速过一遍 `llm/`、`tool/`、`web/src/`，输出"已有 / 半成品 / 空缺"对照表，再排优先级。

---

### 2. 处理 dev → main 的合并积压

当前 `dev` 比 `main` **超前 8 个 commit**，已违反 CLAUDE.md 约定的"每满 5 次合并一次"规则。

**下一步**：确认这 8 个 commit 质量 OK（CI 绿、命名检查通过），立即合并到 main 并打 tag，清零计数。

---

### 3. 提交或还原未暂存的依赖变更

以下文件有未暂存改动，会影响下一次提交的 diff 清洁度：

- `pom.xml`
- `terminal-ui/package.json`、`terminal-ui/package-lock.json`
- `web/package.json`、`web/package-lock.json`

**下一步**：`git diff` 确认是版本升级还是意外改动，决定暂存提交还是 `git restore`。

---

## 🟠 本版必须实现（功能缺口）

### 4. 插件系统

commit 历史里出现了 `plugin module removal`，但插件系统是本版**明确必须做**的能力之一，移除方向相反。

**下一步**：确认移除原因（重构重来 vs. 误删），如需重建，先在 `bootstrap/` 或独立模块里设计插件注册与加载的最小接口，不要做"泛工作流平台"式的过度设计。

---

### 5. 多模态输入 + 图像理解/生成

Solon AI 4.0.x 是否已支持多模态消息结构（`ImageContent` / `AudioContent`）需要核查；`DashboardMediaController` 已有 6 个端点但前端几乎未接入。

**下一步**：
1. 查阅 Solon AI 4.0.3 多模态 API（参考 `solon-ai.git` 对应 tag）
2. 在 `llm/` 的 Gateway 层补充多模态消息组装
3. 给 Media 控制器的图像端点建一个最小前端入口

---

### 6. TTS / 独立语音转写

代码库里目前看不到 TTS 和 STT 相关模块。

**下一步**：确认放在 `tool/` 还是 `llm/` 的 dialect 层；优先对接国内可用服务（阿里云/讯飞），做成内置工具形式，不要暴露为外部 plugin。

---

### 7. 浏览器自动化（内置实现）

CLAUDE.md 强调"内置实现"，意味着不能简单调外部 MCP 工具。目前未见相关模块。

**下一步**：在 `tool/` 下建 `browser/` 子包，用 Playwright Java 或 Selenium 做最小封装，先实现三个工具：
- 打开页面
- 截图
- 提取文本

---

### 8. 价格分析/计算

与 `llm/` 的使用量统计强相关，Dashboard 端需要展示 token 用量和估算费用。

**下一步**：在 `llm/` 里补充 provider 价格配置表，接入 `DashboardInsightsController` 的 overview 端点，前端在 `DiagnosticsView` 或独立页面展示。

---

## 🟡 前端补全（影响 Dashboard 可用性）

### 9. 清理孤立前端文件，补全缺失路由

**已发现的问题：**

- `MemoryView.vue`（177 行）无路由注册，是死文件
- 后端 SPA 转发了 `/memory`、`/agents`、`/workspace`、`/cron`、`/env`、`/sessions`，但 `router/index.ts` 里一个都没有
- `DashboardRunController` 的 `/api/runs/{runId}/commands` 端点前端未调用

**下一步**：逐一确认哪些路由是"计划中但未实现"，删掉死文件，补上确实需要的路由，避免后端路由和前端路由越来越脱节。

---

## 🟢 工程健康（有余力时处理）

### 10. CI 加入自动化测试门控

当前 `release.yml` 和 `packages.yml` 跑的都是 `-DskipTests`，命名检查是唯一的质量门控。4 个已知失败的测试如果一直绕过，会越来越难修。

**下一步**：在 `naming.yml` 或单独的 `test.yml` 里加一个 `mvn test` step，用 `-Dtest=!DockerTest,...` 排除已知失败项，让其余测试在 CI 里跑起来。

---

## ⚠️ 风险警告

| 类型 | 描述 |
|------|------|
| **功能倒退** | Plugin module 被移除，但插件系统是本版明确必做项，需立即确认原因并补回 |
| **规则违反** | dev 分支已超前 main 8 个 commit，违反了 CLAUDE.md 的 5-commit 合并规则 |
| **脏工作区** | pom.xml / package-lock.json 有未暂存改动，提交前需清理 |
| **信息盲区** | 6 项本版必做能力的实现状态完全未知，功能盘点是最高优先级 |

---

## 优先级汇总

```
P0  功能盘点（输出对照表）
P0  dev 合并积压清零
P1  插件系统补回
P1  多模态 / TTS / 浏览器自动化逐个落地
P2  前端路由清理
P3  CI 测试门控
```
