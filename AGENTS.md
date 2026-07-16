# jimuqu-agent 技术规范

## 语言与框架

- 主实现语言使用 Java，并保持 Java 8 源码兼容。
- 核心框架使用 Solon；AI 接入、Agent 编排和模型协议封装优先使用 Solon AI。
- 通用工具优先使用 Hutool。
- JSON 序列化与反序列化优先使用 `org.noear:snack4`。
- 前端使用 Vue 3、TypeScript 和 Vite，沿用现有组件与状态管理方式。
- 数据持久化沿用现有 SQLite 仓储实现。

## 依赖选型

- Solon、Solon AI 已提供对应模块、dialect、plugin 或适配实现时，优先直接复用。
- 不引入 Spring Boot、Spring AI、LangChain4j、Jackson、Fastjson、Gson 等替代性主框架或序列化方案。
- 新依赖必须有明确必要性，保持边界清晰，并优先选择 Maven Central 或 npm 官方仓库中的稳定版本。
- 标准库、现有工具类或已安装依赖能够解决的问题，不新增重复实现或依赖。

## 架构约束

- 配置、模型协议、渠道、工具、Agent 编排和存储保持职责分离。
- `bootstrap/` 只负责 Solon Bean 装配，不承载业务逻辑。
- `engine/` 承载会话与 Agent 编排；`llm/` 作为模型协议边界；`tool/` 承载工具实现；`gateway/` 承载消息渠道；`storage/repository/` 承载 SQLite 实现。
- Dashboard 后端沿用 `DashboardXxxController` 与 `DashboardXxxService` 分层，统一使用现有鉴权和响应结构。
- 前端路由放在 `web/src/router/index.ts`，API 调用放在 `web/src/api/`，页面放在 `web/src/views/`。
- 不为单一实现增加无必要的接口、工厂、兼容层或预留扩展。

## 编码规范

- 修改前先检查现有调用链和项目模式，优先复用现有实现，并以最小改动解决根因。
- Java 类、字段和方法必须添加具体的中文 Javadoc 或中文注释；配置项在格式允许时添加中文说明。
- Controller、依赖注入、配置、过滤器和插件沿用现有 Solon 写法，不使用 Spring 注解或 Spring MVC 模式。
- 工具副作用必须经过现有安全、审批和审计边界，不得绕过输入校验或危险操作保护。
- 配置、代码、测试和文档只使用当前 `solonclaw` 命名，不增加历史配置键、环境变量、命令、字段或枚举值的兼容回退。
- 外部参考仓库路径只用于本地开发，不写入仓库文件、配置样例、发布产物或用户可见文本。

## 验证规范

- 修改后运行与改动最相关的测试或构建；验证失败不得提交。
- 后端快速验证使用 `mvn -Dskip.web.build=true test`，完整构建使用 `mvn package`。
- 前端验证在 `web/` 下运行 `npm run build`，必要时补充对应的现有静态测试。
- Java 格式检查使用 `mvn spotless:check`，格式修复使用 `mvn spotless:apply`。
- 提交前运行：

```bash
python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
python3 scripts/check-raw-exception-logging.py
```

## 参考源码

- Solon：`https://gitee.com/opensolon/solon.git`
- Solon AI：`https://gitee.com/opensolon/solon-ai.git`
- Hutool：`https://gitee.com/dromara/hutool.git`

框架 API、扩展点或行为不明确时，先核对当前依赖版本和对应官方源码，再决定实现方式。
