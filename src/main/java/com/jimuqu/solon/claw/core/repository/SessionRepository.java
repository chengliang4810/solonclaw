package com.jimuqu.solon.claw.core.repository;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 会话仓储接口。 */
public interface SessionRepository {
    /** 查询来源键当前绑定的会话。 */
    SessionRecord getBoundSession(String sourceKey) throws Exception;

    /** 为来源键创建并绑定新会话。 */
    SessionRecord bindNewSession(String sourceKey) throws Exception;

    /** 重新绑定来源键到指定会话。 */
    void bindSource(String sourceKey, String sessionId) throws Exception;

    /** 克隆会话并生成新分支。 */
    SessionRecord cloneSession(String sourceKey, String sourceSessionId, String branchName)
            throws Exception;

    /** 通过会话 ID 查询会话。 */
    SessionRecord findById(String sessionId) throws Exception;

    /** 通过来源键和分支名查询会话。 */
    SessionRecord findBySourceAndBranch(String sourceKey, String branchName) throws Exception;

    /** 按 Jimuqu /resume 引用查询候选会话：唯一 ID 前缀或精确标题。 */
    List<SessionRecord> findResumeCandidates(String reference, int limit) throws Exception;

    /** 保存会话。 */
    void save(SessionRecord sessionRecord) throws Exception;

    /** 全文检索会话。 */
    List<SessionRecord> search(String keyword, int limit) throws Exception;

    /** 按更新时间列出最近会话。 */
    List<SessionRecord> listRecent(int limit) throws Exception;

    /** 按更新时间分页列出最近会话。 */
    List<SessionRecord> listRecent(int limit, int offset) throws Exception;

    /** 列出最近仍处于 Agent pending 状态的会话，用于启动恢复。 */
    List<SessionRecord> listPendingAgentSessions(long updatedAfterMillis, int limit)
            throws Exception;

    /** 返回会话总数。 */
    int countAll() throws Exception;

    /** 返回指定会话所在 lineage 的所有节点（祖先、当前节点、后代）。 */
    default List<SessionRecord> listLineage(String sessionId) throws Exception {
        SessionRecord root = findById(resolveRootSessionId(sessionId));
        if (root == null) {
            return Collections.emptyList();
        }
        int total = Math.max(countAll(), 1);
        List<SessionRecord> records = listRecent(Math.min(Math.max(total, 200), 5000), 0);
        Map<String, SessionRecord> byId = new LinkedHashMap<String, SessionRecord>();
        for (SessionRecord record : records) {
            if (record != null && record.getSessionId() != null) {
                byId.put(record.getSessionId(), record);
            }
        }
        if (!byId.containsKey(root.getSessionId())) {
            byId.put(root.getSessionId(), root);
        }

        LinkedHashSet<String> selected = new LinkedHashSet<String>();
        String cursor = root.getSessionId();
        while (isNotBlank(cursor) && selected.add(cursor)) {
            SessionRecord current = byId.get(cursor);
            if (current == null) {
                break;
            }
            cursor = current.getParentSessionId();
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (SessionRecord record : byId.values()) {
                String parent = record.getParentSessionId();
                if (isNotBlank(parent)
                        && selected.contains(parent)
                        && selected.add(record.getSessionId())) {
                    changed = true;
                }
            }
        }

        List<SessionRecord> result = new ArrayList<SessionRecord>();
        for (SessionRecord record : byId.values()) {
            if (selected.contains(record.getSessionId())) {
                result.add(record);
            }
        }
        return result;
    }

    /** 返回指定会话沿最新子分支向下追踪到的最后一个节点。 */
    default List<String> latestDescendantPath(String sessionId) throws Exception {
        SessionRecord root = findById(sessionId);
        if (root == null) {
            return Collections.emptyList();
        }
        List<SessionRecord> records = listLineage(root.getSessionId());
        Map<String, List<SessionRecord>> childrenByParent =
                new LinkedHashMap<String, List<SessionRecord>>();
        for (SessionRecord record : records) {
            String parent = record.getParentSessionId();
            if (!isNotBlank(parent)) {
                continue;
            }
            List<SessionRecord> children = childrenByParent.get(parent);
            if (children == null) {
                children = new ArrayList<SessionRecord>();
                childrenByParent.put(parent, children);
            }
            children.add(record);
        }

        List<String> path = new ArrayList<String>();
        String current = root.getSessionId();
        path.add(current);
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        seen.add(current);

        while (childrenByParent.containsKey(current)) {
            List<SessionRecord> children = childrenByParent.get(current);
            SessionRecord newest = null;
            for (SessionRecord candidate : children) {
                if (candidate == null || seen.contains(candidate.getSessionId())) {
                    continue;
                }
                if (newest == null || candidate.getCreatedAt() > newest.getCreatedAt()) {
                    newest = candidate;
                }
            }
            if (newest == null) {
                break;
            }
            current = newest.getSessionId();
            path.add(current);
            seen.add(current);
        }
        return path;
    }

    /** 解析 lineage 根会话 ID。 */
    default String resolveRootSessionId(String sessionId) throws Exception {
        SessionRecord root = resolveRootSession(sessionId);
        return root == null ? sessionId : root.getSessionId();
    }

    /** 解析 lineage 根会话。 */
    default SessionRecord resolveRootSession(String sessionId) throws Exception {
        SessionRecord current = findById(sessionId);
        if (current == null || !isNotBlank(current.getSessionId())) {
            return null;
        }
        String currentId = current.getSessionId();
        LinkedHashSet<String> visited = new LinkedHashSet<String>();
        while (isNotBlank(current.getParentSessionId()) && visited.add(currentId)) {
            SessionRecord parent = findById(current.getParentSessionId());
            if (parent == null || !isNotBlank(parent.getSessionId())) {
                break;
            }
            current = parent;
            currentId = current.getSessionId();
        }
        return current;
    }

    /** 删除指定会话。 */
    void delete(String sessionId) throws Exception;

    /** 更新会话模型覆盖配置。 */
    void setModelOverride(String sessionId, String modelOverride) throws Exception;

    /** 更新会话服务层级覆盖配置。 */
    void setServiceTierOverride(String sessionId, String serviceTierOverride) throws Exception;

    /** 更新会话推理强度覆盖配置。 */
    void setReasoningEffortOverride(String sessionId, String reasoningEffortOverride)
            throws Exception;

    /** 更新当前会话激活 Agent。 */
    void setActiveAgentName(String sessionId, String agentName) throws Exception;

    /** 清除所有使用指定 Agent 的会话激活状态。 */
    void clearActiveAgentName(String agentName) throws Exception;

    /** 更新 Jimuqu /goal 长目标循环状态。 */
    void setGoalState(String sessionId, String goalStateJson) throws Exception;

    /** 更新最近一次学习闭环执行时间，不覆盖会话正文或运行态字段。 */
    void setLastLearningAt(String sessionId, long lastLearningAt) throws Exception;

    default boolean isNotBlank(String value) {
        return value != null && value.trim().length() > 0;
    }
}
