package com.jimuqu.solon.claw.kanban;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outcome of one Jimuqu Kanban dispatcher pass. */
public class KanbanDispatchResult {
    private int reclaimed;
    private int promoted;
    private int timedOut;
    private final List<Map<String, Object>> spawned = new ArrayList<Map<String, Object>>();
    private final List<String> skippedUnassigned = new ArrayList<String>();
    private final List<String> skippedNonspawnable = new ArrayList<String>();
    private final List<String> spawnFailures = new ArrayList<String>();
    private final List<String> autoBlocked = new ArrayList<String>();

    public int getReclaimed() {
        return reclaimed;
    }

    public void setReclaimed(int reclaimed) {
        this.reclaimed = reclaimed;
    }

    public int getPromoted() {
        return promoted;
    }

    public void setPromoted(int promoted) {
        this.promoted = promoted;
    }

    public int getTimedOut() {
        return timedOut;
    }

    public void setTimedOut(int timedOut) {
        this.timedOut = timedOut;
    }

    public List<Map<String, Object>> getSpawned() {
        return spawned;
    }

    public List<String> getSkippedUnassigned() {
        return skippedUnassigned;
    }

    public List<String> getSkippedNonspawnable() {
        return skippedNonspawnable;
    }

    public List<String> getSpawnFailures() {
        return spawnFailures;
    }

    public List<String> getAutoBlocked() {
        return autoBlocked;
    }

    public void addSpawned(KanbanTaskRecord task, String workspacePath, long workerPid) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("task_id", task.getTaskId());
        item.put("assignee", task.getAssignee());
        item.put("workspace_path", workspacePath);
        item.put("worker_pid", workerPid <= 0 ? null : Long.valueOf(workerPid));
        spawned.add(item);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("reclaimed", Integer.valueOf(reclaimed));
        result.put("promoted", Integer.valueOf(promoted));
        result.put("timed_out", Integer.valueOf(timedOut));
        result.put("spawned", spawned);
        result.put("skipped_unassigned", skippedUnassigned);
        result.put("skipped_nonspawnable", skippedNonspawnable);
        result.put("spawn_failures", spawnFailures);
        result.put("auto_blocked", autoBlocked);
        return result;
    }
}
