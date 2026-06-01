# memory-single-external-provider

## 标题
对齐记忆管理器的单外部 provider 约束

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false

## 分类
- category: hermes-backend-alignment

## 现有补充草案
- 已有同主题草案：`../external-backend/004-memory-single-external-provider.md`

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
Hermes only activates one external memory provider at a time and keeps builtin memory/prefetch/sync responsibilities separated; the current backend should tighten provider arbitration so multiple external backends cannot conflict or bloat prompt/tool surfaces.

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/context/DefaultMemoryManager.java`
- `src/main/java/com/jimuqu/solon/claw/context/BuiltinMemoryProvider.java`
- `src/main/java/com/jimuqu/solon/claw/context/FileMemoryService.java`

## 验证方式
Run the memory/skills test slice and verify only one external provider can register while builtin recall/sync still works.

