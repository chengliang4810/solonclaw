# 无人值守全面修复阶段 1.1 增量缺陷报告

生成时间：2026-07-06

## 对应外部对标能力点

- Dashboard-first setup / doctor：主要页面入口应能从侧栏稳定进入，当前页面状态应清晰可见。
- UI/UX 与数据显示优化：导航状态不应被固定底部状态区遮挡，避免用户误判当前页面或漏看系统入口。

## 审计范围

本报告追加记录 2026-07-06 已由 Web UI 验证确认并修复的原子级功能缺陷。BUG-025 至 BUG-059 已记录在 `docs/full-repair-bug-report-2026-07-05.md`，这里不重复列入。

## BUG-060：Dashboard 侧栏底部页面当前入口会被状态区遮挡

状态：已修复，提交 `e7ae9e09f`

影响范围：

- Dashboard 左侧侧栏。
- 位于侧栏较靠下位置的系统页面入口，例如“工具接入（MCP）”。
- 服务器版本、部署模式、更新提示等底部状态信息加载后高度变化的页面。

当前事实：

- Web E2E 在 `1440x920` 视口打开 `#/solonclaw/mcp`。
- 旧布局下 `.sidebar-nav .nav-item.active` 底部约为 `745.015625`，而 `.sidebar-nav` 底部和 `.sidebar-footer` 顶部约为 `744.734375`，当前入口与底部状态区相交。
- 页面控制台没有 error/warn，问题集中在侧栏滚动定位和底部状态区高度变化。

根因：

- 侧栏导航容器有独立滚动区，但路由切换后没有稳定把当前项滚动到可见区域。
- 底部状态区在版本信息等元数据加载后会改变高度，初次滚动时机早于最终布局稳定状态。
- 滚动容器缺少底部滚动空间，靠底部的 active 项即使滚动到边缘也会贴住 footer。

修复记录：

- `AppSidebar.vue` 为侧栏导航增加模板引用，在路由变化后用 `scrollIntoView({ block: "nearest" })` 保持当前入口可见。
- 滚动监听使用 Vue `flush: 'post'` 并等待下一帧布局，避免早于 footer 元数据高度变化。
- 监听 `serverVersion`、`versionTag`、`deploymentMode`、`latestVersion` 等底部元数据变化，footer 高度变化后再次校正当前入口。
- `AppSidebar.scss` 为导航容器增加底部 padding，并为导航项增加 scroll margin，保证底部入口和 footer 留出间隔。
- `appSidebarNavMetadataStatic.test.ts` 增加侧栏滚动、底部留白和 footer 元数据变化触发的静态回归。

验证命令：

```bash
npm --prefix web run test:app-sidebar-nav-metadata
npm --prefix web run build
mvn "-Dskip.web.build=true" "-DskipTests" package
```

浏览器复核：

- 打开 `http://127.0.0.1:5174/#/solonclaw/mcp`。
- 修复后 `.sidebar-nav .nav-item.active` 底部约为 `737.015625`，`.sidebar-footer` 顶部约为 `744.734375`。
- 当前入口位于滚动容器内，且高于 footer；控制台 error/warn 为空。

## BUG-061：消息网关状态加载失败时界面无持久错误提示

状态：已修复，提交 `d57dccd2d`

影响范围：

- Dashboard 消息网关状态页。
- 后端网关状态接口失败或临时不可达时的排障体验。

当前事实：

- 网关状态请求失败时只进入空状态或旧静态展示，用户无法区分“暂无网关”和“加载失败”。
- 网关状态页没有自动刷新失败后的恢复路径，用户需要手动离开再返回页面。

根因：

- 网关 store 没有把加载失败写入可渲染状态。
- 网关视图没有把失败状态作为页面内横幅展示，也没有复用已有的刷新节奏。

修复记录：

- `gateways.ts` 增加 `loadError`，请求开始时清理旧错误，失败时保留错误文本。
- `GatewaysView.vue` 增加页面内错误横幅，并在失败时继续保留已有网关数据。
- 页面挂载后复用已有刷新入口，确保状态恢复后能自动更新。

验证命令：

```bash
node --experimental-strip-types web/tests/gatewayReadOnlyStatic.test.ts
node --experimental-strip-types web/tests/viteProxyTargetStatic.test.ts
npm --prefix web run build
python -X utf8 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
```

## BUG-062：模型提供方加载失败后隐藏已加载数据

状态：已修复，提交 `6714e8338`

影响范围：

- Dashboard 模型页提供方列表。
- Dashboard 设置页模型配置表单。
- 模型接口临时失败后的配置查看和修复流程。

当前事实：

- 模型提供方刷新失败时，store 会清空 `providers`、`allProviders`、默认模型、默认提供方、fallback 提供方和方言目录。
- 页面看到加载失败后只剩错误或空状态，之前已经加载成功的模型提供方和设置表单不可见。

根因：

- `fetchProviders()` 在 catch 分支把旧数据当作失败副作用清空。
- `ProvidersPanel.vue` 和 `ModelSettings.vue` 使用 `v-else` 分支，让错误提示替换掉旧数据区域。

修复记录：

- `models.ts` 失败时只写入 `loadError`，不清空上一轮成功数据。
- 模型列表和模型设置页改为错误横幅加旧数据继续渲染，只有无错误且无数据时才显示空状态。
- `modelsLoadFailure.test.ts` 增加 store 与 SSR 模板回归断言。

验证命令：

```bash
node --experimental-strip-types web/tests/modelsLoadFailure.test.ts
node --experimental-strip-types web/tests/providerDisplayOptions.test.ts
node --experimental-strip-types web/tests/chatApiModelCatalogStatic.test.ts
npm --prefix web run build
python -X utf8 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
```

## BUG-063：文件目录导航失败后会推进路径并清空旧列表

状态：已修复，提交 `ebbeaf14d`

影响范围：

- Dashboard 工作区文件页。
- 文件目录导航、刷新和错误恢复体验。

当前事实：

- `fetchEntries(path)` 在请求成功前就更新 `currentPath`。
- 请求失败后 `entries` 被清空，页面只剩错误状态；用户会同时丢失当前目录位置和旧文件列表。

根因：

- 文件 store 把目标路径作为请求前状态提交，失败时没有回滚。
- 文件列表组件用错误分支替换了列表区域，无法在错误横幅下继续展示上一轮成功数据。

修复记录：

- `files.ts` 使用局部 `nextPath` 请求，只有 `listFiles(nextPath)` 成功后才更新 `currentPath` 和 `entries`。
- 失败时保留旧 `entries` 和旧 `currentPath`，只写入 `loadError`。
- `FileList.vue` 改为错误横幅与旧列表并存。
- `filesLoadFailure.test.ts` 增加导航失败不推进路径、旧列表仍可见的回归断言。

验证命令：

```bash
node --experimental-strip-types web/tests/filesLoadFailure.test.ts
node --experimental-strip-types web/tests/workspaceFileRestoreStatic.test.ts
node --experimental-strip-types web/tests/filesUnsupportedActionsStatic.test.ts
node --experimental-strip-types web/tests/fileTypeIcon.test.ts
npm --prefix web run build
python -X utf8 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
```

## BUG-064：Vite 代理模式下消息网关页显示前端端口

状态：已修复，本次提交

影响范围：

- Dashboard 消息网关状态页。
- 使用 `SOLONCLAW_SERVER_URL` 连接非默认后端端口的 Web 开发与真实 E2E 验证场景。

当前事实：

- Chrome 真实打开 `http://127.0.0.1:5178/?token=smoke-token#/solonclaw/gateways`。
- 后端实际启动在 `http://127.0.0.1:18081`，但网关页显示 `websocket:5178`、`stream:5178` 等前端 Vite 端口。
- 页面功能和接口请求成功，控制台无 error/warn；问题集中在展示端口误导。

根因：

- `fetchGateways()` 通过 `window.location.port` 推导端口。
- 在 jar 内置页面中，前端端口等于后端端口；但 Vite 代理模式中，前端端口和后端端口不同。

修复记录：

- `vite.config.ts` 将显式配置的 `SOLONCLAW_SERVER_URL` 注入为前端构建常量，未配置时保持空值，避免生产包重新带回默认 `8080`。
- `gateways.ts` 优先从该后端地址解析展示端口，未配置时回退到当前页面端口。
- `gatewayReadOnlyStatic.test.ts` 增加 dev proxy 后端目标的静态回归断言。

验证命令：

```bash
node --experimental-strip-types web/tests/gatewayReadOnlyStatic.test.ts
npm --prefix web run build
python -X utf8 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
```

浏览器复核：

- 后端：`java -Dserver.port=18081 -Dsolonclaw.dashboard.accessToken=smoke-token -jar target/solonclaw-0.0.1.jar server`
- 前端：`SOLONCLAW_SERVER_URL=http://127.0.0.1:18081 npm --prefix web run dev -- --host 127.0.0.1 --port 5178`
- Chrome 打开 `http://127.0.0.1:5178/?token=smoke-token#/solonclaw/gateways` 后，网关条目显示 `websocket:18081`、`stream:18081`，控制台 error/warn 为空。

## BUG-065：TUI `/image` 附件未解析成功时仍提示已附加

状态：已修复，提交 `4ee56ecc3`

影响范围：

- 本地 TUI `/image <path>` 命令。
- 终端粘贴或输入本地图片路径后的附件感知主链。

当前事实：

- 后端 `image.attach` 在附件解析失败时仍提前返回 `name`。
- TUI `/image` 命令把 `name` 当作成功信号，用户会看到 `Attached image`，但后端没有写入真实附件缓存。

根因：

- `TerminalUiRpcService.imageAttach()` 在解析附件前先写入成功展示字段。
- `attachedImageNotice()` 没有识别 `attached=false` 的失败响应。

修复记录：

- `imageAttach()` 只有真实附件存在时才返回 `name`，失败时返回 `attached=false` 与错误消息。
- `ImageAttachResponse` 补齐 `attached`、`message` 和附件元数据字段。
- TUI 消息渲染在 `attached=false` 时显示失败消息，不再渲染成功附件提示。

验证命令：

```bash
npm test -- src/__tests__/createSlashHandler.test.ts
mvn "-Dskip.web.build=true" "-Dtest=TerminalUiRpcServiceTest#imageAttachReportsFailureWhenNoAttachmentResolved" test
npm --prefix terminal-ui run lint
npm --prefix terminal-ui run type-check
mvn "-Dskip.web.build=true" "-DskipTests" compile
python -X utf8 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
```

## BUG-066：设置页配置加载失败时没有页面内错误态

状态：已修复，提交 `f85cafa3b`

影响范围：

- Dashboard 设置页。
- 配置接口临时失败后的 dashboard-first setup / doctor 使用体验。

当前事实：

- `fetchSettings()` 捕获 `/api/config` 失败后只输出控制台错误。
- 页面停止 loading 后继续显示空表单，用户无法知道配置加载失败，也可能误以为空配置可保存。

根因：

- `settings` store 没有保存或暴露加载错误。
- `SettingsView.vue` 没有消费加载失败状态。

修复记录：

- `settings` store 增加 `loadError`，每次重试前清空，失败时保留错误消息，并保留上一轮成功配置。
- `SettingsView.vue` 在 tabs 前渲染持久错误横幅，使用统一 `common.fetchFailed` 文案。
- 新增静态回归测试覆盖 store 暴露错误与页面错误态位置。

验证命令：

```bash
node --experimental-strip-types web/tests/settingsLoadFailureStatic.test.ts
node --experimental-strip-types web/tests/settingsUnsupportedSectionsStatic.test.ts
npm --prefix web run build
python -X utf8 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
```

## 当前结论

- BUG-060 已按侧栏共享组件层修复，没有在单个页面入口处打补丁。
- BUG-061 至 BUG-066 已按各自共享 store、配置、协议或页面组件层修复，失败时保留上一轮成功数据并提供页面内错误反馈，开发代理端口展示不再误导。
- 后续 Web UI E2E 若发现其它路由入口遮挡或错位，应继续追加阶段 1.1 原子缺陷报告，再按最小共享层修复。
