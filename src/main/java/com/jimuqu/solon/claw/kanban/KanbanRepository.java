package com.jimuqu.solon.claw.kanban;

import java.util.List;

/** Repository for the shared Hermes-style Kanban board. */
public interface KanbanRepository {
    List<KanbanBoardRecord> listBoards() throws Exception;

    KanbanBoardRecord findBoard(String slug) throws Exception;

    KanbanBoardRecord currentBoard() throws Exception;

    KanbanBoardRecord saveBoard(KanbanBoardRecord board) throws Exception;

    void switchBoard(String slug) throws Exception;

    List<KanbanTaskRecord> listTasks(String boardSlug, String status, boolean includeArchived)
            throws Exception;

    KanbanTaskRecord findTask(String taskId) throws Exception;

    KanbanTaskRecord saveTask(KanbanTaskRecord task) throws Exception;

    boolean updateTaskStatus(String taskId, String status, String result) throws Exception;

    boolean assignTask(String taskId, String assignee) throws Exception;

    void deleteTask(String taskId) throws Exception;

    KanbanCommentRecord addComment(KanbanCommentRecord comment) throws Exception;

    List<KanbanCommentRecord> listComments(String taskId) throws Exception;
}
