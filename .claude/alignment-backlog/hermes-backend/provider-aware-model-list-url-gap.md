# provider-aware-model-list-url-gap

## 标题
provider-aware model list URL resolution

## 状态
- status: deferred

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true
- overlapHint: `021-provider-model-list-url-resolution`

## 对标能力
来源领域：backend-alignment

## 当前缺口
Hermes resolves model-list URLs with provider inference, local-server detection, and endpoint-specific fallbacks such as /models or /tags for different base URLs. The current helper is dialect-driven and mostly string-concatenation based, which is adequate for the happy path but weaker for custom endpoints and provider-aware discovery.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/agent/model_metadata.py:346-417`
- `/Users/chengliang/code-repositories/hermes-agent/agent/model_metadata.py:653-779`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/llm/LlmProviderSupport.java:126-180`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/support/ModelMetadataService.java:285-297`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/web/DashboardProviderService.java:345-383`

## 验证方式
Add unit tests for direct OpenAI, Anthropic, Ollama, LM Studio, and custom base URLs to verify the resolved model-list URL matches the provider-aware endpoint rather than a generic concatenation.
