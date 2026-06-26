package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardWorkspaceService;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供工作区只读查询工具，复用 Dashboard 人格工作区服务。 */
public class WorkspaceManageTools {
    /** Dashboard 工作区服务，用于读取受控文件和日记内容。 */
    private final DashboardWorkspaceService workspaceService;

    /**
     * 创建工作区查询工具。
     *
     * @param workspaceService Dashboard 工作区服务。
     */
    public WorkspaceManageTools(DashboardWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * 查询受控工作区文件或日记内容。
     *
     * @param action 操作名称。
     * @param key 工作区文件 key。
     * @param relativePath 日记相对路径。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "workspace_manage",
            description = "Inspect dashboard workspace files. Actions: files, file, diaries, diary.")
    public String workspaceManage(
            @Param(name = "action", description = "files, file, diaries, diary") String action,
            @Param(name = "key", required = false, description = "Workspace file key") String key,
            @Param(name = "relativePath", required = false, description = "Diary relative path")
                    String relativePath) {
        try {
            if (workspaceService == null) {
                return ToolResultEnvelope.error("workspace service unavailable").toJson();
            }
            Map<String, Object> result = run(action, key, relativePath);
            return ToolResultEnvelope.ok("工作区查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行工作区只读查询动作。
     *
     * @param action 操作名称。
     * @param key 工作区文件 key。
     * @param relativePath 日记相对路径。
     * @return 返回 Dashboard 工作区服务结果。
     */
    private Map<String, Object> run(String action, String key, String relativePath) {
        String normalized = action == null ? "files" : action.trim().toLowerCase(Locale.ROOT);
        if ("file".equals(normalized) || "get_file".equals(normalized)) {
            return workspaceService.getFile(key);
        }
        if ("diaries".equals(normalized) || "list_diaries".equals(normalized)) {
            return workspaceService.listDiaryFiles();
        }
        if ("diary".equals(normalized) || "get_diary".equals(normalized)) {
            return workspaceService.getDiaryFile(relativePath);
        }
        return workspaceService.getFiles();
    }
}
