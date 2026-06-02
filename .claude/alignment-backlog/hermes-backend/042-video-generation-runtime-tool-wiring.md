# 042-video-generation-runtime-tool-wiring

## 标题
补齐视频生成运行时/工具接线

## 状态
- status: deferred

## 优先级 / 风险
- priority: medium
- risk: low-medium
- parallelSafe: true
- overlapHint: `042-video-generation-runtime-tool-wiring`

## 对标能力
来源领域：media-provider-backend-alignment

## 当前缺口
Hermes 已有视频生成 provider registry、dispatch 与 picker 测试面；当前项目已有 `VideoGenProvider` 抽象和 provider 插件入口，但缺少类似 image/speech 的视频生成运行时 service、工具接线、生成结果落盘/引用、基础错误和 usage 回传。

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/agent/video_gen_provider.py`
- `/Users/chengliang/code-repositories/hermes-agent/agent/video_gen_registry.py`
- `/Users/chengliang/code-repositories/hermes-agent/tests/tools/test_video_generation_dispatch.py`
- `/Users/chengliang/code-repositories/hermes-agent/tests/tools/test_video_generation_dynamic_schema.py`
- `/Users/chengliang/code-repositories/hermes-agent/tests/hermes_cli/test_video_gen_picker.py`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/plugin/provider/VideoGenProvider.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/tool/runtime/MediaSpeechTools.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/media/`

## 验证方式
补充视频生成 service/tool 单测：验证 provider 选择、参数传递、失败返回、生成结果引用和 usage metadata 不破坏既有 image/speech 工具行为。

## Deferred 原因
本轮作为轻量复扫新增候选记录；不纳入当前 029/037/040/model-capability 批次，下一轮处理。
