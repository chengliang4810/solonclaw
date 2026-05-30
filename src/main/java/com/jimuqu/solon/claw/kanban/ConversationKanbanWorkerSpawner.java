package com.jimuqu.solon.claw.kanban;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimePolicy;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** In-process Kanban worker spawner backed by the existing agent orchestrator. */
public class ConversationKanbanWorkerSpawner implements KanbanWorkerSpawner {
    private static final Logger log = LoggerFactory.getLogger(ConversationKanbanWorkerSpawner.class);

    private final ConversationOrchestrator conversationOrchestrator;
    private final KanbanService kanbanService;

    public ConversationKanbanWorkerSpawner(
            ConversationOrchestrator conversationOrchestrator, KanbanService kanbanService) {
        this.conversationOrchestrator = conversationOrchestrator;
        this.kanbanService = kanbanService;
    }

    @Override
    public long spawn(final KanbanTaskRecord task, final String workspacePath, final String workerContext)
            throws Exception {
        if (conversationOrchestrator == null) {
            throw new IllegalStateException("Conversation orchestrator is not ready");
        }
        final String sourceKey = "MEMORY:kanban-" + task.getTaskId() + ":" + IdSupport.newId();
        final String prompt = workerPrompt(task, workspacePath, workerContext);
        Thread thread =
                ThreadUtil.newThread(
                        new Runnable() {
                            @Override
                            public void run() {
                                runWorker(task, sourceKey, prompt);
                            }
                        },
                        "kanban-worker-" + task.getTaskId());
        thread.setDaemon(true);
        thread.start();
        return thread.getId();
    }

    private void runWorker(KanbanTaskRecord task, String sourceKey, String prompt) {
        try {
            GatewayMessage message = new GatewayMessage(PlatformType.MEMORY, "kanban", task.getTaskId(), prompt);
            message.setSourceKeyOverride(sourceKey);
            message.setEnabledToolsetsOverride(Collections.singletonList("kanban"));
            if (StrUtil.isNotBlank(task.getAssignee()) && !"default".equalsIgnoreCase(task.getAssignee())) {
                message.setUserName(task.getAssignee());
            }
            GatewayReply reply = conversationOrchestrator.runScheduled(message);
            if (reply != null && reply.isError()) {
                kanbanService.status(task.getTaskId(), "blocked", reply.getContent(), reply.getContent(), null);
            }
        } catch (Exception e) {
            String error = safeError(e);
            log.warn("Kanban worker failed: taskId={}, error={}", task.getTaskId(), error);
            try {
                kanbanService.status(task.getTaskId(), "blocked", error, error, null);
            } catch (Exception ignored) {
            }
        }
    }

    private String workerPrompt(KanbanTaskRecord task, String workspacePath, String workerContext) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("你是 Kanban worker，正在执行任务 ")
                .append(task.getTaskId())
                .append("。必须围绕该任务工作。\n");
        buffer.append("执行要求：\n");
        buffer.append("- 开始后可用 kanban_show 查看任务。\n");
        buffer.append("- 长任务需要用 kanban_heartbeat(task_id=\"")
                .append(task.getTaskId())
                .append("\") 保持心跳。\n");
        buffer.append("- 进入新流程步骤时调用 kanban_step(task_id=\"")
                .append(task.getTaskId())
                .append("\", step_key=\"...\", note=\"...\") 记录推进。\n");
        buffer.append("- 完成时调用 kanban_complete(task_id=\"")
                .append(task.getTaskId())
                .append("\", summary=\"...\", result=\"...\")。\n");
        buffer.append("- 无法完成时调用 kanban_block(task_id=\"")
                .append(task.getTaskId())
                .append("\", reason=\"...\")。\n");
        List<String> skills = AgentRuntimePolicy.parseStringList(task.getSkillsJson());
        if (!skills.isEmpty()) {
            buffer.append("- 请先加载并遵循这些技能：").append(skills).append('\n');
        }
        if (StrUtil.isNotBlank(workspacePath)) {
            buffer.append("- 工作目录：").append(workspacePath).append('\n');
        }
        buffer.append("\n任务上下文：\n").append(StrUtil.nullToEmpty(workerContext));
        return buffer.toString();
    }

    private String safeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        String value = StrUtil.isBlank(message) ? error.getClass().getSimpleName() : message;
        return SecretRedactor.redact(value, 1000);
    }
}
