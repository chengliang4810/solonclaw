# Web 网关页前端端口误显示为网关端口

## 问题

Web UI 在 Vite 开发模式下打开网关页时，会把浏览器当前端口显示为消息网关端口。例如后端 API 运行在 `8080`、前端 Vite 运行在 `5175` 时，页面显示 `websocket:5175` / `stream:5175`。

## 根因

`web/src/api/solonclaw/gateways.ts` 在 `/api/status` 没有返回网关端口时，通过 `__SOLONCLAW_DEV_SERVER_URL__` 和 `window.location.port` 推断端口。该端口只代表当前前端页面或 Vite dev server，不代表后端消息网关。

## 修复

- 前端不再从 Vite 或浏览器地址推断网关端口。
- 仅当后端状态字段返回真实 `port` 时才显示 `host:port`。
- 后端未返回端口时，网关页只显示连接模式，避免展示错误地址。

## 验证

- `node --experimental-strip-types tests/gatewayReadOnlyStatic.test.ts`
- `npm run test:backend-ui-coverage`
