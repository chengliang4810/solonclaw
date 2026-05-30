package com.jimuqu.solon.claw.storage.repository;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.kanban.KanbanBoardRecord;
import com.jimuqu.solon.claw.kanban.KanbanCommentRecord;
import com.jimuqu.solon.claw.kanban.KanbanEventRecord;
import com.jimuqu.solon.claw.kanban.KanbanNotifyClaim;
import com.jimuqu.solon.claw.kanban.KanbanNotifySubscriptionRecord;
import com.jimuqu.solon.claw.kanban.KanbanRepository;
import com.jimuqu.solon.claw.kanban.KanbanRunRecord;
import com.jimuqu.solon.claw.kanban.KanbanTaskRecord;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;

/** SQLite-backed Kanban repository. */
@RequiredArgsConstructor
public class SqliteKanbanRepository implements KanbanRepository {
    public static final String DEFAULT_BOARD = "default";
    private static final long DEFAULT_CLAIM_TTL_SECONDS = 900L;
    private static final long DEFAULT_CRASH_GRACE_SECONDS = 30L;
    private static final long STALE_HEARTBEAT_GAP_SECONDS = 3600L;

    private final SqliteDatabase database;

    @Override
    public List<KanbanBoardRecord> listBoards() throws Exception {
        return listBoards(false);
    }

    @Override
    public List<KanbanBoardRecord> listBoards(boolean includeArchived) throws Exception {
        ensureDefaultBoard();
        List<KanbanBoardRecord> boards = new ArrayList<KanbanBoardRecord>();
        Connection connection = database.openConnection();
        try {
            String sql =
                    includeArchived
                            ? "select * from kanban_boards order by current desc, updated_at desc"
                            : "select * from kanban_boards where archived = 0 order by current desc, updated_at desc";
            PreparedStatement statement =
                    connection.prepareStatement(sql);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    boards.add(mapBoard(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return boards;
    }

    @Override
    public KanbanBoardRecord findBoard(String slug) throws Exception {
        String normalized = normalizeBoard(slug);
        ensureDefaultBoard();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("select * from kanban_boards where slug = ? and archived = 0");
            statement.setString(1, normalized);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? mapBoard(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    @Override
    public KanbanBoardRecord currentBoard() throws Exception {
        ensureDefaultBoard();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from kanban_boards where current = 1 and archived = 0 order by updated_at desc limit 1");
            ResultSet resultSet = statement.executeQuery();
            try {
                if (resultSet.next()) {
                    return mapBoard(resultSet);
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return findBoard(DEFAULT_BOARD);
    }

    @Override
    public KanbanBoardRecord saveBoard(KanbanBoardRecord board) throws Exception {
        if (board == null) {
            throw new IllegalArgumentException("board is required");
        }
        String slug = normalizeBoard(board.getSlug());
        long now = System.currentTimeMillis();
        if (StrUtil.isBlank(board.getBoardId())) {
            board.setBoardId("board_" + IdSupport.newId());
        }
        board.setSlug(slug);
        board.setName(StrUtil.blankToDefault(board.getName(), slug));
        if (board.getCreatedAt() <= 0) {
            board.setCreatedAt(now);
        }
        board.setUpdatedAt(now);

        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into kanban_boards (board_id, slug, name, description, color, current, archived, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, board.getBoardId());
            statement.setString(2, board.getSlug());
            statement.setString(3, board.getName());
            statement.setString(4, board.getDescription());
            statement.setString(5, board.getColor());
            statement.setInt(6, board.isCurrent() ? 1 : 0);
            statement.setInt(7, board.isArchived() ? 1 : 0);
            statement.setLong(8, board.getCreatedAt());
            statement.setLong(9, board.getUpdatedAt());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
        if (board.isCurrent()) {
            switchBoard(board.getSlug());
        }
        return board;
    }

    @Override
    public void switchBoard(String slug) throws Exception {
        String normalized = normalizeBoard(slug);
        if (findBoard(normalized) == null) {
            throw new IllegalArgumentException("Kanban board not found: " + normalized);
        }
        Connection connection = database.openConnection();
        try {
            Statement statement = connection.createStatement();
            try {
                statement.executeUpdate("update kanban_boards set current = 0");
            } finally {
                statement.close();
            }
            PreparedStatement update =
                    connection.prepareStatement(
                            "update kanban_boards set current = 1, updated_at = ? where slug = ?");
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, normalized);
            update.executeUpdate();
            update.close();
        } finally {
            connection.close();
        }
    }

    @Override
    public List<KanbanTaskRecord> listTasks(String boardSlug, String status, boolean includeArchived)
            throws Exception {
        return listTasks(boardSlug, status, includeArchived, null);
    }

    @Override
    public List<KanbanTaskRecord> listTasks(
            String boardSlug, String status, boolean includeArchived, String sessionId)
            throws Exception {
        String slug = StrUtil.isBlank(boardSlug) ? currentBoard().getSlug() : normalizeBoard(boardSlug);
        List<KanbanTaskRecord> tasks = new ArrayList<KanbanTaskRecord>();
        StringBuilder sql =
                new StringBuilder("select * from kanban_tasks where board_slug = ?");
        List<String> args = new ArrayList<String>();
        args.add(slug);
        if (StrUtil.isNotBlank(status)) {
            sql.append(" and status = ?");
            args.add(status.trim().toLowerCase());
        } else if (!includeArchived) {
            sql.append(" and status <> 'archived'");
        }
        if (StrUtil.isNotBlank(sessionId)) {
            sql.append(" and session_id = ?");
            args.add(sessionId.trim());
        }
        sql.append(" order by priority desc, created_at asc");

        Connection connection = database.openConnection();
        try {
            PreparedStatement statement = connection.prepareStatement(sql.toString());
            for (int i = 0; i < args.size(); i++) {
                statement.setString(i + 1, args.get(i));
            }
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    tasks.add(mapTask(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return tasks;
    }

    @Override
    public List<KanbanTaskRecord> listReadyTasks(String boardSlug) throws Exception {
        String slug = StrUtil.isBlank(boardSlug) ? currentBoard().getSlug() : normalizeBoard(boardSlug);
        List<KanbanTaskRecord> tasks = new ArrayList<KanbanTaskRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from kanban_tasks where board_slug = ? and status = 'ready' and claim_lock is null order by priority desc, created_at asc");
            statement.setString(1, slug);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    tasks.add(mapTask(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return tasks;
    }

    @Override
    public Map<String, Map<String, Integer>> countTasksByAssignee(String boardSlug) throws Exception {
        String slug = StrUtil.isBlank(boardSlug) ? currentBoard().getSlug() : normalizeBoard(boardSlug);
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<String, Map<String, Integer>>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select assignee, status, count(*) as n from kanban_tasks "
                                    + "where board_slug = ? and status <> 'archived' and assignee is not null and assignee <> '' "
                                    + "group by assignee, status order by assignee asc, status asc");
            statement.setString(1, slug);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    String assignee = resultSet.getString("assignee");
                    Map<String, Integer> statusCounts = counts.get(assignee);
                    if (statusCounts == null) {
                        statusCounts = new LinkedHashMap<String, Integer>();
                        counts.put(assignee, statusCounts);
                    }
                    statusCounts.put(
                            resultSet.getString("status"),
                            Integer.valueOf(resultSet.getInt("n")));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return counts;
    }

    @Override
    public KanbanTaskRecord findTask(String taskId) throws Exception {
        Connection connection = database.openConnection();
        try {
            return findTask(connection, taskId);
        } finally {
            connection.close();
        }
    }

    @Override
    public KanbanTaskRecord saveTask(KanbanTaskRecord task) throws Exception {
        if (task == null || StrUtil.isBlank(task.getTitle())) {
            throw new IllegalArgumentException("task title is required");
        }
        long now = System.currentTimeMillis();
        KanbanTaskRecord previous =
                StrUtil.isBlank(task.getTaskId()) ? null : findTask(task.getTaskId());
        if (StrUtil.isBlank(task.getTaskId())) {
            task.setTaskId("KB-" + IdSupport.newId().substring(0, 8).toUpperCase());
        }
        task.setBoardSlug(
                StrUtil.isBlank(task.getBoardSlug())
                        ? currentBoard().getSlug()
                        : normalizeBoard(task.getBoardSlug()));
        task.setStatus(normalizeStatus(StrUtil.blankToDefault(task.getStatus(), "todo")));
        task.setWorkspaceKind(normalizeWorkspaceKind(task.getWorkspaceKind()));
        task.setCreatedBy(StrUtil.blankToDefault(task.getCreatedBy(), "user"));
        if (task.getCreatedAt() <= 0) {
            task.setCreatedAt(now);
        }
        task.setUpdatedAt(now);

        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                connection.prepareStatement(
                            "insert or replace into kanban_tasks (task_id, board_slug, title, body, assignee, status, priority, tenant, session_id, workspace_kind, workspace_path, branch_name, created_by, result, idempotency_key, claim_lock, claim_expires_at, worker_id, worker_pid, last_spawn_error, spawn_failures, max_retries, max_runtime_seconds, last_heartbeat_at, current_run_id, workflow_template_id, current_step_key, skills_json, created_at, updated_at, started_at, completed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, task.getTaskId());
            statement.setString(2, task.getBoardSlug());
            statement.setString(3, task.getTitle());
            statement.setString(4, task.getBody());
            statement.setString(5, task.getAssignee());
            statement.setString(6, task.getStatus());
            statement.setInt(7, task.getPriority());
            statement.setString(8, task.getTenant());
            statement.setString(9, task.getSessionId());
            statement.setString(10, task.getWorkspaceKind());
            statement.setString(11, task.getWorkspacePath());
            statement.setString(12, task.getBranchName());
            statement.setString(13, task.getCreatedBy());
            statement.setString(14, task.getResult());
            statement.setString(15, task.getIdempotencyKey());
            statement.setString(16, task.getClaimLock());
            statement.setLong(17, task.getClaimExpiresAt());
            statement.setString(18, task.getWorkerId());
            statement.setLong(19, task.getWorkerPid());
            statement.setString(20, task.getLastSpawnError());
            statement.setInt(21, task.getSpawnFailures());
            if (task.getMaxRetries() == null) {
                statement.setNull(22, java.sql.Types.INTEGER);
            } else {
                statement.setInt(22, task.getMaxRetries().intValue());
            }
            statement.setLong(23, task.getMaxRuntimeSeconds());
            statement.setLong(24, task.getLastHeartbeatAt());
            statement.setString(25, task.getCurrentRunId());
            statement.setString(26, task.getWorkflowTemplateId());
            statement.setString(27, task.getCurrentStepKey());
            statement.setString(28, task.getSkillsJson());
            statement.setLong(29, task.getCreatedAt());
            statement.setLong(30, task.getUpdatedAt());
            statement.setLong(31, task.getStartedAt());
            statement.setLong(32, task.getCompletedAt());
            statement.executeUpdate();
            statement.close();
            KanbanTaskRecord persisted = findTask(task.getTaskId());
            if (previous == null) {
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("title", persisted.getTitle());
                payload.put("assignee", persisted.getAssignee());
                payload.put("status", persisted.getStatus());
                payload.put("tenant", persisted.getTenant());
                payload.put("created_by", persisted.getCreatedBy());
                payload.put("branch_name", persisted.getBranchName());
                addEvent(connection, persisted.getTaskId(), "created", payload);
            }
            if ("running".equals(persisted.getStatus())) {
                ensureActiveRun(persisted, now, connection);
            } else if (previous != null && StrUtil.isNotBlank(previous.getCurrentRunId())) {
                if ("done".equals(persisted.getStatus())) {
                    closeOrSynthesizeRun(
                            persisted,
                            "done",
                            "completed",
                            persisted.getResult(),
                            null,
                            null,
                            now,
                            connection);
                } else if ("blocked".equals(persisted.getStatus())) {
                    closeOrSynthesizeRun(
                            persisted,
                            "blocked",
                            "blocked",
                            persisted.getResult(),
                            null,
                            persisted.getResult(),
                            now,
                            connection);
                } else {
                    closeOrSynthesizeRun(
                            persisted,
                            "released",
                            "released",
                            persisted.getResult(),
                            null,
                            null,
                            now,
                            connection);
                }
            }
        } finally {
            connection.close();
        }
        return findTask(task.getTaskId());
    }

    @Override
    public void linkTasks(String parentId, String childId) throws Exception {
        if (StrUtil.hasBlank(parentId, childId)) {
            return;
        }
        if (StrUtil.equals(parentId, childId)) {
            throw new IllegalArgumentException("Kanban task cannot link to itself: " + parentId);
        }
        Connection connection = database.openConnection();
        try {
            KanbanTaskRecord parent = findTask(connection, parentId);
            KanbanTaskRecord child = findTask(connection, childId);
            if (parent == null) {
                throw new IllegalArgumentException("Kanban task not found: " + parentId);
            }
            if (child == null) {
                throw new IllegalArgumentException("Kanban task not found: " + childId);
            }
            if (!StrUtil.equals(parent.getBoardSlug(), child.getBoardSlug())) {
                throw new IllegalArgumentException(
                        "Kanban tasks must be on the same board: " + parentId + " -> " + childId);
            }
            if (linkWouldCreateCycle(connection, parentId, childId)) {
                throw new IllegalArgumentException(
                        "Kanban link would create a dependency cycle: " + parentId + " -> " + childId);
            }
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or ignore into kanban_task_links (parent_id, child_id) values (?, ?)");
            statement.setString(1, parentId);
            statement.setString(2, childId);
            statement.executeUpdate();
            statement.close();
            PreparedStatement demote =
                    connection.prepareStatement(
                            "update kanban_tasks set status = 'todo', updated_at = ? where task_id = ? and status = 'ready' and exists (select 1 from kanban_task_links l join kanban_tasks p on p.task_id = l.parent_id where l.child_id = kanban_tasks.task_id and p.status not in ('done', 'archived'))");
            demote.setLong(1, System.currentTimeMillis());
            demote.setString(2, childId);
            demote.executeUpdate();
            demote.close();
        } finally {
            connection.close();
        }
    }

    private KanbanTaskRecord findTask(Connection connection, String taskId) throws Exception {
        PreparedStatement statement =
                connection.prepareStatement("select * from kanban_tasks where task_id = ?");
        statement.setString(1, taskId);
        ResultSet resultSet = statement.executeQuery();
        try {
            return resultSet.next() ? mapTask(resultSet) : null;
        } finally {
            resultSet.close();
            statement.close();
        }
    }

    private boolean linkWouldCreateCycle(Connection connection, String parentId, String childId) throws Exception {
        PreparedStatement statement =
                connection.prepareStatement(
                        "with recursive descendants(task_id) as ("
                                + "select child_id from kanban_task_links where parent_id = ? "
                                + "union "
                                + "select l.child_id from kanban_task_links l join descendants d on l.parent_id = d.task_id"
                                + ") select 1 from descendants where task_id = ? limit 1");
        statement.setString(1, childId);
        statement.setString(2, parentId);
        ResultSet resultSet = statement.executeQuery();
        try {
            return resultSet.next();
        } finally {
            resultSet.close();
            statement.close();
        }
    }

    @Override
    public boolean unlinkTasks(String parentId, String childId) throws Exception {
        if (StrUtil.hasBlank(parentId, childId)) {
            return false;
        }
        String boardSlug = null;
        Connection connection = database.openConnection();
        try {
            PreparedStatement taskStatement =
                    connection.prepareStatement("select board_slug from kanban_tasks where task_id = ?");
            taskStatement.setString(1, childId);
            ResultSet taskResult = taskStatement.executeQuery();
            try {
                if (taskResult.next()) {
                    boardSlug = taskResult.getString("board_slug");
                }
            } finally {
                taskResult.close();
                taskStatement.close();
            }
            PreparedStatement statement =
                    connection.prepareStatement(
                            "delete from kanban_task_links where parent_id = ? and child_id = ?");
            statement.setString(1, parentId);
            statement.setString(2, childId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated <= 0) {
                return false;
            }
        } finally {
            connection.close();
        }
        if (StrUtil.isNotBlank(boardSlug)) {
            recomputeReady(boardSlug);
        }
        return true;
    }

    @Override
    public boolean renameBoard(String slug, String name) throws Exception {
        String normalized = normalizeBoard(slug);
        if (StrUtil.isBlank(name)) {
            throw new IllegalArgumentException("board name is required");
        }
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_boards set name = ?, updated_at = ? where slug = ? and archived = 0");
            statement.setString(1, name.trim());
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, normalized);
            int updated = statement.executeUpdate();
            statement.close();
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean archiveBoard(String slug) throws Exception {
        String normalized = normalizeBoard(slug);
        if (DEFAULT_BOARD.equals(normalized)) {
            throw new IllegalArgumentException("default Kanban board cannot be archived");
        }
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_boards set archived = 1, current = 0, updated_at = ? where slug = ? and archived = 0");
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, normalized);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated > 0 && currentBoardNeedsFallback(connection)) {
                setDefaultCurrent(connection);
            }
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean deleteBoard(String slug) throws Exception {
        String normalized = normalizeBoard(slug);
        if (DEFAULT_BOARD.equals(normalized)) {
            throw new IllegalArgumentException("default Kanban board cannot be deleted");
        }
        Connection connection = database.openConnection();
        try {
            List<String> taskIds = new ArrayList<String>();
            PreparedStatement taskQuery =
                    connection.prepareStatement("select task_id from kanban_tasks where board_slug = ?");
            taskQuery.setString(1, normalized);
            ResultSet resultSet = taskQuery.executeQuery();
            try {
                while (resultSet.next()) {
                    taskIds.add(resultSet.getString("task_id"));
                }
            } finally {
                resultSet.close();
                taskQuery.close();
            }
            deleteTasks(connection, taskIds);
            PreparedStatement statement =
                    connection.prepareStatement("delete from kanban_boards where slug = ?");
            statement.setString(1, normalized);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated > 0 && currentBoardNeedsFallback(connection)) {
                setDefaultCurrent(connection);
            }
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    private boolean currentBoardNeedsFallback(Connection connection) throws Exception {
        PreparedStatement statement =
                connection.prepareStatement(
                        "select count(*) from kanban_boards where current = 1 and archived = 0");
        ResultSet resultSet = statement.executeQuery();
        try {
            return !resultSet.next() || resultSet.getLong(1) == 0L;
        } finally {
            resultSet.close();
            statement.close();
        }
    }

    private void setDefaultCurrent(Connection connection) throws Exception {
        PreparedStatement update =
                connection.prepareStatement(
                        "update kanban_boards set current = 1, archived = 0, updated_at = ? where slug = ?");
        update.setLong(1, System.currentTimeMillis());
        update.setString(2, DEFAULT_BOARD);
        update.executeUpdate();
        update.close();
    }

    @Override
    public List<KanbanTaskRecord> listParents(String taskId) throws Exception {
        return listLinkedTasks(
                "select t.* from kanban_tasks t join kanban_task_links l on l.parent_id = t.task_id where l.child_id = ? order by t.updated_at desc",
                taskId);
    }

    @Override
    public List<KanbanTaskRecord> listChildren(String taskId) throws Exception {
        return listLinkedTasks(
                "select t.* from kanban_tasks t join kanban_task_links l on l.child_id = t.task_id where l.parent_id = ? order by t.priority desc, t.updated_at desc",
                taskId);
    }

    @Override
    public boolean updateTaskStatus(String taskId, String status, String result) throws Exception {
        String normalized = normalizeStatus(status);
        long now = System.currentTimeMillis();
        KanbanTaskRecord before = findTask(taskId);
        if (before == null) {
            return false;
        }
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set status = ?, result = coalesce(?, result), updated_at = ?, started_at = case when ? = 'running' and started_at = 0 then ? else started_at end, completed_at = case when ? = 'done' then ? else completed_at end where task_id = ?");
            statement.setString(1, normalized);
            statement.setString(2, result);
            statement.setLong(3, now);
            statement.setString(4, normalized);
            statement.setLong(5, now);
            statement.setString(6, normalized);
            statement.setLong(7, now);
            statement.setString(8, taskId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated <= 0) {
                return false;
            }
            KanbanTaskRecord after = findTask(taskId);
            if ("running".equals(normalized)) {
                ensureActiveRun(after, now, connection);
            } else if ("done".equals(normalized)) {
                closeOrSynthesizeRun(
                        after, "done", "completed", StrUtil.blankToDefault(result, after.getResult()), null, null, now, connection);
            } else if ("blocked".equals(normalized)) {
                closeOrSynthesizeRun(after, "blocked", "blocked", result, null, result, now, connection);
            } else if ("scheduled".equals(normalized)) {
                closeOrSynthesizeRun(after, "scheduled", "scheduled", result, null, null, now, connection);
            } else if ("archived".equals(normalized)) {
                closeOrSynthesizeRun(after, "released", "archived", result, null, null, now, connection);
            } else if (StrUtil.isNotBlank(before.getCurrentRunId())) {
                closeOrSynthesizeRun(after, "released", "released", result, null, null, now, connection);
            }
            return true;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean unblockTask(String taskId) throws Exception {
        long now = System.currentTimeMillis();
        Connection connection = database.openConnection();
        try {
            KanbanTaskRecord task = findTask(taskId);
            if (task == null
                    || (!"blocked".equals(task.getStatus()) && !"scheduled".equals(task.getStatus()))) {
                return false;
            }
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set status = case when exists (select 1 from kanban_task_links l join kanban_tasks p on p.task_id = l.parent_id where l.child_id = kanban_tasks.task_id and p.status not in ('done', 'archived')) then 'todo' else 'ready' end, claim_lock = null, claim_expires_at = 0, worker_id = null, worker_pid = 0, current_run_id = null, spawn_failures = 0, last_spawn_error = null, completed_at = 0, updated_at = ? where task_id = ? and status in ('blocked', 'scheduled')");
            statement.setLong(1, now);
            statement.setString(2, taskId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated > 0) {
                closeRunById(task.getCurrentRunId(), "released", "unblocked", null, null, null, now, connection);
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("previous_status", task.getStatus());
                payload.put("previous_run_id", task.getCurrentRunId());
                payload.put("previous_result", task.getResult());
                addEvent(connection, taskId, "unblocked", payload);
            }
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean scheduleTask(String taskId, String reason) throws Exception {
        long now = System.currentTimeMillis();
        Connection connection = database.openConnection();
        try {
            KanbanTaskRecord task = findTask(taskId);
            if (task == null) {
                return false;
            }
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set status = 'scheduled', claim_lock = null, claim_expires_at = 0, worker_id = null, worker_pid = 0, current_run_id = null, updated_at = ? where task_id = ? and status in ('todo', 'ready', 'running', 'blocked')");
            statement.setLong(1, now);
            statement.setString(2, taskId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated > 0) {
                closeOrSynthesizeRun(task, "scheduled", "scheduled", reason, null, null, now, connection);
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("reason", reason);
                addEvent(connection, taskId, "scheduled", payload);
            }
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public KanbanTaskRecord findTaskByIdempotencyKey(String boardSlug, String idempotencyKey)
            throws Exception {
        if (StrUtil.isBlank(idempotencyKey)) {
            return null;
        }
        String slug = StrUtil.isBlank(boardSlug) ? currentBoard().getSlug() : normalizeBoard(boardSlug);
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from kanban_tasks where board_slug = ? and idempotency_key = ? and status <> 'archived' order by updated_at desc limit 1");
            statement.setString(1, slug);
            statement.setString(2, idempotencyKey);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? mapTask(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean reclaimTask(String taskId, String reason) throws Exception {
        long now = System.currentTimeMillis();
        Connection connection = database.openConnection();
        try {
            KanbanTaskRecord task = findTask(taskId);
            if (task == null) {
                return false;
            }
            if (!"running".equals(task.getStatus()) && StrUtil.isBlank(task.getClaimLock())) {
                return false;
            }
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set status = 'ready', claim_lock = null, claim_expires_at = 0, worker_id = null, worker_pid = 0, current_run_id = null, updated_at = ? where task_id = ?");
            statement.setLong(1, now);
            statement.setString(2, taskId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated > 0) {
                closeRunById(task.getCurrentRunId(), "released", "reclaimed", reason, null, null, now, connection);
                KanbanEventRecord event = new KanbanEventRecord();
                event.setTaskId(taskId);
                event.setKind("reclaimed");
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("manual", Boolean.TRUE);
                payload.put("reason", reason);
                payload.put("prev_lock", task.getClaimLock());
                payload.put("prev_worker", task.getWorkerId());
                event.setPayloadJson(ONode.serialize(payload));
                addEvent(event);
            }
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean assignTask(String taskId, String assignee) throws Exception {
        return assignTask(taskId, assignee, null);
    }

    @Override
    public boolean assignTask(String taskId, String assignee, String source) throws Exception {
        KanbanTaskRecord task = findTask(taskId);
        if (task != null && "running".equals(task.getStatus())) {
            return false;
        }
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set assignee = ?, updated_at = ? where task_id = ?");
            statement.setString(1, StrUtil.blankToDefault(assignee, null));
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, taskId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated > 0) {
                KanbanEventRecord event = new KanbanEventRecord();
                event.setTaskId(taskId);
                event.setKind("assigned");
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("assignee", StrUtil.blankToDefault(assignee, null));
                if (StrUtil.isNotBlank(source)) {
                    payload.put("source", source);
                }
                event.setPayloadJson(ONode.serialize(payload));
                addEvent(event);
            }
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean setWorkspacePath(String taskId, String workspacePath) throws Exception {
        if (StrUtil.isBlank(taskId)) {
            return false;
        }
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set workspace_path = ?, updated_at = ? where task_id = ?");
            statement.setString(1, workspacePath);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, taskId);
            int updated = statement.executeUpdate();
            statement.close();
            return updated == 1;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean reassignTask(String taskId, String assignee, boolean reclaimFirst, String reason)
            throws Exception {
        if (reclaimFirst) {
            reclaimTask(taskId, StrUtil.blankToDefault(reason, "reassign"));
        }
        boolean assigned = assignTask(taskId, assignee);
        if (assigned) {
            KanbanEventRecord event = new KanbanEventRecord();
            event.setTaskId(taskId);
            event.setKind("reassigned");
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("assignee", assignee);
            payload.put("reclaim_first", Boolean.valueOf(reclaimFirst));
            payload.put("reason", reason);
            event.setPayloadJson(ONode.serialize(payload));
            addEvent(event);
        }
        return assigned;
    }

    @Override
    public boolean retryTask(String taskId, String reason) throws Exception {
        KanbanTaskRecord task = findTask(taskId);
        if (task == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Connection connection = database.openConnection();
        try {
            closeRunById(task.getCurrentRunId(), "released", "retried", reason, null, null, now, connection);
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set status = 'ready', claim_lock = null, claim_expires_at = 0, worker_id = null, current_run_id = null, completed_at = 0, updated_at = ? where task_id = ?");
            statement.setLong(1, now);
            statement.setString(2, taskId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated > 0) {
                KanbanEventRecord event = new KanbanEventRecord();
                event.setTaskId(taskId);
                event.setKind("retry");
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("reason", reason);
                payload.put("previous_status", task.getStatus());
                payload.put("previous_run_id", task.getCurrentRunId());
                event.setPayloadJson(ONode.serialize(payload));
                addEvent(event);
            }
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public KanbanTaskRecord claimTask(
            String taskId, String claimer, long ttlSeconds, String workerId, long workerPid)
            throws Exception {
        return claimTaskWithStatus(taskId, "ready", claimer, ttlSeconds, workerId, workerPid);
    }

    @Override
    public KanbanTaskRecord claimReviewTask(
            String taskId, String claimer, long ttlSeconds, String workerId, long workerPid)
            throws Exception {
        return claimTaskWithStatus(taskId, "review", claimer, ttlSeconds, workerId, workerPid);
    }

    private KanbanTaskRecord claimTaskWithStatus(
            String taskId, String expectedStatus, String claimer, long ttlSeconds, String workerId, long workerPid)
            throws Exception {
        if (StrUtil.isBlank(taskId)) {
            return null;
        }
        long now = System.currentTimeMillis();
        long expires = now + Math.max(1L, ttlSeconds) * 1000L;
        String lock = StrUtil.blankToDefault(claimer, "local");
        Connection connection = database.openConnection();
        try {
            KanbanTaskRecord task = findTask(taskId);
            if (task == null) {
                return null;
            }
            closeLeakedReadyRun(task, now, connection);
            if ("ready".equals(expectedStatus) && hasUndoneParents(taskId, connection)) {
                PreparedStatement demote =
                        connection.prepareStatement(
                                "update kanban_tasks set status = 'todo', updated_at = ? where task_id = ? and status = 'ready'");
                demote.setLong(1, now);
                demote.setString(2, taskId);
                demote.executeUpdate();
                demote.close();
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("reason", "parents_not_done");
                addEvent(connection, taskId, "claim_rejected", payload);
                return null;
            }
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set status = 'running', claim_lock = ?, claim_expires_at = ?, worker_id = ?, worker_pid = ?, last_spawn_error = null, started_at = case when started_at = 0 then ? else started_at end, updated_at = ? where task_id = ? and status = ?");
            statement.setString(1, lock);
            statement.setLong(2, expires);
            statement.setString(3, StrUtil.blankToDefault(workerId, lock));
            statement.setLong(4, workerPid);
            statement.setLong(5, now);
            statement.setLong(6, now);
            statement.setString(7, taskId);
            statement.setString(8, expectedStatus);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated != 1) {
                return null;
            }
            KanbanTaskRecord claimed = findTask(taskId);
            ensureActiveRun(claimed, now, connection);
            claimed = findTask(taskId);
            syncActiveRunRuntime(claimed, connection);
            addEvent(connection, taskId, "claimed", claimedPayload(claimed));
            return findTask(taskId);
        } finally {
            connection.close();
        }
    }

    private boolean hasUndoneParents(String taskId, Connection connection) throws Exception {
        PreparedStatement statement =
                connection.prepareStatement(
                        "select 1 from kanban_task_links l join kanban_tasks p on p.task_id = l.parent_id where l.child_id = ? and p.status not in ('done', 'archived') limit 1");
        statement.setString(1, taskId);
        ResultSet resultSet = statement.executeQuery();
        try {
            return resultSet.next();
        } finally {
            resultSet.close();
            statement.close();
        }
    }

    @Override
    public KanbanTaskRecord claimNextReady(
            String boardSlug, String assignee, String claimer, long ttlSeconds, String workerId, long workerPid)
            throws Exception {
        String slug = StrUtil.isBlank(boardSlug) ? currentBoard().getSlug() : normalizeBoard(boardSlug);
        Connection connection = database.openConnection();
        try {
            StringBuilder sql =
                    new StringBuilder(
                            "select task_id from kanban_tasks where board_slug = ? and status = 'ready'");
            if (StrUtil.isNotBlank(assignee)) {
                sql.append(" and assignee = ?");
            }
            sql.append(" order by priority desc, updated_at asc");
            PreparedStatement statement = connection.prepareStatement(sql.toString());
            statement.setString(1, slug);
            if (StrUtil.isNotBlank(assignee)) {
                statement.setString(2, assignee);
            }
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    KanbanTaskRecord claimed =
                            claimTask(resultSet.getString("task_id"), claimer, ttlSeconds, workerId, workerPid);
                    if (claimed != null) {
                        return claimed;
                    }
                }
                return null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean heartbeatClaim(String taskId, String claimer, long ttlSeconds) throws Exception {
        if (StrUtil.hasBlank(taskId, claimer)) {
            return false;
        }
        long expires = System.currentTimeMillis() + Math.max(1L, ttlSeconds) * 1000L;
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set claim_expires_at = ?, updated_at = ? where task_id = ? and status = 'running' and claim_lock = ?");
            statement.setLong(1, expires);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, taskId);
            statement.setString(4, claimer);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated == 1) {
                KanbanTaskRecord task = findTask(taskId);
                syncActiveRunRuntime(task, connection);
                return true;
            }
            return false;
        } finally {
            connection.close();
        }
    }

    @Override
    public int releaseStaleClaims(long now) throws Exception {
        int reclaimed = 0;
        Connection connection = database.openConnection();
        try {
            PreparedStatement select =
                    connection.prepareStatement(
                            "select * from kanban_tasks where status = 'running' and claim_expires_at > 0 and claim_expires_at < ?");
            select.setLong(1, now);
            ResultSet resultSet = select.executeQuery();
            try {
                while (resultSet.next()) {
                    KanbanTaskRecord task = mapTask(resultSet);
                    if (isWorkerPidAlive(task.getWorkerPid())) {
                        extendLiveStaleClaim(connection, task, now);
                        continue;
                    }
                    if (reclaimRunningTask(connection, task, "reclaimed", "reclaimed", "stale_lock=" + task.getClaimLock(), "reclaimed", true, now)) {
                        reclaimed++;
                    }
                }
            } finally {
                resultSet.close();
                select.close();
            }
        } finally {
            connection.close();
        }
        return reclaimed;
    }

    @Override
    public List<String> reclaimCrashedWorkers(long now) throws Exception {
        List<String> crashed = new ArrayList<String>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement select =
                    connection.prepareStatement(
                            "select * from kanban_tasks where status = 'running' and worker_pid > 0");
            ResultSet resultSet = select.executeQuery();
            try {
                while (resultSet.next()) {
                    KanbanTaskRecord task = mapTask(resultSet);
                    if (isWithinCrashGraceWindow(task, now)) {
                        continue;
                    }
                    if (isWorkerPidAlive(task.getWorkerPid())) {
                        continue;
                    }
                    String error = "pid " + task.getWorkerPid() + " not alive";
                    if (reclaimRunningTask(connection, task, "crashed", "crashed", error, "crashed", true, now)) {
                        crashed.add(task.getTaskId());
                    }
                }
            } finally {
                resultSet.close();
                select.close();
            }
        } finally {
            connection.close();
        }
        return crashed;
    }

    @Override
    public List<String> reclaimStaleRunning(long now, long staleTimeoutSeconds) throws Exception {
        List<String> stale = new ArrayList<String>();
        if (staleTimeoutSeconds <= 0) {
            return stale;
        }
        Connection connection = database.openConnection();
        try {
            PreparedStatement select =
                    connection.prepareStatement(
                            "select * from kanban_tasks where status = 'running' and started_at > 0 and (? - started_at) >= ? * 1000 and (last_heartbeat_at <= 0 or (? - last_heartbeat_at) >= ? * 1000)");
            select.setLong(1, now);
            select.setLong(2, staleTimeoutSeconds);
            select.setLong(3, now);
            select.setLong(4, STALE_HEARTBEAT_GAP_SECONDS);
            ResultSet resultSet = select.executeQuery();
            try {
                while (resultSet.next()) {
                    KanbanTaskRecord task = mapTask(resultSet);
                    String error = staleError(task, now);
                    if (reclaimRunningTask(connection, task, "stale", "stale", error, "stale", true, now)) {
                        stale.add(task.getTaskId());
                    }
                }
            } finally {
                resultSet.close();
                select.close();
            }
        } finally {
            connection.close();
        }
        return stale;
    }

    private String staleError(KanbanTaskRecord task, long now) {
        long elapsedSeconds = task == null ? 0L : Math.max(0L, (now - task.getStartedAt()) / 1000L);
        if (task == null || task.getLastHeartbeatAt() <= 0) {
            return "heartbeat stale: no heartbeat after " + elapsedSeconds + "s running";
        }
        long heartbeatAgeSeconds = Math.max(0L, (now - task.getLastHeartbeatAt()) / 1000L);
        return "heartbeat stale: no heartbeat for "
                + heartbeatAgeSeconds
                + "s after "
                + elapsedSeconds
                + "s running";
    }

    private boolean isWithinCrashGraceWindow(KanbanTaskRecord task, long now) {
        if (task == null || task.getStartedAt() <= 0) {
            return false;
        }
        return now - task.getStartedAt() < DEFAULT_CRASH_GRACE_SECONDS * 1000L;
    }

    private void extendLiveStaleClaim(Connection connection, KanbanTaskRecord task, long now)
            throws Exception {
        long previousExpiresAt = task.getClaimExpiresAt();
        long nextExpiresAt = now + DEFAULT_CLAIM_TTL_SECONDS * 1000L;
        PreparedStatement statement =
                connection.prepareStatement(
                        "update kanban_tasks set claim_expires_at = ?, updated_at = ? where task_id = ? and status = 'running'");
        statement.setLong(1, nextExpiresAt);
        statement.setLong(2, now);
        statement.setString(3, task.getTaskId());
        int updated = statement.executeUpdate();
        statement.close();
        if (updated != 1) {
            return;
        }
        task.setClaimExpiresAt(nextExpiresAt);
        task.setUpdatedAt(now);
        syncActiveRunRuntime(task, connection);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("prev_claim_expires_at", Long.valueOf(previousExpiresAt));
        payload.put("claim_expires_at", Long.valueOf(nextExpiresAt));
        payload.put("worker_pid", Long.valueOf(task.getWorkerPid()));
        payload.put("reason", "worker_pid_alive");
        addEvent(connection, task.getTaskId(), "claim_extended", payload);
    }

    private boolean isWorkerPidAlive(long pid) {
        if (pid <= 0) {
            return false;
        }
        Process process = null;
        try {
            process = new ProcessBuilder("kill", "-0", String.valueOf(pid)).start();
            return process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @Override
    public boolean heartbeatWorker(String taskId, String note) throws Exception {
        long now = System.currentTimeMillis();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set last_heartbeat_at = ?, updated_at = ? where task_id = ? and status = 'running'");
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, taskId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated != 1) {
                return false;
            }
            KanbanTaskRecord task = findTask(taskId);
            syncActiveRunRuntime(task, connection);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("note", note);
            addEvent(connection, taskId, "heartbeat", payload);
            return true;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean markSpawnFailure(String taskId, String error) throws Exception {
        long now = System.currentTimeMillis();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set last_spawn_error = ?, spawn_failures = spawn_failures + 1, worker_pid = 0, updated_at = ? where task_id = ?");
            statement.setString(1, error);
            statement.setLong(2, now);
            statement.setString(3, taskId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated == 1) {
                KanbanTaskRecord task = findTask(taskId);
                updateActiveRunError(task, error, connection);
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("error", error);
                addEvent(connection, taskId, "spawn_failed", payload);
                return true;
            }
            return false;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean clearSpawnFailures(String taskId, long workerPid) throws Exception {
        if (StrUtil.isBlank(taskId)) {
            return false;
        }
        long now = System.currentTimeMillis();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set spawn_failures = 0, last_spawn_error = null, worker_pid = ?, updated_at = ? where task_id = ? and status = 'running'");
            statement.setLong(1, workerPid);
            statement.setLong(2, now);
            statement.setString(3, taskId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated == 1) {
                KanbanTaskRecord task = findTask(taskId);
                syncActiveRunRuntime(task, connection);
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("worker_pid", workerPid <= 0 ? null : Long.valueOf(workerPid));
                addEvent(connection, taskId, "spawned", payload);
                return true;
            }
            return false;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean autoBlockAfterSpawnFailure(String taskId, int failureLimit, String reason)
            throws Exception {
        if (StrUtil.isBlank(taskId)) {
            return false;
        }
        KanbanTaskRecord task = findTask(taskId);
        if (task == null) {
            return false;
        }
        int effectiveLimit = task.getMaxRetries() == null
                ? Math.max(1, failureLimit)
                : Math.max(1, task.getMaxRetries().intValue());
        String limitSource = task.getMaxRetries() == null ? "dispatcher" : "task";
        if (task.getSpawnFailures() < effectiveLimit) {
            return false;
        }
        long now = System.currentTimeMillis();
        String error = StrUtil.blankToDefault(reason, task.getLastSpawnError());
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_tasks set status = 'blocked', result = coalesce(?, result), claim_lock = null, claim_expires_at = 0, worker_id = null, worker_pid = 0, current_run_id = null, updated_at = ? where task_id = ? and status = 'running'");
            statement.setString(1, error);
            statement.setLong(2, now);
            statement.setString(3, taskId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated != 1) {
                return false;
            }
            closeRunById(task.getCurrentRunId(), "blocked", "gave_up", error, null, error, now, connection);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("failure_limit", Integer.valueOf(Math.max(1, failureLimit)));
            payload.put("effective_limit", Integer.valueOf(effectiveLimit));
            payload.put("limit_source", limitSource);
            payload.put("spawn_failures", Integer.valueOf(task.getSpawnFailures()));
            payload.put("error", error);
            addEvent(connection, taskId, "gave_up", payload);
            return true;
        } finally {
            connection.close();
        }
    }

    @Override
    public int recomputeReady(String boardSlug) throws Exception {
        String slug = StrUtil.isBlank(boardSlug) ? currentBoard().getSlug() : normalizeBoard(boardSlug);
        List<String> promotable = new ArrayList<String>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement select =
                    connection.prepareStatement(
                            "select t.task_id from kanban_tasks t where t.board_slug = ? and t.status in ('todo', 'blocked') and not exists (select 1 from kanban_task_links l join kanban_tasks p on p.task_id = l.parent_id where l.child_id = t.task_id and p.status not in ('done', 'archived')) and not exists (select 1 from kanban_events e where e.rowid = (select max(mx.rowid) from kanban_events mx where mx.task_id = t.task_id and mx.kind in ('blocked', 'unblocked')) and e.kind = 'blocked')");
            select.setString(1, slug);
            ResultSet resultSet = select.executeQuery();
            try {
                while (resultSet.next()) {
                    promotable.add(resultSet.getString("task_id"));
                }
            } finally {
                resultSet.close();
                select.close();
            }
            int promoted = 0;
            for (String taskId : promotable) {
                PreparedStatement update =
                        connection.prepareStatement(
                                "update kanban_tasks set status = 'ready', spawn_failures = 0, last_spawn_error = null, updated_at = ? where task_id = ? and status in ('todo', 'blocked')");
                update.setLong(1, System.currentTimeMillis());
                update.setString(2, taskId);
                int count = update.executeUpdate();
                update.close();
                if (count == 1) {
                    promoted++;
                    Map<String, Object> payload = new LinkedHashMap<String, Object>();
                    payload.put("reason", "parents_done");
                    addEvent(connection, taskId, "promoted_ready", payload);
                }
            }
            return promoted;
        } finally {
            connection.close();
        }
    }

    @Override
    public List<String> reclaimTimedOutWorkers(long now) throws Exception {
        List<String> reclaimed = new ArrayList<String>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement select =
                    connection.prepareStatement(
                            "select t.* from kanban_tasks t left join kanban_runs r on r.run_id = t.current_run_id where t.status = 'running' and t.max_runtime_seconds > 0 and coalesce(r.started_at, t.started_at) > 0 and (? - coalesce(r.started_at, t.started_at)) > t.max_runtime_seconds * 1000");
            select.setLong(1, now);
            ResultSet resultSet = select.executeQuery();
            try {
                while (resultSet.next()) {
                    KanbanTaskRecord task = mapTask(resultSet);
                    String error = "runtime_limit=" + task.getMaxRuntimeSeconds();
                    if (reclaimRunningTask(connection, task, "timed_out", "timed_out", error, "timed_out", true, now)) {
                        reclaimed.add(task.getTaskId());
                    }
                }
            } finally {
                resultSet.close();
                select.close();
            }
        } finally {
            connection.close();
        }
        return reclaimed;
    }

    @Override
    public void deleteTask(String taskId) throws Exception {
        Connection connection = database.openConnection();
        try {
            deleteTasks(connection, Collections.singletonList(taskId));
        } finally {
            connection.close();
        }
    }

    private void deleteTasks(Connection connection, List<String> taskIds) throws Exception {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        for (String taskId : taskIds) {
            PreparedStatement comments =
                    connection.prepareStatement("delete from kanban_comments where task_id = ?");
            comments.setString(1, taskId);
            comments.executeUpdate();
            comments.close();
            PreparedStatement events =
                    connection.prepareStatement("delete from kanban_events where task_id = ?");
            events.setString(1, taskId);
            events.executeUpdate();
            events.close();
            PreparedStatement runs =
                    connection.prepareStatement("delete from kanban_runs where task_id = ?");
            runs.setString(1, taskId);
            runs.executeUpdate();
            runs.close();
            PreparedStatement subscriptions =
                    connection.prepareStatement("delete from kanban_notify_subscriptions where task_id = ?");
            subscriptions.setString(1, taskId);
            subscriptions.executeUpdate();
            subscriptions.close();
            PreparedStatement links =
                    connection.prepareStatement(
                            "delete from kanban_task_links where parent_id = ? or child_id = ?");
            links.setString(1, taskId);
            links.setString(2, taskId);
            links.executeUpdate();
            links.close();
            PreparedStatement task =
                    connection.prepareStatement("delete from kanban_tasks where task_id = ?");
            task.setString(1, taskId);
            task.executeUpdate();
            task.close();
        }
    }

    @Override
    public KanbanCommentRecord addComment(KanbanCommentRecord comment) throws Exception {
        if (comment == null || StrUtil.isBlank(comment.getTaskId())) {
            throw new IllegalArgumentException("task id is required");
        }
        String body = StrUtil.nullToEmpty(comment.getBody()).trim();
        if (StrUtil.isBlank(body)) {
            throw new IllegalArgumentException("comment body is required");
        }
        KanbanTaskRecord task = findTask(comment.getTaskId());
        if (task == null) {
            throw new IllegalArgumentException("Kanban task not found: " + comment.getTaskId());
        }
        if (StrUtil.isBlank(comment.getCommentId())) {
            comment.setCommentId("comment_" + IdSupport.newId());
        }
        comment.setTaskId(task.getTaskId());
        comment.setAuthor(StrUtil.blankToDefault(comment.getAuthor(), "user"));
        comment.setBody(body);
        if (comment.getCreatedAt() <= 0) {
            comment.setCreatedAt(System.currentTimeMillis());
        }
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into kanban_comments (comment_id, task_id, author, body, created_at) values (?, ?, ?, ?, ?)");
            statement.setString(1, comment.getCommentId());
            statement.setString(2, comment.getTaskId());
            statement.setString(3, comment.getAuthor());
            statement.setString(4, comment.getBody());
            statement.setLong(5, comment.getCreatedAt());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
        return comment;
    }

    @Override
    public List<KanbanCommentRecord> listComments(String taskId) throws Exception {
        List<KanbanCommentRecord> comments = new ArrayList<KanbanCommentRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from kanban_comments where task_id = ? order by created_at asc");
            statement.setString(1, taskId);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    comments.add(mapComment(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return comments;
    }

    @Override
    public KanbanEventRecord addEvent(KanbanEventRecord event) throws Exception {
        if (event == null || StrUtil.hasBlank(event.getTaskId(), event.getKind())) {
            throw new IllegalArgumentException("task id and event kind are required");
        }
        if (StrUtil.isBlank(event.getEventId())) {
            event.setEventId("event_" + IdSupport.newId());
        }
        if (event.getCreatedAt() <= 0) {
            event.setCreatedAt(System.currentTimeMillis());
        }
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into kanban_events (event_id, task_id, kind, payload_json, created_at) values (?, ?, ?, ?, ?)");
            statement.setString(1, event.getEventId());
            statement.setString(2, event.getTaskId());
            statement.setString(3, event.getKind());
            statement.setString(4, redact(event.getPayloadJson(), 8000));
            statement.setLong(5, event.getCreatedAt());
            statement.executeUpdate();
            statement.close();
            return event;
        } finally {
            connection.close();
        }
    }

    @Override
    public KanbanRunRecord addRun(KanbanRunRecord run) throws Exception {
        if (run == null || StrUtil.isBlank(run.getTaskId())) {
            throw new IllegalArgumentException("task id is required");
        }
        if (StrUtil.isBlank(run.getRunId())) {
            run.setRunId("run_" + IdSupport.newId());
        }
        if (StrUtil.isBlank(run.getStatus())) {
            run.setStatus("running");
        }
        if (run.getStartedAt() <= 0) {
            run.setStartedAt(System.currentTimeMillis());
        }
        Connection connection = database.openConnection();
        try {
            insertRun(run, connection);
            if (run.getEndedAt() <= 0) {
                PreparedStatement update =
                        connection.prepareStatement(
                                "update kanban_tasks set current_run_id = ? where task_id = ?");
                update.setString(1, run.getRunId());
                update.setString(2, run.getTaskId());
                update.executeUpdate();
                update.close();
            }
            return run;
        } finally {
            connection.close();
        }
    }

    @Override
    public List<KanbanRunRecord> listRuns(String taskId, boolean includeActive) throws Exception {
        List<KanbanRunRecord> runs = new ArrayList<KanbanRunRecord>();
        Connection connection = database.openConnection();
        try {
            String sql =
                    includeActive
                            ? "select * from kanban_runs where task_id = ? order by started_at asc, run_id asc"
                            : "select * from kanban_runs where task_id = ? and ended_at > 0 order by started_at asc, run_id asc";
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, taskId);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    runs.add(mapRun(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return runs;
    }

    @Override
    public KanbanRunRecord latestRun(String taskId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from kanban_runs where task_id = ? order by started_at desc, rowid desc limit 1");
            statement.setString(1, taskId);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? mapRun(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    @Override
    public String latestSummary(String taskId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select summary from kanban_runs where task_id = ? and summary is not null and summary <> '' order by case when ended_at > 0 then ended_at else started_at end desc, rowid desc limit 1");
            statement.setString(1, taskId);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? resultSet.getString("summary") : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    @Override
    public Map<String, String> latestSummaries(List<String> taskIds) throws Exception {
        Map<String, String> summaries = new LinkedHashMap<String, String>();
        if (taskIds == null || taskIds.isEmpty()) {
            return summaries;
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < taskIds.size(); i++) {
            if (i > 0) {
                placeholders.append(", ");
            }
            placeholders.append('?');
        }
        String sql =
                "select task_id, summary from ("
                        + "select task_id, summary, row_number() over (partition by task_id order by case when ended_at > 0 then ended_at else started_at end desc, rowid desc) as rn "
                        + "from kanban_runs where task_id in ("
                        + placeholders
                        + ") and summary is not null and summary <> '') where rn = 1";
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            for (int i = 0; i < taskIds.size(); i++) {
                statement.setString(i + 1, taskIds.get(i));
            }
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    summaries.put(resultSet.getString("task_id"), resultSet.getString("summary"));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return summaries;
    }

    @Override
    public KanbanRunRecord activeRun(String taskId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from kanban_runs where task_id = ? and ended_at = 0 order by started_at desc, run_id desc limit 1");
            statement.setString(1, taskId);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? mapRun(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean closeActiveRun(
            String taskId,
            String status,
            String outcome,
            String summary,
            String metadataJson,
            String error)
            throws Exception {
        KanbanTaskRecord task = findTask(taskId);
        if (task == null) {
            return false;
        }
        Connection connection = database.openConnection();
        try {
            boolean closed =
                    closeRunById(
                            task.getCurrentRunId(),
                            status,
                            outcome,
                            summary,
                            metadataJson,
                            error,
                            System.currentTimeMillis(),
                            connection);
            PreparedStatement update =
                    connection.prepareStatement(
                            "update kanban_tasks set current_run_id = null where task_id = ?");
            update.setString(1, taskId);
            update.executeUpdate();
            update.close();
            return closed;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean updateLatestRun(String taskId, String summary, String metadataJson, String error)
            throws Exception {
        KanbanRunRecord run = latestRun(taskId);
        if (run == null) {
            return false;
        }
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_runs set summary = coalesce(?, summary), metadata_json = coalesce(?, metadata_json), error = coalesce(?, error) where run_id = ?");
            statement.setString(1, redact(summary, 8000));
            statement.setString(2, redact(metadataJson, 8000));
            statement.setString(3, redact(error, 4000));
            statement.setString(4, run.getRunId());
            int updated = statement.executeUpdate();
            statement.close();
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean editCompletedTaskResult(String taskId, String result, String summary, String metadataJson)
            throws Exception {
        String handoffSummary = summary == null ? result : summary;
        long now = System.currentTimeMillis();
        Connection connection = database.openConnection();
        try {
            KanbanTaskRecord task = findTask(taskId);
            if (task == null || !"done".equals(task.getStatus())) {
                return false;
            }
            PreparedStatement updateTask =
                    connection.prepareStatement(
                            "update kanban_tasks set result = ?, updated_at = ? where task_id = ? and status = 'done'");
            updateTask.setString(1, result);
            updateTask.setLong(2, now);
            updateTask.setString(3, taskId);
            int updated = updateTask.executeUpdate();
            updateTask.close();
            if (updated != 1) {
                return false;
            }
            String runId = latestCompletedRunId(taskId, connection);
            if (StrUtil.isBlank(runId)) {
                KanbanRunRecord run = new KanbanRunRecord();
                run.setRunId("run_" + IdSupport.newId());
                run.setTaskId(taskId);
                run.setProfile(task.getAssignee());
                run.setStepKey(task.getCurrentStepKey());
                run.setStatus("done");
                run.setOutcome("completed");
                run.setSummary(handoffSummary);
                run.setMetadataJson(metadataJson);
                run.setStartedAt(now);
                run.setEndedAt(now);
                insertRun(run, connection);
                runId = run.getRunId();
            } else {
                PreparedStatement updateRun =
                        metadataJson == null
                                ? connection.prepareStatement(
                                        "update kanban_runs set summary = ? where run_id = ?")
                                : connection.prepareStatement(
                                        "update kanban_runs set summary = ?, metadata_json = ? where run_id = ?");
                updateRun.setString(1, redact(handoffSummary, 8000));
                if (metadataJson == null) {
                    updateRun.setString(2, runId);
                } else {
                    updateRun.setString(2, redact(metadataJson, 8000));
                    updateRun.setString(3, runId);
                }
                updateRun.executeUpdate();
                updateRun.close();
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            List<String> fields = new ArrayList<String>();
            fields.add("result");
            fields.add("summary");
            if (metadataJson != null) {
                fields.add("metadata");
            }
            payload.put("fields", fields);
            payload.put("result_len", Integer.valueOf(StrUtil.nullToEmpty(result).length()));
            payload.put("summary", summaryPreview(handoffSummary));
            payload.put("run_id", runId);
            addEvent(connection, taskId, "edited", payload);
            return true;
        } finally {
            connection.close();
        }
    }

    @Override
    public List<KanbanEventRecord> listEvents(String taskId) throws Exception {
        List<KanbanEventRecord> events = new ArrayList<KanbanEventRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from kanban_events where task_id = ? order by created_at asc");
            statement.setString(1, taskId);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    events.add(mapEvent(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return events;
    }

    @Override
    public List<KanbanEventRecord> listRecentEvents(int limit) throws Exception {
        int bounded = Math.max(1, Math.min(limit <= 0 ? 200 : limit, 500));
        List<KanbanEventRecord> events = new ArrayList<KanbanEventRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from kanban_events order by created_at desc limit ?");
            statement.setInt(1, bounded);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    events.add(0, mapEvent(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return events;
    }

    @Override
    public int deleteEventsOlderThan(long cutoffMillis) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("delete from kanban_events where created_at < ?");
            statement.setLong(1, cutoffMillis);
            int deleted = statement.executeUpdate();
            statement.close();
            return deleted;
        } finally {
            connection.close();
        }
    }

    @Override
    public KanbanNotifySubscriptionRecord saveNotifySubscription(
            KanbanNotifySubscriptionRecord subscription) throws Exception {
        if (subscription == null
                || StrUtil.hasBlank(subscription.getTaskId(), subscription.getPlatform(), subscription.getChatId())) {
            throw new IllegalArgumentException("task_id, platform and chat_id are required");
        }
        if (findTask(subscription.getTaskId()) == null) {
            throw new IllegalArgumentException("Kanban task not found: " + subscription.getTaskId());
        }
        long now = System.currentTimeMillis();
        if (StrUtil.isBlank(subscription.getSubscriptionId())) {
            subscription.setSubscriptionId("ksub_" + IdSupport.newId());
        }
        if (subscription.getCreatedAt() <= 0) {
            subscription.setCreatedAt(now);
        }
        subscription.setUpdatedAt(now);
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into kanban_notify_subscriptions (subscription_id, task_id, platform, chat_id, thread_id, user_id, last_event_id, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, coalesce((select created_at from kanban_notify_subscriptions where task_id = ? and platform = ? and chat_id = ? and coalesce(thread_id, '') = coalesce(?, '')), ?), ?)");
            statement.setString(1, subscription.getSubscriptionId());
            statement.setString(2, subscription.getTaskId());
            statement.setString(3, subscription.getPlatform());
            statement.setString(4, subscription.getChatId());
            statement.setString(5, subscription.getThreadId());
            statement.setString(6, subscription.getUserId());
            statement.setString(7, subscription.getLastEventId());
            statement.setString(8, subscription.getTaskId());
            statement.setString(9, subscription.getPlatform());
            statement.setString(10, subscription.getChatId());
            statement.setString(11, subscription.getThreadId());
            statement.setLong(12, subscription.getCreatedAt());
            statement.setLong(13, subscription.getUpdatedAt());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
        List<KanbanNotifySubscriptionRecord> records = listNotifySubscriptions(subscription.getTaskId());
        for (KanbanNotifySubscriptionRecord record : records) {
            if (StrUtil.equals(record.getPlatform(), subscription.getPlatform())
                    && StrUtil.equals(record.getChatId(), subscription.getChatId())
                    && StrUtil.equals(StrUtil.nullToEmpty(record.getThreadId()), StrUtil.nullToEmpty(subscription.getThreadId()))) {
                return record;
            }
        }
        return subscription;
    }

    @Override
    public List<KanbanNotifySubscriptionRecord> listNotifySubscriptions(String taskId)
            throws Exception {
        List<KanbanNotifySubscriptionRecord> subscriptions =
                new ArrayList<KanbanNotifySubscriptionRecord>();
        StringBuilder sql =
                new StringBuilder("select * from kanban_notify_subscriptions");
        if (StrUtil.isNotBlank(taskId)) {
            sql.append(" where task_id = ?");
        }
        sql.append(" order by updated_at desc");
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement = connection.prepareStatement(sql.toString());
            if (StrUtil.isNotBlank(taskId)) {
                statement.setString(1, taskId);
            }
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    subscriptions.add(mapNotifySubscription(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return subscriptions;
    }

    @Override
    public KanbanNotifyClaim claimNotifyEvents(
            String taskId, String platform, String chatId, String threadId, List<String> kinds)
            throws Exception {
        Connection connection = database.openConnection();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            KanbanNotifySubscriptionRecord subscription =
                    findNotifySubscription(connection, taskId, platform, chatId, threadId);
            KanbanNotifyClaim claim = new KanbanNotifyClaim();
            if (subscription == null) {
                claim.setOldCursor("0");
                claim.setNewCursor("0");
                connection.commit();
                return claim;
            }
            String oldCursor = normalizeCursor(subscription.getLastEventId());
            List<KanbanEventRecord> events = listNotifyEventsAfter(connection, taskId, oldCursor, kinds);
            String newCursor = oldCursor;
            for (KanbanEventRecord event : events) {
                newCursor = maxCursor(newCursor, event.getNotifyCursor());
            }
            claim.setOldCursor(oldCursor);
            claim.setNewCursor(newCursor);
            if (!events.isEmpty()
                    && !updateNotifyCursor(connection, taskId, platform, chatId, threadId, oldCursor, newCursor)) {
                events = Collections.emptyList();
                newCursor = oldCursor;
                claim.setNewCursor(oldCursor);
            }
            claim.setEvents(events);
            connection.commit();
            return claim;
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
            connection.close();
        }
    }

    @Override
    public boolean advanceNotifyCursor(
            String taskId, String platform, String chatId, String threadId, String newCursor)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_notify_subscriptions set last_event_id = ?, updated_at = ? where task_id = ? and platform = ? and chat_id = ? and coalesce(thread_id, '') = coalesce(?, '')");
            statement.setString(1, normalizeCursor(newCursor));
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, taskId);
            statement.setString(4, platform);
            statement.setString(5, chatId);
            statement.setString(6, threadId);
            int updated = statement.executeUpdate();
            statement.close();
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean rewindNotifyCursor(
            String taskId,
            String platform,
            String chatId,
            String threadId,
            String claimedCursor,
            String oldCursor)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update kanban_notify_subscriptions set last_event_id = ?, updated_at = ? where task_id = ? and platform = ? and chat_id = ? and coalesce(thread_id, '') = coalesce(?, '') and coalesce(last_event_id, '0') = ?");
            statement.setString(1, normalizeCursor(oldCursor));
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, taskId);
            statement.setString(4, platform);
            statement.setString(5, chatId);
            statement.setString(6, threadId);
            statement.setString(7, normalizeCursor(claimedCursor));
            int updated = statement.executeUpdate();
            statement.close();
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean removeNotifySubscription(String taskId, String platform, String chatId, String threadId)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "delete from kanban_notify_subscriptions where task_id = ? and platform = ? and chat_id = ? and coalesce(thread_id, '') = coalesce(?, '')");
            statement.setString(1, taskId);
            statement.setString(2, platform);
            statement.setString(3, chatId);
            statement.setString(4, threadId);
            int deleted = statement.executeUpdate();
            statement.close();
            return deleted > 0;
        } finally {
            connection.close();
        }
    }

    private List<KanbanTaskRecord> listLinkedTasks(String sql, String taskId) throws Exception {
        List<KanbanTaskRecord> tasks = new ArrayList<KanbanTaskRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, taskId);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    tasks.add(mapTask(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return tasks;
    }

    private void ensureDefaultBoard() throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement count =
                    connection.prepareStatement("select count(*) from kanban_boards");
            ResultSet resultSet = count.executeQuery();
            boolean empty = !resultSet.next() || resultSet.getLong(1) == 0L;
            resultSet.close();
            count.close();
            if (!empty) {
                return;
            }
            long now = System.currentTimeMillis();
            PreparedStatement insert =
                    connection.prepareStatement(
                            "insert into kanban_boards (board_id, slug, name, description, color, current, archived, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)");
            insert.setString(1, "board_default");
            insert.setString(2, DEFAULT_BOARD);
            insert.setString(3, "默认看板");
            insert.setString(4, "本地协作任务看板");
            insert.setString(5, "#2563eb");
            insert.setInt(6, 1);
            insert.setInt(7, 0);
            insert.setLong(8, now);
            insert.setLong(9, now);
            insert.executeUpdate();
            insert.close();
        } finally {
            connection.close();
        }
    }

    private String normalizeBoard(String slug) {
        String value = StrUtil.blankToDefault(slug, DEFAULT_BOARD).trim().toLowerCase();
        if (!value.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException(
                    "invalid board slug: " + slug + ", use lowercase letters, digits, - or _");
        }
        return value;
    }

    private String normalizeStatus(String status) {
        String value = StrUtil.blankToDefault(status, "todo").trim().toLowerCase();
        if (!"triage".equals(value)
                && !"todo".equals(value)
                && !"scheduled".equals(value)
                && !"ready".equals(value)
                && !"review".equals(value)
                && !"running".equals(value)
                && !"blocked".equals(value)
                && !"done".equals(value)
                && !"archived".equals(value)) {
            throw new IllegalArgumentException("invalid kanban status: " + status);
        }
        return value;
    }

    private String normalizeWorkspaceKind(String workspaceKind) {
        String value = StrUtil.blankToDefault(workspaceKind, "scratch").trim().toLowerCase();
        if (!"scratch".equals(value) && !"dir".equals(value) && !"worktree".equals(value)) {
            throw new IllegalArgumentException("workspace_kind must be one of [scratch, dir, worktree]");
        }
        return value;
    }

    private KanbanBoardRecord mapBoard(ResultSet resultSet) throws Exception {
        KanbanBoardRecord record = new KanbanBoardRecord();
        record.setBoardId(resultSet.getString("board_id"));
        record.setSlug(resultSet.getString("slug"));
        record.setName(resultSet.getString("name"));
        record.setDescription(resultSet.getString("description"));
        record.setColor(resultSet.getString("color"));
        record.setCurrent(resultSet.getInt("current") == 1);
        record.setArchived(resultSet.getInt("archived") == 1);
        record.setCreatedAt(resultSet.getLong("created_at"));
        record.setUpdatedAt(resultSet.getLong("updated_at"));
        return record;
    }

    private KanbanNotifySubscriptionRecord findNotifySubscription(
            Connection connection, String taskId, String platform, String chatId, String threadId)
            throws Exception {
        PreparedStatement statement =
                connection.prepareStatement(
                        "select * from kanban_notify_subscriptions where task_id = ? and platform = ? and chat_id = ? and coalesce(thread_id, '') = coalesce(?, '')");
        statement.setString(1, taskId);
        statement.setString(2, platform);
        statement.setString(3, chatId);
        statement.setString(4, threadId);
        ResultSet resultSet = statement.executeQuery();
        try {
            return resultSet.next() ? mapNotifySubscription(resultSet) : null;
        } finally {
            resultSet.close();
            statement.close();
        }
    }

    private List<KanbanEventRecord> listNotifyEventsAfter(
            Connection connection, String taskId, String cursor, List<String> kinds)
            throws Exception {
        StringBuilder sql =
                new StringBuilder(
                        "select rowid as notify_cursor, * from kanban_events where task_id = ? and rowid > ?");
        List<String> normalizedKinds = normalizedKinds(kinds);
        if (!normalizedKinds.isEmpty()) {
            sql.append(" and kind in (");
            for (int i = 0; i < normalizedKinds.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
            }
            sql.append(")");
        }
        sql.append(" order by rowid asc");
        PreparedStatement statement = connection.prepareStatement(sql.toString());
        statement.setString(1, taskId);
        statement.setLong(2, parseCursor(cursor));
        for (int i = 0; i < normalizedKinds.size(); i++) {
            statement.setString(i + 3, normalizedKinds.get(i));
        }
        ResultSet resultSet = statement.executeQuery();
        List<KanbanEventRecord> events = new ArrayList<KanbanEventRecord>();
        try {
            while (resultSet.next()) {
                KanbanEventRecord event = mapEvent(resultSet);
                event.setNotifyCursor(String.valueOf(resultSet.getLong("notify_cursor")));
                events.add(event);
            }
        } finally {
            resultSet.close();
            statement.close();
        }
        return events;
    }

    private boolean updateNotifyCursor(
            Connection connection,
            String taskId,
            String platform,
            String chatId,
            String threadId,
            String oldCursor,
            String newCursor)
            throws Exception {
        PreparedStatement statement =
                connection.prepareStatement(
                        "update kanban_notify_subscriptions set last_event_id = ?, updated_at = ? where task_id = ? and platform = ? and chat_id = ? and coalesce(thread_id, '') = coalesce(?, '') and coalesce(last_event_id, '0') = ?");
        statement.setString(1, normalizeCursor(newCursor));
        statement.setLong(2, System.currentTimeMillis());
        statement.setString(3, taskId);
        statement.setString(4, platform);
        statement.setString(5, chatId);
        statement.setString(6, threadId);
        statement.setString(7, normalizeCursor(oldCursor));
        int updated = statement.executeUpdate();
        statement.close();
        return updated > 0;
    }

    private List<String> normalizedKinds(List<String> kinds) {
        if (kinds == null || kinds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String kind : kinds) {
            String normalized = StrUtil.nullToEmpty(kind).trim();
            if (StrUtil.isNotBlank(normalized) && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private String maxCursor(String current, String candidate) {
        long currentValue = parseCursor(current);
        long candidateValue = parseCursor(candidate);
        return String.valueOf(Math.max(currentValue, candidateValue));
    }

    private String normalizeCursor(String cursor) {
        return String.valueOf(parseCursor(cursor));
    }

    private long parseCursor(String cursor) {
        try {
            return Long.parseLong(StrUtil.blankToDefault(cursor, "0"));
        } catch (Exception e) {
            return 0L;
        }
    }

    private KanbanTaskRecord mapTask(ResultSet resultSet) throws Exception {
        KanbanTaskRecord record = new KanbanTaskRecord();
        record.setTaskId(resultSet.getString("task_id"));
        record.setBoardSlug(resultSet.getString("board_slug"));
        record.setTitle(resultSet.getString("title"));
        record.setBody(resultSet.getString("body"));
        record.setAssignee(resultSet.getString("assignee"));
        record.setStatus(resultSet.getString("status"));
        record.setPriority(resultSet.getInt("priority"));
        record.setTenant(resultSet.getString("tenant"));
        record.setSessionId(resultSet.getString("session_id"));
        record.setWorkspaceKind(resultSet.getString("workspace_kind"));
        record.setWorkspacePath(resultSet.getString("workspace_path"));
        record.setBranchName(resultSet.getString("branch_name"));
        record.setCreatedBy(resultSet.getString("created_by"));
        record.setResult(resultSet.getString("result"));
        record.setIdempotencyKey(resultSet.getString("idempotency_key"));
        record.setClaimLock(resultSet.getString("claim_lock"));
        record.setClaimExpiresAt(resultSet.getLong("claim_expires_at"));
        record.setWorkerId(resultSet.getString("worker_id"));
        record.setWorkerPid(resultSet.getLong("worker_pid"));
        record.setLastSpawnError(resultSet.getString("last_spawn_error"));
        record.setSpawnFailures(resultSet.getInt("spawn_failures"));
        int maxRetries = resultSet.getInt("max_retries");
        record.setMaxRetries(resultSet.wasNull() ? null : Integer.valueOf(maxRetries));
        record.setMaxRuntimeSeconds(resultSet.getLong("max_runtime_seconds"));
        record.setLastHeartbeatAt(resultSet.getLong("last_heartbeat_at"));
        record.setCurrentRunId(resultSet.getString("current_run_id"));
        record.setWorkflowTemplateId(resultSet.getString("workflow_template_id"));
        record.setCurrentStepKey(resultSet.getString("current_step_key"));
        record.setSkillsJson(resultSet.getString("skills_json"));
        record.setCreatedAt(resultSet.getLong("created_at"));
        record.setUpdatedAt(resultSet.getLong("updated_at"));
        record.setStartedAt(resultSet.getLong("started_at"));
        record.setCompletedAt(resultSet.getLong("completed_at"));
        return record;
    }

    private KanbanCommentRecord mapComment(ResultSet resultSet) throws Exception {
        KanbanCommentRecord record = new KanbanCommentRecord();
        record.setCommentId(resultSet.getString("comment_id"));
        record.setTaskId(resultSet.getString("task_id"));
        record.setAuthor(resultSet.getString("author"));
        record.setBody(resultSet.getString("body"));
        record.setCreatedAt(resultSet.getLong("created_at"));
        return record;
    }

    private KanbanEventRecord mapEvent(ResultSet resultSet) throws Exception {
        KanbanEventRecord record = new KanbanEventRecord();
        record.setEventId(resultSet.getString("event_id"));
        record.setTaskId(resultSet.getString("task_id"));
        record.setKind(resultSet.getString("kind"));
        record.setPayloadJson(resultSet.getString("payload_json"));
        record.setCreatedAt(resultSet.getLong("created_at"));
        return record;
    }

    private KanbanRunRecord mapRun(ResultSet resultSet) throws Exception {
        KanbanRunRecord record = new KanbanRunRecord();
        record.setRunId(resultSet.getString("run_id"));
        record.setTaskId(resultSet.getString("task_id"));
        record.setProfile(resultSet.getString("profile"));
        record.setStepKey(resultSet.getString("step_key"));
        record.setStatus(resultSet.getString("status"));
        record.setClaimLock(resultSet.getString("claim_lock"));
        record.setClaimExpiresAt(resultSet.getLong("claim_expires_at"));
        record.setWorkerPid(resultSet.getLong("worker_pid"));
        record.setWorkerId(resultSet.getString("worker_id"));
        record.setMaxRuntimeSeconds(resultSet.getLong("max_runtime_seconds"));
        record.setLastHeartbeatAt(resultSet.getLong("last_heartbeat_at"));
        record.setStartedAt(resultSet.getLong("started_at"));
        record.setEndedAt(resultSet.getLong("ended_at"));
        record.setOutcome(resultSet.getString("outcome"));
        record.setSummary(resultSet.getString("summary"));
        record.setMetadataJson(resultSet.getString("metadata_json"));
        record.setError(resultSet.getString("error"));
        return record;
    }

    private KanbanNotifySubscriptionRecord mapNotifySubscription(ResultSet resultSet)
            throws Exception {
        KanbanNotifySubscriptionRecord record = new KanbanNotifySubscriptionRecord();
        record.setSubscriptionId(resultSet.getString("subscription_id"));
        record.setTaskId(resultSet.getString("task_id"));
        record.setPlatform(resultSet.getString("platform"));
        record.setChatId(resultSet.getString("chat_id"));
        record.setThreadId(resultSet.getString("thread_id"));
        record.setUserId(resultSet.getString("user_id"));
        record.setLastEventId(resultSet.getString("last_event_id"));
        record.setCreatedAt(resultSet.getLong("created_at"));
        record.setUpdatedAt(resultSet.getLong("updated_at"));
        return record;
    }

    private void ensureActiveRun(KanbanTaskRecord task, long now, Connection connection)
            throws Exception {
        if (task == null || StrUtil.isNotBlank(task.getCurrentRunId())) {
            return;
        }
        KanbanRunRecord run = new KanbanRunRecord();
        run.setRunId("run_" + IdSupport.newId());
        run.setTaskId(task.getTaskId());
        run.setProfile(task.getAssignee());
        run.setStepKey(task.getCurrentStepKey());
        run.setStatus("running");
        run.setClaimLock(task.getClaimLock());
        run.setClaimExpiresAt(task.getClaimExpiresAt());
        run.setWorkerId(task.getWorkerId());
        run.setWorkerPid(task.getWorkerPid());
        run.setMaxRuntimeSeconds(task.getMaxRuntimeSeconds());
        run.setLastHeartbeatAt(task.getLastHeartbeatAt());
        run.setStartedAt(now);
        insertRun(run, connection);
        PreparedStatement update =
                connection.prepareStatement(
                        "update kanban_tasks set current_run_id = ? where task_id = ?");
        update.setString(1, run.getRunId());
        update.setString(2, task.getTaskId());
        update.executeUpdate();
        update.close();
    }

    private void closeLeakedReadyRun(KanbanTaskRecord task, long now, Connection connection)
            throws Exception {
        if (task == null
                || !"ready".equals(task.getStatus())
                || StrUtil.isBlank(task.getCurrentRunId())) {
            return;
        }
        closeRunById(
                task.getCurrentRunId(),
                "reclaimed",
                "reclaimed",
                "invariant recovery on re-claim",
                null,
                null,
                now,
                connection);
        PreparedStatement update =
                connection.prepareStatement(
                        "update kanban_tasks set current_run_id = null where task_id = ?");
        update.setString(1, task.getTaskId());
        update.executeUpdate();
        update.close();
    }

    private void syncActiveRunRuntime(KanbanTaskRecord task, Connection connection) throws Exception {
        if (task == null || StrUtil.isBlank(task.getCurrentRunId())) {
            return;
        }
        PreparedStatement statement =
                connection.prepareStatement(
                        "update kanban_runs set claim_lock = ?, claim_expires_at = ?, worker_pid = ?, worker_id = ?, max_runtime_seconds = ?, last_heartbeat_at = ? where run_id = ? and ended_at = 0");
        statement.setString(1, task.getClaimLock());
        statement.setLong(2, task.getClaimExpiresAt());
        statement.setLong(3, task.getWorkerPid());
        statement.setString(4, task.getWorkerId());
        statement.setLong(5, task.getMaxRuntimeSeconds());
        statement.setLong(6, task.getLastHeartbeatAt());
        statement.setString(7, task.getCurrentRunId());
        statement.executeUpdate();
        statement.close();
    }

    private void updateActiveRunError(KanbanTaskRecord task, String error, Connection connection)
            throws Exception {
        if (task == null || StrUtil.isBlank(task.getCurrentRunId())) {
            return;
        }
        PreparedStatement statement =
                connection.prepareStatement(
                        "update kanban_runs set error = coalesce(?, error), worker_pid = 0 where run_id = ? and ended_at = 0");
        statement.setString(1, redact(error, 4000));
        statement.setString(2, task.getCurrentRunId());
        statement.executeUpdate();
        statement.close();
    }

    private boolean reclaimRunningTask(
            Connection connection,
            KanbanTaskRecord task,
            String runStatus,
            String outcome,
            String error,
            String eventKind,
            boolean ready,
            long now)
            throws Exception {
        if (task == null || !"running".equals(task.getStatus())) {
            return false;
        }
        PreparedStatement statement =
                connection.prepareStatement(
                        "update kanban_tasks set status = ?, claim_lock = null, claim_expires_at = 0, worker_id = null, worker_pid = 0, current_run_id = null, updated_at = ? where task_id = ? and status = 'running'");
        statement.setString(1, ready ? "ready" : task.getStatus());
        statement.setLong(2, now);
        statement.setString(3, task.getTaskId());
        int updated = statement.executeUpdate();
        statement.close();
        if (updated != 1) {
            return false;
        }
        closeRunById(task.getCurrentRunId(), runStatus, outcome, error, null, error, now, connection);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("prev_lock", task.getClaimLock());
        payload.put("prev_worker", task.getWorkerId());
        payload.put("claim_expires_at", Long.valueOf(task.getClaimExpiresAt()));
        payload.put("last_heartbeat_at", Long.valueOf(Math.max(0L, task.getLastHeartbeatAt())));
        payload.put("worker_pid", Long.valueOf(Math.max(0L, task.getWorkerPid())));
        payload.put("error", error);
        addEvent(connection, task.getTaskId(), eventKind, payload);
        return true;
    }

    private Map<String, Object> claimedPayload(KanbanTaskRecord task) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (task == null) {
            return payload;
        }
        payload.put("claim_lock", task.getClaimLock());
        payload.put("claim_expires_at", Long.valueOf(task.getClaimExpiresAt()));
        payload.put("worker_id", task.getWorkerId());
        payload.put("worker_pid", task.getWorkerPid() <= 0 ? null : Long.valueOf(task.getWorkerPid()));
        return payload;
    }

    private void closeOrSynthesizeRun(
            KanbanTaskRecord task,
            String status,
            String outcome,
            String summary,
            String metadataJson,
            String error,
            long now,
            Connection connection)
            throws Exception {
        if (task == null) {
            return;
        }
        boolean closed =
                closeRunById(
                        task.getCurrentRunId(), status, outcome, summary, metadataJson, error, now, connection);
        if (!closed) {
            KanbanRunRecord run = new KanbanRunRecord();
            run.setRunId("run_" + IdSupport.newId());
            run.setTaskId(task.getTaskId());
            run.setProfile(task.getAssignee());
            run.setStepKey(task.getCurrentStepKey());
            run.setStatus(status);
            run.setOutcome(outcome);
            run.setSummary(summary);
            run.setMetadataJson(metadataJson);
            run.setError(error);
            run.setStartedAt(now);
            run.setEndedAt(now);
            insertRun(run, connection);
        }
        PreparedStatement update =
                connection.prepareStatement(
                        "update kanban_tasks set current_run_id = null where task_id = ?");
        update.setString(1, task.getTaskId());
        update.executeUpdate();
        update.close();
    }

    private boolean closeRunById(
            String runId,
            String status,
            String outcome,
            String summary,
            String metadataJson,
            String error,
            long now,
            Connection connection)
            throws Exception {
        if (StrUtil.isBlank(runId)) {
            return false;
        }
        PreparedStatement statement =
                connection.prepareStatement(
                        "update kanban_runs set status = ?, outcome = ?, summary = coalesce(?, summary), metadata_json = coalesce(?, metadata_json), error = coalesce(?, error), ended_at = case when ended_at = 0 then ? else ended_at end, claim_lock = null, claim_expires_at = 0 where run_id = ? and ended_at = 0");
        statement.setString(1, status);
        statement.setString(2, outcome);
        statement.setString(3, redact(summary, 8000));
        statement.setString(4, redact(metadataJson, 8000));
        statement.setString(5, redact(error, 4000));
        statement.setLong(6, now);
        statement.setString(7, runId);
        int updated = statement.executeUpdate();
        statement.close();
        return updated > 0;
    }

    private String latestCompletedRunId(String taskId, Connection connection) throws Exception {
        PreparedStatement statement =
                connection.prepareStatement(
                        "select run_id from kanban_runs where task_id = ? and outcome = 'completed' order by case when ended_at > 0 then ended_at else started_at end desc, run_id desc limit 1");
        statement.setString(1, taskId);
        ResultSet resultSet = statement.executeQuery();
        try {
            return resultSet.next() ? resultSet.getString("run_id") : null;
        } finally {
            resultSet.close();
            statement.close();
        }
    }

    private void insertRun(KanbanRunRecord run, Connection connection) throws Exception {
        PreparedStatement statement =
                connection.prepareStatement(
                        "insert or replace into kanban_runs (run_id, task_id, profile, step_key, status, claim_lock, claim_expires_at, worker_pid, worker_id, max_runtime_seconds, last_heartbeat_at, started_at, ended_at, outcome, summary, metadata_json, error) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        statement.setString(1, run.getRunId());
        statement.setString(2, run.getTaskId());
        statement.setString(3, run.getProfile());
        statement.setString(4, run.getStepKey());
        statement.setString(5, run.getStatus());
        statement.setString(6, run.getClaimLock());
        statement.setLong(7, run.getClaimExpiresAt());
        statement.setLong(8, run.getWorkerPid());
        statement.setString(9, run.getWorkerId());
        statement.setLong(10, run.getMaxRuntimeSeconds());
        statement.setLong(11, run.getLastHeartbeatAt());
        statement.setLong(12, run.getStartedAt());
        statement.setLong(13, run.getEndedAt());
        statement.setString(14, run.getOutcome());
        statement.setString(15, redact(run.getSummary(), 8000));
        statement.setString(16, redact(run.getMetadataJson(), 8000));
        statement.setString(17, redact(run.getError(), 4000));
        statement.executeUpdate();
        statement.close();
    }

    private String summaryPreview(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.length() <= 400) {
            return StrUtil.blankToDefault(text, null);
        }
        return text.substring(0, 400);
    }

    private void addEvent(
            Connection connection, String taskId, String kind, Map<String, Object> payload)
            throws Exception {
        PreparedStatement statement =
                connection.prepareStatement(
                        "insert into kanban_events (event_id, task_id, kind, payload_json, created_at) values (?, ?, ?, ?, ?)");
        statement.setString(1, "event_" + IdSupport.newId());
        statement.setString(2, taskId);
        statement.setString(3, kind);
        statement.setString(4, redact(payload == null ? null : ONode.serialize(payload), 8000));
        statement.setLong(5, System.currentTimeMillis());
        statement.executeUpdate();
        statement.close();
    }

    private String redact(String value, int maxLength) {
        return value == null ? null : SecretRedactor.redact(value, maxLength);
    }
}
