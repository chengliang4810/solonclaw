# 记忆摘要系统使用指南

## 概述

SolonClaw 现已集成 Solon AI 的 `SummarizationInterceptor`，实现了"永不失忆"的长期记忆管理功能。通过多层级摘要策略，系统可以自动压缩和保留对话历史，确保 Agent 在长对话中不会丢失重要信息。

## 核心特性

### 1. 自动摘要触发
- 当会话消息数超过阈值（默认 12 条）时，自动触发摘要
- 过期消息被压缩后保留在上下文头部
- 物理移除原始明细，减少 Token 消耗

### 2. 多层级摘要策略

#### A. 关键信息提取 (KeyInfoExtractionStrategy)
- **职责**：作为"信息审计专家"，只提取事实、参数、结论和已验证的失败尝试
- **场景**：垂直领域任务（如 SQL 生成、自动化运维），需要防止核心参数丢失
- **特点**：过滤掉冗长的思考过程，只保留"硬干货"

#### B. 层级滚动摘要 (HierarchicalSummarizationStrategy)
- **职责**：将"旧摘要"与"新消息"递归合并：(Summary_N-1 + History_New) -> Summary_N
- **场景**：超长任务流
- **特点**：支持无限续航，记忆链条永不断裂，历史背景通过摘要不断向后传递

#### C. 组合策略 (CompositeSummarizationStrategy) - 推荐
- **职责**：串联多个策略，构建多层级记忆体系
- **最佳实践顺序**：
  1. KeyInfo 提炼（保证硬核数据在看板上）
  2. Hierarchical 压缩（保证全局进度不丢失）

## 配置说明

在 `app.yml` 中配置记忆摘要功能：

```yaml
solonclaw:
  memory:
    summarization:
      enabled: true                       # 是否启用记忆摘要（永不失忆功能）
      maxMessages: 12                     # 超过 12 条消息时触发摘要压缩
      strategy: composite                 # 摘要策略：lstm, keyInfo, hierarchical, composite
      maxSummaryLength: 500               # 摘要最大长度（字符数）
      keyInfoEnabled: true                # 是否启用关键信息提取
      hierarchicalEnabled: true           # 是否启用层级滚动摘要
```

### 配置项说明

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | true | 是否启用摘要功能 |
| `maxMessages` | int | 12 | 触发摘要的消息数阈值（建议 5-50） |
| `strategy` | string | composite | 摘要策略类型 |
| `maxSummaryLength` | int | 500 | 摘要最大长度（字符数） |
| `keyInfoEnabled` | boolean | true | 是否启用关键信息提取（仅 composite 有效） |
| `hierarchicalEnabled` | boolean | true | 是否启用层级滚动摘要（仅 composite 有效） |

### 策略类型

- **lstm**：基础 LLM 语义压缩
- **keyInfo**：关键信息提取
- **hierarchical**：层级滚动摘要
- **composite**：组合策略（推荐）

## 使用示例

### 1. 启用组合策略（推荐）

```yaml
solonclaw:
  memory:
    summarization:
      enabled: true
      strategy: composite
      maxMessages: 12
      keyInfoEnabled: true
      hierarchicalEnabled: true
```

### 2. 仅使用关键信息提取

```yaml
solonclaw:
  memory:
    summarization:
      enabled: true
      strategy: keyInfo
      maxMessages: 10
```

### 3. 禁用摘要功能

```yaml
solonclaw:
  memory:
    summarization:
      enabled: false
```

## 工作原理

```
用户消息 1
用户消息 2
...
用户消息 12
用户消息 13  ← 触发摘要
              ↓
提取关键信息 (KeyInfo)
生成层级摘要 (Hierarchical)
              ↓
压缩消息 1-7 为摘要
保留消息 8-13 原文
              ↓
摘要消息
用户消息 8
...
用户消息 13
```

## 观察摘要效果

启动应用后，在日志中可以看到摘要相关信息：

```
INFO  c.j.s.m.s.SummarizationStrategyFactory - 创建组合摘要策略
INFO  c.j.s.m.s.SummarizationStrategyFactory -   - 已添加：关键信息提取策略
INFO  c.j.s.m.s.SummarizationStrategyFactory -   - 已添加：层级滚动摘要策略
INFO  c.j.s.agent.AgentService - 已启用记忆摘要系统，策略: composite，最大消息数: 12
```

## 最佳实践

### 1. 选择合适的触发阈值

- **短对话**（5-10 条消息）：`maxMessages: 8`
- **中等对话**（10-20 条消息）：`maxMessages: 12`（默认）
- **长对话**（20-50 条消息）：`maxMessages: 20`

### 2. 根据场景选择策略

- **需要保留硬核数据**：使用 `keyInfo` 或 `composite` + `keyInfoEnabled: true`
- **需要追踪全局进度**：使用 `hierarchical` 或 `composite` + `hierarchicalEnabled: true`
- **通用场景**：使用 `composite`（推荐）

### 3. 调整摘要长度

- **简洁摘要**：`maxSummaryLength: 300`
- **详细摘要**：`maxSummaryLength: 1000`

## 技术实现

### 核心组件

1. **MemorySummarizationConfig**：配置类
2. **SummarizationStrategyFactory**：策略工厂
3. **AgentService**：集成摘要拦截器

### 使用的 Solon AI 组件

- `SummarizationInterceptor`：摘要拦截器
- `LLMSummarizationStrategy`：LLM 基础压缩
- `KeyInfoExtractionStrategy`：关键信息提取
- `HierarchicalSummarizationStrategy`：层级滚动摘要
- `CompositeSummarizationStrategy`：组合策略

## 注意事项

1. **Token 成本**：摘要生成需要额外的 API 调用，会略微增加成本
2. **延迟影响**：摘要生成会增加单次对话的延迟
3. **信息丢失**：摘要会压缩原始消息，部分细节可能丢失
4. **向量存储**：当前未实现 `VectorStoreSummarizationStrategy`，原始消息不会被存入向量数据库

## 未来扩展

可以添加以下功能：

1. **向量存储归档**：使用 `VectorStoreSummarizationStrategy` 将原始消息存入向量数据库
2. **自定义提示词**：允许用户配置摘要生成的提示词
3. **多级摘要**：实现更细粒度的摘要层级
4. **摘要质量评估**：自动评估摘要的质量和准确性

## 相关文档

- [Solon AI 官方文档](https://solon.noear.org/)
- [Agent 服务文档](./agent-service.md)
- [记忆系统文档](./memory-system.md)
