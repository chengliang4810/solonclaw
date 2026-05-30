package com.jimuqu.solon.claw.kanban;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outcome of one Jimuqu Kanban dispatcher pass. */
public class KanbanDispatchResult {
    private int reclaimed;
    private int promoted;
    private Integer maxSpawn;
    private Integer maxInProgress;
    private Integer maxInProgressPerProfile;
    private Integer runningBeforeDispatch;
    private final List<String> crashed = new ArrayList<String>();
    private final List<String> stale = new ArrayList<String>();
    private final List<String> timedOut = new ArrayList<String>();
    private final List<Map<String, Object>> spawned = new ArrayList<Map<String, Object>>();
    private final List<String> skippedUnassigned = new ArrayList<String>();
    private final List<String> skippedNonspawnable = new ArrayList<String>();
    private final List<String> skippedCapacity = new ArrayList<String>();
    private final List<String> autoAssignedDefault = new ArrayList<String>();
    private final List<Map<String, Object>> skippedPerProfileCapped = new ArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> respawnGuarded = new ArrayList<Map<String, Object>>();
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

    public List<String> getTimedOut() {
        return timedOut;
    }

    public List<String> getCrashed() {
        return crashed;
    }

    public List<String> getStale() {
        return stale;
    }

    public Integer getMaxInProgress() {
        return maxInProgress;
    }

    public Integer getMaxSpawn() {
        return maxSpawn;
    }

    public void setMaxSpawn(Integer maxSpawn) {
        this.maxSpawn = maxSpawn;
    }

    public void setMaxInProgress(Integer maxInProgress) {
        this.maxInProgress = maxInProgress;
    }

    public Integer getMaxInProgressPerProfile() {
        return maxInProgressPerProfile;
    }

    public void setMaxInProgressPerProfile(Integer maxInProgressPerProfile) {
        this.maxInProgressPerProfile = maxInProgressPerProfile;
    }

    public Integer getRunningBeforeDispatch() {
        return runningBeforeDispatch;
    }

    public void setRunningBeforeDispatch(Integer runningBeforeDispatch) {
        this.runningBeforeDispatch = runningBeforeDispatch;
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

    public List<String> getSkippedCapacity() {
        return skippedCapacity;
    }

    public List<String> getAutoAssignedDefault() {
        return autoAssignedDefault;
    }

    public List<Map<String, Object>> getSkippedPerProfileCapped() {
        return skippedPerProfileCapped;
    }

    public List<Map<String, Object>> getRespawnGuarded() {
        return respawnGuarded;
    }

    public List<String> getSpawnFailures() {
        return spawnFailures;
    }

    public List<String> getAutoBlocked() {
        return autoBlocked;
    }

    public void addSpawned(KanbanTaskRecord task, String workspacePath, long workerPid) {
        addSpawned(task, workspacePath, workerPid, null);
    }

    public void addSpawned(KanbanTaskRecord task, String workspacePath, long workerPid, String assigneeOverride) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("task_id", task.getTaskId());
        item.put("assignee", StrUtil.blankToDefault(assigneeOverride, task.getAssignee()));
        item.put("workspace_path", workspaceReference(task, workspacePath));
        item.put("worker_pid", workerPid <= 0 ? null : Long.valueOf(workerPid));
        spawned.add(item);
    }

    public void addSkippedPerProfileCapped(String taskId, String assignee, int currentRunning) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("task_id", taskId);
        item.put("assignee", assignee);
        item.put("current", Integer.valueOf(currentRunning));
        skippedPerProfileCapped.add(item);
    }

    public void addRespawnGuarded(String taskId, String reason) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("task_id", taskId);
        item.put("reason", reason);
        respawnGuarded.add(item);
    }

    private String workspaceReference(KanbanTaskRecord task, String workspacePath) {
        if (task != null && "scratch".equals(task.getWorkspaceKind())) {
            return "workspace://kanban/" + SecretRedactor.redact(task.getTaskId(), 200);
        }
        if (StrUtil.isBlank(workspacePath)) {
            return null;
        }
        String name = new File(workspacePath).getName();
        if (StrUtil.isBlank(name)) {
            name = "workspace";
        }
        return "path://" + SecretRedactor.redact(name, 200);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("reclaimed", Integer.valueOf(reclaimed));
        result.put("promoted", Integer.valueOf(promoted));
        result.put("timed_out", timedOut);
        result.put("crashed", crashed);
        result.put("stale", stale);
        if (maxSpawn != null) {
            result.put("max_spawn", maxSpawn);
        }
        if (maxInProgress != null) {
            result.put("max_in_progress", maxInProgress);
        }
        if (maxInProgressPerProfile != null) {
            result.put("max_in_progress_per_profile", maxInProgressPerProfile);
        }
        if (runningBeforeDispatch != null) {
            result.put("running_before_dispatch", runningBeforeDispatch);
        }
        result.put("spawned", spawned);
        result.put("skipped_unassigned", skippedUnassigned);
        result.put("skipped_nonspawnable", skippedNonspawnable);
        result.put("skipped_capacity", skippedCapacity);
        result.put("auto_assigned_default", autoAssignedDefault);
        result.put("skipped_per_profile_capped", skippedPerProfileCapped);
        result.put("respawn_guarded", respawnGuarded);
        result.put("spawn_failures", spawnFailures);
        result.put("auto_blocked", autoBlocked);
        return result;
    }
}
