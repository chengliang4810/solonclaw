# 012-usage-pricing-canonical-buckets

## 标题
归一化 usage 与 pricing token buckets

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true

## 外部对标参考
- `对标实现路径：agent/usage_pricing.py`
- `对标实现路径：agent/model_metadata.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/llm/LlmUsage.java`
- `src/main/java/com/jimuqu/solon/claw/pricing/ModelPrice.java`
- `src/main/java/com/jimuqu/solon/claw/pricing/UsageCostCalculator.java`

## 当前缺口
当前 usage/cost 主要按本地 token 桶乘单价，没有 外部对标的 canonical usage 归一化、request_count、pricing source/fetched_at，也未统一不同 provider 的 usage shape。

## 实现范围
增加统一 usage/pricing 归一层，保留 cache read/write/reasoning 等桶并补齐请求维度与价格来源信息，让计费逻辑兼容 Anthropic、OpenAI、OpenRouter、自定义端点等 usage shape。

## 验证方式
`mvn test -Dtest=UsagePricingTest,SolonAiLlmGatewayUsageTest,SessionUsageTrackingTest`
