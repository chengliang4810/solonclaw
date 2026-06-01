# skill-progressive-preprocessing

## 标题
补齐技能渐进加载前置预处理

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

## 分类
- category: hermes-backend-alignment

## 现有补充草案
- 已有同主题草案：`../external-backend/014-skill-progressive-preprocessing.md`

## Hermes 参考
- Hermes memory layer enforces a single external provider, keeps builtin memory separate, and isolates recall/sync boundaries through a dedicated manager/provider contract.
- Hermes context handling fences recalled memory blocks, strips injected context from streaming output, and treats visible response text as distinct from recalled memory context.
- Hermes skill loading adds preprocessing, directory trust constraints, and a lightweight bundle reload model based on disk mtimes plus duplicate-slug resolution.
- Hermes skill security is source-aware, with stronger trust/severity distinctions for builtin, trusted, community, and agent-created skills before install or activation.

## 当前项目观察
- Current project already has a dedicated memory manager/provider split, skill hub/import services, and existing backlog items for memory isolation, skill trust, preprocessing, and bundle reload.
- The repo also already tracks context compression, session recovery, and skill hub behaviors, so the best backlog candidates are backend policy/guardrail deltas rather than UI work.
- Relevant local backlog files already exist under /Users/chengliang/code-projects/jimuqu-agent/.claude/alignment-backlog/external-backend, making these candidates low-friction alignment follow-ups.

## 当前缺口
Hermes preprocesses skill markdown before load, including template-variable substitution and optional inline shell execution under config control; the current loader can add a comparable pre-load stage while keeping root-path resolution strict.

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/context/LocalSkillService.java`
- `src/main/java/com/jimuqu/solon/claw/context/ExternalSkillDirectoryService.java`
- `src/main/java/com/jimuqu/solon/claw/context/SkillDirectoryResolver.java`

## 验证方式
Load skills through the backend path and verify preprocessing occurs only when enabled and still respects trusted-root boundaries.

