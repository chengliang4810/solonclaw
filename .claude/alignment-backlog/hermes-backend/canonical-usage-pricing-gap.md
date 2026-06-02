# canonical-usage-pricing-gap

## 标题
归一 usage/pricing 计费桶与来源元数据

## 状态
- status: selected

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true
- overlapHint: `012-usage-pricing-canonical-buckets`

## 对标能力
来源领域：backend-alignment

## 当前缺口
Hermes normalizes usage into canonical buckets and carries billing metadata such as request_count, raw_usage, pricing source, source URL, pricing version, and fetched_at. Current code only tracks local token buckets plus a coarse source string, so it cannot represent provider-specific usage shapes or pricing provenance with the same fidelity.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/agent/usage_pricing.py:30-77,577-607,706-850`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/llm/LlmUsage.java:1-153`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/pricing/ModelPrice.java:1-47`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/pricing/UsageCostCalculator.java:1-86`

## 验证方式
Add tests covering Anthropic/OpenAI/custom-endpoint usage shapes, request_count > 1, and pricing lookup outputs that preserve source/version/fetched_at-like provenance fields.
