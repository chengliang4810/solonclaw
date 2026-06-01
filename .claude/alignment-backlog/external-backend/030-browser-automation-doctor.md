# 030-browser-automation-doctor

## 标题
增加浏览器自动化 doctor 依赖探测

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: medium
- parallelSafe: true

## 对标能力
对标实现会在 doctor 中检查浏览器自动化依赖是否可用，并给出缺失时的可操作说明。

## 当前缺口
当前已有浏览器运行时服务和工具，但诊断侧缺少依赖探测输出，工具不可用时排障入口不够明确。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`
- `src/main/java/com/jimuqu/solon/claw/browser/BrowserRuntimeService.java`

## 验证方式
模拟 provider 可用、命令缺失和配置禁用，断言 doctor 返回可用性、缺失项与 next step。
