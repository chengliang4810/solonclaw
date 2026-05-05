package com.jimuqu.solon.claw.storage.repository;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.kanban.KanbanBoardRecord;
import com.jimuqu.solon.claw.kanban.KanbanCommentRecord;
import com.jimuqu.solon.claw.kanban.KanbanRepository;
import com.jimuqu.solon.claw.kanban.KanbanTaskRecord;
import com.jimuqu.solon.claw.support.IdSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** SQLite-backed Kanban repository. */
@RequiredArgsConstructor
public class SqliteKanbanRepository implements KanbanRepository {
    public static final String DEFAULT_BOARD = "default";

    private final SqliteDatabase database;

    @Override
    public List<KanbanBoardRecord> listBoards() throws Exception {
        ensureDefaultBoard();
        List<KanbanBoardRecord> boards = new ArrayList<KanbanBoardRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from kanban_boards order by current desc, updated_at desc");
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
                    connection.prepareStatement("select * from kanban_boards where slug = ?");
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
                            "select * from kanban_boards where current = 1 order by updated_at desc limit 1");
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
                            "insert or replace into kanban_boards (board_id, slug, name, description, color, current, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, board.getBoardId());
            statement.setString(2, board.getSlug());
            statement.setString(3, board.getName());
            statement.setString(4, board.getDescription());
            statement.setString(5, board.getColor());
            statement.setInt(6, board.isCurrent() ? 1 : 0);
            statement.setLong(7, board.getCreatedAt());
            statement.setLong(8, board.getUpdatedAt());
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
        sql.append(" order by priority desc, updated_at desc");

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
    public KanbanTaskRecord findTask(String taskId) throws Exception {
        Connection connection = database.openConnection();
        try {
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
        if (StrUtil.isBlank(task.getTaskId())) {
            task.setTaskId("KB-" + IdSupport.newId().substring(0, 8).toUpperCase());
        }
        task.setBoardSlug(
                StrUtil.isBlank(task.getBoardSlug())
                        ? currentBoard().getSlug()
                        : normalizeBoard(task.getBoardSlug()));
        task.setStatus(normalizeStatus(StrUtil.blankToDefault(task.getStatus(), "todo")));
        task.setWorkspaceKind(StrUtil.blankToDefault(task.getWorkspaceKind(), "scratch"));
        task.setCreatedBy(StrUtil.blankToDefault(task.getCreatedBy(), "user"));
        if (task.getCreatedAt() <= 0) {
            task.setCreatedAt(now);
        }
        task.setUpdatedAt(now);

        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into kanban_tasks (task_id, board_slug, title, body, assignee, status, priority, tenant, workspace_kind, workspace_path, created_by, result, created_at, updated_at, started_at, completed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, task.getTaskId());
            statement.setString(2, task.getBoardSlug());
            statement.setString(3, task.getTitle());
            statement.setString(4, task.getBody());
            statement.setString(5, task.getAssignee());
            statement.setString(6, task.getStatus());
            statement.setInt(7, task.getPriority());
            statement.setString(8, task.getTenant());
            statement.setString(9, task.getWorkspaceKind());
            statement.setString(10, task.getWorkspacePath());
            statement.setString(11, task.getCreatedBy());
            statement.setString(12, task.getResult());
            statement.setLong(13, task.getCreatedAt());
            statement.setLong(14, task.getUpdatedAt());
            statement.setLong(15, task.getStartedAt());
            statement.setLong(16, task.getCompletedAt());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
        return task;
    }

    @Override
    public boolean updateTaskStatus(String taskId, String status, String result) throws Exception {
        String normalized = normalizeStatus(status);
        long now = System.currentTimeMillis();
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
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean assignTask(String taskId, String assignee) throws Exception {
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
            return updated > 0;
        } finally {
            connection.close();
        }
    }

    @Override
    public void deleteTask(String taskId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement comments =
                    connection.prepareStatement("delete from kanban_comments where task_id = ?");
            comments.setString(1, taskId);
            comments.executeUpdate();
            comments.close();
            PreparedStatement task =
                    connection.prepareStatement("delete from kanban_tasks where task_id = ?");
            task.setString(1, taskId);
            task.executeUpdate();
            task.close();
        } finally {
            connection.close();
        }
    }

    @Override
    public KanbanCommentRecord addComment(KanbanCommentRecord comment) throws Exception {
        if (comment == null || StrUtil.hasBlank(comment.getTaskId(), comment.getBody())) {
            throw new IllegalArgumentException("task id and comment body are required");
        }
        if (StrUtil.isBlank(comment.getCommentId())) {
            comment.setCommentId("comment_" + IdSupport.newId());
        }
        comment.setAuthor(StrUtil.blankToDefault(comment.getAuthor(), "user"));
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
                            "insert into kanban_boards (board_id, slug, name, description, color, current, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)");
            insert.setString(1, "board_default");
            insert.setString(2, DEFAULT_BOARD);
            insert.setString(3, "默认看板");
            insert.setString(4, "本地协作任务看板");
            insert.setString(5, "#2563eb");
            insert.setInt(6, 1);
            insert.setLong(7, now);
            insert.setLong(8, now);
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
                && !"ready".equals(value)
                && !"running".equals(value)
                && !"blocked".equals(value)
                && !"done".equals(value)
                && !"archived".equals(value)) {
            throw new IllegalArgumentException("invalid kanban status: " + status);
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
        record.setCreatedAt(resultSet.getLong("created_at"));
        record.setUpdatedAt(resultSet.getLong("updated_at"));
        return record;
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
        record.setWorkspaceKind(resultSet.getString("workspace_kind"));
        record.setWorkspacePath(resultSet.getString("workspace_path"));
        record.setCreatedBy(resultSet.getString("created_by"));
        record.setResult(resultSet.getString("result"));
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
}
