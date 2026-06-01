# 018-env-probe-diagnostics

## 标题
增加 subprocess policy 的 env probe 诊断

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

## 外部对标参考
- `对标实现路径：tools/env_probe.py`
- `对标实现路径：cli/env_loader.py`
- `对标实现路径：tests/tools/test_env_probe.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/SubprocessEnvironmentSanitizer.java`
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/ConfigTools.java`
- `src/test/java/com/jimuqu/solon/claw/tool/runtime/SubprocessEnvironmentSanitizerTest.java`

## 当前缺口
外部对标仓库 提供 env-probe flow 与 env-loader 行为解释哪些变量会被 passed through、blocked 或 forced；当前后端已做 subprocess env sanitization，但缺少同等 probe-style diagnostic surface。

## 实现范围
暴露小型后端诊断或 tool endpoint，报告 sanitizer policy 与 per-variable probe decisions，并用 focused unit test 覆盖 allow/block/force cases。

## 验证方式
`mvn -Dskip.web.build=true -Dtest=SubprocessEnvironmentSanitizerTest test`
