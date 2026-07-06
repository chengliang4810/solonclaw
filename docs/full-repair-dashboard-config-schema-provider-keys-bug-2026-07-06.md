# Dashboard 配置 Schema 模型键修复

## 问题

`GET /api/config/schema` 仍暴露旧的模型配置键：

- `llm.provider`
- `llm.apiUrl`
- `llm.model`

这些键已经不再是当前 provider 配置主路径。Dashboard 如果按 Schema 渲染并保存默认模型，会把用户输入写到旧结构，导致当前 provider 配置没有更新。

## 根因

`DashboardConfigService.registerFields()` 仍注册旧的 provider 身份、地址和默认模型字段。运行态配置白名单、加载器和工作区配置接口已经使用当前路径：

- `model.providerKey`
- `providers.default.name`
- `providers.default.baseUrl`
- `providers.default.defaultModel`
- `providers.default.dialect`

## 修复

将 Dashboard 配置 Schema 中的旧 provider 字段替换为当前 provider/model 字段。保留仍有效的 `llm.stream`、`llm.reasoningEffort`、`llm.temperature` 等运行行为字段。

## 验证

已补充 `DashboardControllerHttpTest#shouldPersistConfigAndExposeDashboardResources` 覆盖：

- `PUT /api/config` 可保存 `providers.default.defaultModel`
- `GET /api/config/schema` 暴露当前 provider/model 字段
- Schema 不再暴露 `llm.provider`、`llm.apiUrl`、`llm.model`

验证命令：

```bash
mvn "-Dskip.web.build=true" "-Dtest=DashboardControllerHttpTest#shouldPersistConfigAndExposeDashboardResources" test
```
