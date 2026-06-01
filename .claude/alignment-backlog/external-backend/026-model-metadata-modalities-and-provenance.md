# 026-model-metadata-modalities-and-provenance

## 标题
扩展模型元数据模态与来源字段

## 状态
- status: selected

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

## 对标能力
对标实现的模型元数据会暴露输入/输出模态、支持状态与元数据来源，供模型选择、降级和多模态路由使用。

## 当前缺口
当前 `ModelMetadata` 已有 vision/audio/attachment 等布尔能力位，但缺少稳定模态列表与 provenance 说明。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/core/model/ModelMetadata.java`
- `src/main/java/com/jimuqu/solon/claw/support/ModelMetadataService.java`
- `src/test/java/com/jimuqu/solon/claw/ModelMetadataServiceTest.java`

## 验证方式
`mvn -Dskip.web.build=true -Dtest=ModelMetadataServiceTest test`
