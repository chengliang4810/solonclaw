# 027-context-estimator-image-cost

## 标题
为图片输入补粗略 token 成本估算

## 状态
- status: selected

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

## 对标能力
对标实现会在上下文预算阶段为图片输入增加保守 token 成本估算，避免多模态上下文被低估。

## 当前缺口
当前 `ContextTokenEstimator` 只对文本和内联 data URI 做字符级估算，普通图片引用或附件占位不会显式增加预算成本。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/engine/ContextTokenEstimator.java`
- `src/test/java/com/jimuqu/solon/claw/engine/ContextTokenEstimatorTest.java`

## 验证方式
`mvn -Dskip.web.build=true -Dtest=ContextTokenEstimatorTest test`
