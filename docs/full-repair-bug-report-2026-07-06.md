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

## 当前结论

- BUG-060 已按侧栏共享组件层修复，没有在单个页面入口处打补丁。
- 后续 Web UI E2E 若发现其它路由入口遮挡或错位，应继续追加阶段 1.1 原子缺陷报告，再按最小共享层修复。
