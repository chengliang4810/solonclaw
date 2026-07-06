# README 首次 Dashboard 登录流程缺失

## 范围

- Dashboard-first setup / doctor：本地首次启动后，用户应能从 README 直接知道如何进入 Dashboard。
- `java -jar` 与 Docker 之外的本地开发运行路径。

## 现象

`README.md` 与 `README_EN.md` 的运行章节只展示 `java -jar target/solonclaw-0.0.1.jar` 和默认地址 `http://127.0.0.1:8080`。配置示例中 `solonclaw.dashboard.accessToken` 为空，但运行章节没有说明：

- 首次打开 Dashboard 时可以输入新访问令牌完成本机初始化。
- 首次令牌会写入 `workspace/config.yml`。
- 也可以通过 `-Dsolonclaw.dashboard.accessToken=...` 在启动前固定令牌。

## 根因

后端与前端已经实现本机限定的首次令牌设置路径，历史修复只更新了登录页和接口测试，没有同步更新用户最先阅读的中英文 README 运行章节。

## 修复

- `README.md`：在运行章节补充首次 Dashboard token 初始化和启动参数示例。
- `README_EN.md`：同步补充英文说明。
- `web/tests/readmeDashboardBootstrapStatic.test.ts`：锁定 README 必须包含启动配置键和持久化位置。

## 验证

```bash
node --experimental-strip-types web/tests/readmeDashboardBootstrapStatic.test.ts
```
