package com.jimuqu.solon.claw.kanban;

import java.util.List;
import java.util.Map;

/** Repository for the shared Jimuqu Kanban board. */
public interface KanbanRepository {
    List<KanbanBoardRecord> listBoards() throws Exception;

    List<KanbanBoardRecord> listBoards(boolean includeArchived) throws Exception;

    KanbanBoardRecord findBoard(String slug) throws Exception;

    KanbanBoardRecord currentBoard() throws Exception;

    KanbanBoardRecord saveBoard(KanbanBoardRecord board) throws Exception;

    void switchBoard(String slug) throws Exception;

    boolean renameBoard(String slug, String name) throws Exception;

    boolean archiveBoard(String slug) throws Exception;

    boolean deleteBoard(String slug) throws Exception;

    List<KanbanTaskRecord> listTasks(String boardSlug, String status, boolean includeArchived)
            throws Exception;

    List<KanbanTaskRecord> listTasks(
            String boardSlug, String status, boolean includeArchived, String sessionId)
            throws Exception;

    List<KanbanTaskRecord> listReadyTasks(String boardSlug) throws Exception;

    Map<String, Map<String, Integer>> countTasksByAssignee(String boardSlug) throws Exception;

    KanbanTaskRecord findTask(String taskId) throws Exception;

    KanbanTaskRecord findTaskByIdempotencyKey(String boardSlug, String idempotencyKey)
            throws Exception;

    KanbanTaskRecord saveTask(KanbanTaskRecord task) throws Exception;

    void linkTasks(String parentId, String childId) throws Exception;

    boolean unlinkTasks(String parentId, String childId) throws Exception;

    List<KanbanTaskRecord> listParents(String taskId) throws Exception;

    List<KanbanTaskRecord> listChildren(String taskId) throws Exception;

    boolean updateTaskStatus(String taskId, String status, String result) throws Exception;

    boolean unblockTask(String taskId) throws Exception;

    boolean scheduleTask(String taskId, String reason) throws Exception;

    boolean reclaimTask(String taskId, String reason) throws Exception;

    boolean assignTask(String taskId, String assignee) throws Exception;

    boolean assignTask(String taskId, String assignee, String source) throws Exception;

    boolean setWorkspacePath(String taskId, String workspacePath) throws Exception;

    boolean reassignTask(String taskId, String assignee, boolean reclaimFirst, String reason)
            throws Exception;

    boolean retryTask(String taskId, String reason) throws Exception;

    KanbanTaskRecord claimTask(
            String taskId, String claimer, long ttlSeconds, String workerId, long workerPid)
            throws Exception;

    KanbanTaskRecord claimReviewTask(
            String taskId, String claimer, long ttlSeconds, String workerId, long workerPid)
            throws Exception;

    KanbanTaskRecord claimNextReady(
            String boardSlug, String assignee, String claimer, long ttlSeconds, String workerId, long workerPid)
            throws Exception;

    boolean heartbeatClaim(String taskId, String claimer, long ttlSeconds) throws Exception;

    int releaseStaleClaims(long now) throws Exception;

    List<String> reclaimStaleRunning(long now, long staleTimeoutSeconds) throws Exception;

    List<String> reclaimCrashedWorkers(long now) throws Exception;

    boolean heartbeatWorker(String taskId, String note) throws Exception;

    boolean markSpawnFailure(String taskId, String error) throws Exception;

    boolean clearSpawnFailures(String taskId, long workerPid) throws Exception;

    boolean autoBlockAfterSpawnFailure(String taskId, int failureLimit, String reason)
            throws Exception;

    int recomputeReady(String boardSlug) throws Exception;

    List<String> reclaimTimedOutWorkers(long now) throws Exception;

    void deleteTask(String taskId) throws Exception;

    KanbanCommentRecord addComment(KanbanCommentRecord comment) throws Exception;

    List<KanbanCommentRecord> listComments(String taskId) throws Exception;

    KanbanEventRecord addEvent(KanbanEventRecord event) throws Exception;

    List<KanbanEventRecord> listEvents(String taskId) throws Exception;

    List<KanbanEventRecord> listRecentEvents(int limit) throws Exception;

    int deleteEventsOlderThan(long cutoffMillis) throws Exception;

    KanbanNotifySubscriptionRecord saveNotifySubscription(KanbanNotifySubscriptionRecord subscription)
            throws Exception;

    List<KanbanNotifySubscriptionRecord> listNotifySubscriptions(String taskId)
            throws Exception;

    KanbanNotifyClaim claimNotifyEvents(
            String taskId, String platform, String chatId, String threadId, List<String> kinds)
            throws Exception;

    boolean advanceNotifyCursor(
            String taskId, String platform, String chatId, String threadId, String newCursor)
            throws Exception;

    boolean rewindNotifyCursor(
            String taskId,
            String platform,
            String chatId,
            String threadId,
            String claimedCursor,
            String oldCursor)
            throws Exception;

    boolean removeNotifySubscription(String taskId, String platform, String chatId, String threadId)
            throws Exception;

    KanbanRunRecord addRun(KanbanRunRecord run) throws Exception;

    List<KanbanRunRecord> listRuns(String taskId, boolean includeActive) throws Exception;

    KanbanRunRecord latestRun(String taskId) throws Exception;

    String latestSummary(String taskId) throws Exception;

    Map<String, String> latestSummaries(List<String> taskIds) throws Exception;

    KanbanRunRecord activeRun(String taskId) throws Exception;

    boolean closeActiveRun(
            String taskId,
            String status,
            String outcome,
            String summary,
            String metadataJson,
            String error)
            throws Exception;

    boolean updateLatestRun(String taskId, String summary, String metadataJson, String error)
            throws Exception;

    boolean editCompletedTaskResult(String taskId, String result, String summary, String metadataJson)
            throws Exception;
}
