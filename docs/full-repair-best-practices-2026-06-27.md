# 阶段 6 最佳实践收口记录

日期：2026-06-27

## 对应能力点

- 对应项目级架构约束、工具系统安全边界、Dashboard 服务复用和持续验证流程。
- 本文只记录本轮全面修复已经采用的实践，不新增实现范围。

## 已遵循的实践

1. 优先复用 Solon 项目边界
   - 新增工具和 Dashboard 能力优先复用现有服务层、仓储和配置解析器。
   - 不引入 Spring Boot、Jackson、Fastjson、Gson 或其它替代性主框架和序列化方案。

2. 使用窄工具替代万能 API
   - Dashboard 独有能力按 `run_manage`、`mcp_manage`、`provider_manage`、`session_manage`、`diagnostics_manage` 等窄工具补齐。
   - 不新增可以任意调用 Dashboard API 的通用 HTTP 工具，避免绕过既有审批、脱敏、URL 安全和配置校验边界。

3. 高风险入口保留强边界
   - 检查点回滚、会话删除、审批 resolve/revoke、OAuth 回调和聊天运行主链不作为普通自然语言工具暴露。
   - 密钥配置写入继续走专用密钥路径，普通工作区配置维护只允许非密配置。

4. 前端入口与后端能力对齐
   - 对静态路由、API 包装和页面操作入口做差异复核。
   - 已删除或隐藏前端中必然失败的超前入口，已补齐媒体下载引用、运行控制等高确定性后端能力入口。

5. 展示状态优先避免误导
   - 页面加载失败时清理旧数据并保留页面内错误提示。
   - 运行中统计页增加可见状态刷新，避免用户把历史数据误判为当前状态。

6. 重复代码处理以收益为边界
   - 对生产代码和测试支撑中高价值重复做复用。
   - import 头部、公开工具重载、语义不同的安全判断和低收益 UI 相似项不为消除重复而强行抽象。

7. 原子提交和本地 `dev` 合并
   - 每个修复项单独提交，提交信息使用中英双语。
   - 每批修复通过验证后快进合并到本地 `dev`，不把未验证改动混入 `dev`。

## 验证策略

- Java 侧使用聚焦 Maven 测试优先验证改动面，必要时补 `mvn -Dskip.web.build=true -DskipTests compile` 或 `test-compile`。
- Web 侧使用 `npm run build --prefix web` 验证类型和构建。
- TUI 侧使用对应单测、type-check 和 lint 验证。
- 提交前执行 `git diff --check`。
- 命名门禁使用 `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`。
- 重复代码复核使用 `python3 scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`。

## 阶段结论

- 阶段 6 不需要新增依赖或额外架构层；本轮最佳实践重点是把已执行的边界、复用和验证方式沉淀成可复查记录。
- 后续新增功能时，应继续优先选择“复用现有服务 + 窄入口 + 明确验证”的路径，避免为自然语言操作能力打开泛化后门。
