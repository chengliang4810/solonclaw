# 007-cron-prompt-ingestion-validation

## 标题
强化 Cron prompt 写入校验

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true

## 外部对标参考
- `对标实现路径：tools/cronjob_tools.py`

## 当前项目目标文件
- `src/main/java/com/jimuqu/solon/claw/scheduler/CronJobService.java`

## 当前缺口
当前 create/update validation 会扫描用户 prompt，但对 invisible Unicode 与 exfiltration-style payload 的创建期硬化还未完整对齐 外部对标 cron tool chain。

## 实现范围
在 service layer 收紧 cron prompt ingestion validation，在持久化前阻断危险 directive、invisible Unicode、prompt-injection 与 secret-exfil patterns。

## 验证方式
`mvn -Dskip.web.build=true -Dtest=DefaultCronSchedulerTest test`
