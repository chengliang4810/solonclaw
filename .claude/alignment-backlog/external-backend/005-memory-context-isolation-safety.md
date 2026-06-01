# 005-memory-context-isolation-safety

## 标题
增强记忆上下文隔离与安全边界

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true

## 外部对标参考
- `对标实现路径：agent/memory_manager.py`
- `对标实现路径：agent/file_safety.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/context/FileMemoryService.java`
- `src/main/java/com/jimuqu/solon/claw/context/BuiltinMemoryProvider.java`
- `src/main/java/com/jimuqu/solon/claw/context/DefaultMemoryManager.java`

## 当前缺口
外部对标对 memory 输出做显式 fenced context 处理，并隔离可见流与召回上下文；当前实现会把今日记忆和长期记忆直接拼进 prompt，缺少同等级的上下文防泄漏边界。

## 实现范围
给 memory 召回内容增加统一 fence/strip 处理，避免记忆块混入普通回答流；同时收紧写入长期记忆的拒绝条件和敏感路径读取防线。

## 验证方式
`mvn -Dskip.web.build=true -Dtest=MemoryAndSkillsTest,RuntimeMemoryMonitorServiceTest test`
