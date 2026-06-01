# 031-doctor-issue-summary

## 标题
汇总 doctor issues 与 next actions

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

## 对标能力
对标实现会把分区检查后的问题汇总为 issues、warnings 和 next actions，避免用户在多个诊断区之间遗漏根因。

## 当前缺口
当前 doctor 输出更像状态列表，缺少统一的最高 severity、问题计数和建议动作排序。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/web/DashboardGatewayDoctorService.java`

## 验证方式
构造模型缺 key、渠道缺配置和最近 shutdown 异常，断言 summary count 与 next actions 顺序稳定。
