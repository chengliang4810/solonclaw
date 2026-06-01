# 023-health-detailed-runtime

## 标题
扩展 `/health` 详细运行时视图

## 状态
- status: selected

## 优先级 / 风险
- priority: high
- risk: low
- parallelSafe: true

## 对标能力
对标实现的运行时状态入口会同时暴露服务存活、启动时间、运行时摘要与网关状态。

## 当前缺口
当前 `/health` 只返回 `ok/service`，部署和 doctor 之外的调用方拿不到轻量运行态摘要。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/bootstrap/HealthController.java`
- `src/test/java/com/jimuqu/solon/claw/HealthControllerTest.java`

## 验证方式
`mvn -Dskip.web.build=true -Dtest=HealthControllerTest test`
