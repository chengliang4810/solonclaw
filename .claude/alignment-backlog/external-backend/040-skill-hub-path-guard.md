# 040-skill-hub-path-guard

## 标题
Hub 安装/卸载路径防逃逸

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: medium
- parallelSafe: true

## 对标能力
对标实现强化 install path 校验、symlink 防逃逸、只读目录删除重试和 reset 事务顺序。

## 当前缺口
当前 Hub 卸载和覆盖安装依赖 lock 中的路径与目录删除，需要进一步防止路径逃逸和删除失败后状态漂移。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/context/SkillBundlePathSupport.java`
- `src/main/java/com/jimuqu/solon/claw/context/DefaultSkillHubService.java`
- `src/main/java/com/jimuqu/solon/claw/context/DefaultSkillImportService.java`

## 验证方式
lock path 为空、`.`、`../x` 或末段不匹配时拒绝；删除失败不改 lock/manifest。
