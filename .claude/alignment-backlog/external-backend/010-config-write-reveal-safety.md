# 010-config-write-reveal-safety

## 标题
对齐 外部对标配置写入与 reveal 安全

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false

## 外部对标参考
- `对标实现路径：cli/config.py`
- `对标实现路径：tests/cli/test_config.py`
- `对标实现路径：tests/cli/test_set_config_value.py`
- `对标实现路径：tests/cli/test_config_validation.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/web/DashboardConfigController.java`
- `src/main/java/com/jimuqu/solon/claw/web/DashboardRuntimeConfigController.java`
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/ConfigTools.java`
- `src/test/java/com/jimuqu/solon/claw/ConfigToolsTest.java`

## 当前缺口
外部对标将 config mutation、secret redaction、reveal 作为不同安全路径处理；当前后端已有 config/runtime-config endpoint，但 write/reveal split 仍需对齐 redaction 与 guarded mutation 行为。

## 实现范围
收紧 backend config write/reveal flow：非 secret 写入、secret 更新、reveal rate limiting 分层映射到 外部对标仓库 模式，并补充 safe writes 与 redacted reads 测试。

## 验证方式
`mvn -Dskip.web.build=true -Dtest=ConfigToolsTest,RuntimeConfigResolverTest test`
