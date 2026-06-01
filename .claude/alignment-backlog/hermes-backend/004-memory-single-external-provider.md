# 004-memory-single-external-provider

## 标题
对齐记忆管理器的单外部 provider 约束

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false

## Hermes 参考
- `/Users/chengliang/code-repositories/hermes-agent/agent/memory_manager.py`
- `/Users/chengliang/code-repositories/hermes-agent/agent/memory_provider.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/context/DefaultMemoryManager.java`
- `src/main/java/com/jimuqu/solon/claw/context/BuiltinMemoryProvider.java`
- `src/main/java/com/jimuqu/solon/claw/context/FileMemoryService.java`

## 当前缺口
当前记忆链路会串联所有 provider；Hermes 只允许一个外部 memory provider，并把内建记忆、prefetch、sync 责任分层，避免 provider 冲突以及工具/上下文膨胀。

## 实现范围
限制外部记忆提供方数量，保留 builtin-first 顺序，明确拒绝第二个外部 provider，并收紧 prefetch/sync 边界与提示拼接路径。

## 验证方式
`mvn -Dskip.web.build=true -Dtest=MemoryAndSkillsTest test`
