# model-capability-surface-gap

## 标题
补齐剩余模型能力标记和支持面

## 状态
- status: done
- completedCommit: 342e0b73
- testResult: `mvn -Dskip.web.build=true "-Dtest=SkillBundlePathSupportTest,SkillImportServiceTest,ModelMetadataServiceTest,RuntimeRefreshBehaviorTest,DashboardStatusServiceTest,DashboardDiagnosticOutputTest,McpRuntimeServiceTest" test` 通过（141 tests, 0 failures, 0 errors）

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false
- overlapHint: `011-model-metadata-capability-flags`

## 对标能力
来源领域：backend-alignment

## 当前缺口
Hermes exposes a richer capability surface than the current project: not just vision/audio/attachment, but also PDF, structured output, open-weights, interleaved behavior, and broader modality metadata. The current project already covers modalities/source, so the remaining gap is the finer-grained capability matrix and its support semantics for model selection and gating.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/agent/models_dev.py:46-123`
- `/Users/chengliang/code-repositories/hermes-agent/agent/model_metadata.py:450-486`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/core/model/ModelMetadata.java:13-36`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/support/ModelMetadataService.java:15-57,192-340`

## 验证方式
Add/update metadata unit tests to assert representative models map to PDF, structured_output, open_weights, interleaved, and other fine-grained capability fields without regressing existing vision/audio/attachment behavior.
