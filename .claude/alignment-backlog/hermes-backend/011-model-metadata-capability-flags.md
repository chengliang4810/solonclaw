# 011-model-metadata-capability-flags

## 标题
扩展模型元数据能力标记

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false

## Hermes 参考
- `/Users/chengliang/code-repositories/hermes-agent/agent/model_metadata.py`
- `/Users/chengliang/code-repositories/hermes-agent/website/docs/reference/model-catalog.md`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/core/model/ModelMetadata.java`
- `src/main/java/com/jimuqu/solon/claw/support/ModelMetadataService.java`

## 当前缺口
当前模型元数据以配置和模型名的静态能力推断为主，能力位集中在 tools/vision/reasoning/prompt cache/streaming；相比 Hermes，仍缺少更细的多模态、音频、附件相关能力位与来源标记。

## 实现范围
在不破坏现有字段的前提下扩展模型元数据结构，增加 audio、attachment、multimodal 等能力标记与必要 source 信息；解析层沿用既有默认推断并暴露后续覆盖扩展点。

## 验证方式
`mvn -Dskip.web.build=true -Dtest=ModelMetadataServiceTest test`
