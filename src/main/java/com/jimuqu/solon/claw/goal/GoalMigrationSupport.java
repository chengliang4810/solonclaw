// src/main/java/com/jimuqu/solon/claw/goal/GoalMigrationSupport.java
package com.jimuqu.solon.claw.goal;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import lombok.RequiredArgsConstructor;

/**
 * 上下文压缩轮转 session_id 时，把 active 目标迁移到子会话并把父会话归档为 cleared，
 * 保证一次只有一个活跃目标（对标仓库 migrate_goal_to_session）。
 */
@RequiredArgsConstructor
public class GoalMigrationSupport {
    /** 会话仓储，用于读写 goal_state_json。 */
    private final SessionRepository sessionRepository;

    /**
     * 迁移目标：子会话继承 active 目标，父会话归档为 cleared。
     *
     * @param oldSessionId 父会话 id。
     * @param newSessionId 子会话 id。
     * @param reason 迁移原因（写入父 pausedReason）。
     * @return 实际迁移返回 true；父无 active 目标或会话不存在返回 false。
     */
    public boolean migrate(String oldSessionId, String newSessionId, String reason) throws Exception {
        SessionRecord oldSession = sessionRepository.findById(oldSessionId);
        if (oldSession == null) {
            return false;
        }
        GoalState oldState = GoalState.fromJson(oldSession.getGoalStateJson());
        if (oldState == null || !GoalState.STATUS_ACTIVE.equals(oldState.getStatus())) {
            return false;
        }
        SessionRecord newSession = sessionRepository.findById(newSessionId);
        if (newSession == null) {
            return false;
        }
        // 子继承：深拷贝（重新序列化保证隔离），保持 active
        String childJson = oldState.toJson();
        newSession.setGoalStateJson(childJson);
        sessionRepository.setGoalState(newSessionId, childJson);
        // 父归档：置 cleared
        oldState.setStatus(GoalState.STATUS_CLEARED);
        oldState.setPausedReason("migrated to " + newSessionId + " (" + reason + ")");
        String parentJson = oldState.toJson();
        oldSession.setGoalStateJson(parentJson);
        sessionRepository.setGoalState(oldSessionId, parentJson);
        return true;
    }
}
