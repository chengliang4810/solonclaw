# 阶段 3.3 功能复用改造进度记录

日期：2026-06-30

## 对应能力点

- Dashboard-first setup：控制台与配置页面继续收敛重复交互壳，页面只保留业务加载、保存和路由逻辑。
- 测试支撑复用：安全策略边界测试使用同一组测试桩，避免多个测试类复制本地回环和固定 DNS 行为。
- 记忆 / 人格文件：Markdown 文档查看与编辑壳统一复用，保留各页面的数据来源和文案差异。

## 已完成复用项

### 1. 测试安全策略桩复用

- 提交：`bbbc4de2d refactor: 复用测试安全策略桩 / Reuse test security policy fixtures`
- 新增 `SecurityPolicyTestSupport`，统一提供固定 DNS 解析测试桩和允许本地回环但继续阻断云元数据地址的测试桩。
- 替换 `AppUpdateServiceTest`、`DefaultSkillHubHttpClientTest`、`DomesticQrSetupServiceTest`、`WeixinQrSetupServiceTest`、`RuntimeRefreshBehaviorTest`、`BoundedAttachmentIOTest` 中重复的内部类。
- 保留 `ProviderPublicUrlApprovalSecurityPolicyService` 与 token 阻断测试桩，因为它们覆盖的是不同测试语义。

验证：

- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=SecurityPolicyTestSupportTest test`
- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=SecurityPolicyTestSupportTest,AppUpdateServiceTest,DefaultSkillHubHttpClientTest,DomesticQrSetupServiceTest,WeixinQrSetupServiceTest,RuntimeRefreshBehaviorTest,BoundedAttachmentIOTest test`
- `git diff --check`
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`

### 2. Markdown 文档编辑面板复用

- 提交：`7e5c727ca refactor: 复用 Markdown 文档编辑面板 / Reuse Markdown document editor panel`
- 新增 `MarkdownDocumentPanel.vue`，统一 Markdown 展示、空态、加载态、textarea 编辑和保存/取消按钮。
- `MemoryView.vue` 和 `PersonaFileView.vue` 改为复用该面板。
- 页面层继续保留 API 调用、路由监听、只读判断、更新时间格式化、文案拼装和 `§` 到 Markdown 段落的展示转换。
- 新增 `test:markdown-document-panel-reuse` 静态测试，锁定两个页面只通过共享面板渲染文档编辑壳。

验证：

- `npm --prefix web run test:markdown-document-panel-reuse`
- `npm --prefix web run test:device-login-modal-reuse`
- `npm --prefix web run test:platform-qr-panel-reuse`
- `npm --prefix web run build`
- `cd web && bun <omo-programming-skill>/scripts/typescript/check-no-excuse-rules.ts tests/markdownDocumentPanelReuseStatic.test.ts`
- Playwright CLI 预览检查：`#/solonclaw/persona/memory`、`#/solonclaw/persona/memory_today`、`#/solonclaw/persona/user` 桌面和移动宽度可渲染，编辑态 textarea 与保存/取消按钮可见。
- `git diff --check`
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`

说明：预览检查时没有后端代理，页面控制台出现 `/api/*` 与 `/health` 的 502 或连接拒绝，属于预览环境限制，不影响本次组件复用的静态渲染、编辑态和构建验证。

## 延后候选

- `TerminalUiApprovalRespondTest` 初始化 fixture 复用：仍是高收益候选，但文件较大，应单独做红灯测试和定向验证。
- `CronjobTools` 内部请求对象化：可能影响工具签名边界，进入前需先做更细的调用图和兼容性检查。
- `DefaultCommandService` 构造器依赖收敛：属于更大结构调整，不应和当前阶段 3.3 小原子项混合提交。

## 阶段状态

阶段 3.3 已完成两个低风险复用原子项。下一步可继续处理 TUI 审批响应测试 fixture，或进入阶段 3.4 对已融合功能做边界增强评估。
