package com.jimuqu.solon.claw.kanban;

/** Starts execution for a claimed Kanban task. */
public interface KanbanWorkerSpawner {
    long spawn(KanbanTaskRecord task, String workspacePath, String workerContext) throws Exception;
}
