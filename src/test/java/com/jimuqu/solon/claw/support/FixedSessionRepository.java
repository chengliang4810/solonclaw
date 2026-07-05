package com.jimuqu.solon.claw.support;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 固定返回最近会话的测试仓储，避免测试依赖 SQLite。 */
public class FixedSessionRepository implements SessionRepository {
    /** 按更新时间倒序准备的测试会话。 */
    private final List<SessionRecord> sessions;

    /** 是否模拟仓储返回 null，用于验证调用方防御性处理。 */
    private final boolean returnNullList;

    /** 创建空会话测试仓储。 */
    public FixedSessionRepository() {
        this(Collections.<SessionRecord>emptyList());
    }

    /**
     * 创建固定会话测试仓储。
     *
     * @param sessions 最近会话集合。
     */
    public FixedSessionRepository(List<SessionRecord> sessions) {
        this(sessions, false);
    }

    /**
     * 创建固定会话测试仓储，并允许模拟异常仓储返回值。
     *
     * @param sessions 最近会话集合。
     * @param returnNullList 是否让 listRecent 返回 null。
     */
    public FixedSessionRepository(List<SessionRecord> sessions, boolean returnNullList) {
        this.sessions = sessions == null ? Collections.<SessionRecord>emptyList() : sessions;
        this.returnNullList = returnNullList;
    }

    /** 固定返回空绑定会话。 */
    @Override
    public SessionRecord getBoundSession(String sourceKey) {
        return null;
    }

    /** 固定不创建新会话。 */
    @Override
    public SessionRecord bindNewSession(String sourceKey) {
        return null;
    }

    /** 固定不修改来源绑定。 */
    @Override
    public void bindSource(String sourceKey, String sessionId) {}

    /** 固定不克隆会话分支。 */
    @Override
    public SessionRecord cloneSession(String sourceKey, String sourceSessionId, String branchName) {
        return null;
    }

    /** 固定不按 ID 查询会话。 */
    @Override
    public SessionRecord findById(String sessionId) {
        return null;
    }

    /** 固定不按来源和分支查询会话。 */
    @Override
    public SessionRecord findBySourceAndBranch(String sourceKey, String branchName) {
        return null;
    }

    /** 固定不返回恢复候选。 */
    @Override
    public List<SessionRecord> findResumeCandidates(String reference, int limit) {
        return Collections.emptyList();
    }

    /** 固定不持久化会话变更。 */
    @Override
    public void save(SessionRecord sessionRecord) {}

    /** 固定不走全文检索。 */
    @Override
    public List<SessionRecord> search(String keyword, int limit) {
        return Collections.emptyList();
    }

    /** 按调用方限制返回最近会话。 */
    @Override
    public List<SessionRecord> listRecent(int limit) {
        if (returnNullList) {
            return null;
        }
        return new ArrayList<SessionRecord>(sessions.subList(0, Math.min(limit, sessions.size())));
    }

    /** 按调用方限制和偏移返回最近会话。 */
    @Override
    public List<SessionRecord> listRecent(int limit, int offset) {
        if (returnNullList) {
            return null;
        }
        int start = Math.min(Math.max(0, offset), sessions.size());
        int end = Math.min(start + Math.max(0, limit), sessions.size());
        return new ArrayList<SessionRecord>(sessions.subList(start, end));
    }

    /** 固定不返回 pending Agent 会话。 */
    @Override
    public List<SessionRecord> listPendingAgentSessions(long updatedAfterMillis, int limit) {
        return Collections.emptyList();
    }

    /** 返回测试会话总数。 */
    @Override
    public int countAll() {
        return sessions.size();
    }

    /** 固定不删除会话。 */
    @Override
    public void delete(String sessionId) {}

    /** 固定不修改模型覆盖。 */
    @Override
    public void setModelOverride(String sessionId, String modelOverride) {}

    /** 固定不修改服务层级覆盖。 */
    @Override
    public void setServiceTierOverride(String sessionId, String serviceTierOverride) {}

    /** 固定不修改推理强度覆盖。 */
    @Override
    public void setReasoningEffortOverride(String sessionId, String reasoningEffortOverride) {}

    /** 固定不切换激活 Agent。 */
    @Override
    public void setActiveAgentName(String sessionId, String agentName) {}

    /** 固定不清理激活 Agent。 */
    @Override
    public void clearActiveAgentName(String agentName) {}

    /** 固定不写入目标状态。 */
    @Override
    public void setGoalState(String sessionId, String goalStateJson) {}

    /** 固定不更新学习时间。 */
    @Override
    public void setLastLearningAt(String sessionId, long lastLearningAt) {}
}
