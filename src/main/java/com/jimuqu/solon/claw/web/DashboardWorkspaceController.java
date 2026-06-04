package com.jimuqu.solon.claw.web;

import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 人格工作区文件接口。 */
@Controller
public class DashboardWorkspaceController {
    private final DashboardWorkspaceService workspaceService;

    public DashboardWorkspaceController(DashboardWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Mapping(value = "/api/workspace/files", method = MethodType.GET)
    public Map<String, Object> files(Context context) {
        return execute(
                context,
                new Callback() {
                    @Override
                    public Map<String, Object> run() {
                        return workspaceService.getFiles();
                    }
                });
    }

    @Mapping(value = "/api/workspace/files/{key}", method = MethodType.GET)
    public Map<String, Object> file(Context context, final String key) {
        return execute(
                context,
                new Callback() {
                    @Override
                    public Map<String, Object> run() {
                        return workspaceService.getFile(key);
                    }
                });
    }

    @Mapping(value = "/api/workspace/files/{key}", method = MethodType.PUT)
    public Map<String, Object> save(Context context, final String key) throws Exception {
        return execute(
                context,
                new Callback() {
                    @Override
                    public Map<String, Object> run() {
                        String content = content(context);
                        return workspaceService.saveFile(key, content);
                    }
                });
    }

    @Mapping(value = "/api/workspace/files/{key}/restore", method = MethodType.POST)
    public Map<String, Object> restore(Context context, final String key) {
        return execute(
                context,
                new Callback() {
                    @Override
                    public Map<String, Object> run() {
                        return workspaceService.restoreFile(key);
                    }
                });
    }

    @Mapping(value = "/api/workspace/diaries", method = MethodType.GET)
    public Map<String, Object> diaries(Context context) {
        return execute(
                context,
                new Callback() {
                    @Override
                    public Map<String, Object> run() {
                        return workspaceService.listDiaryFiles();
                    }
                });
    }

    @Mapping(value = "/api/workspace/diaries/read", method = MethodType.GET)
    public Map<String, Object> diary(Context context) {
        final String relativePath = context.param("path");
        return execute(
                context,
                new Callback() {
                    @Override
                    public Map<String, Object> run() {
                        return workspaceService.getDiaryFile(relativePath);
                    }
                });
    }

    private Map<String, Object> execute(Context context, Callback callback) {
        try {
            return callback.run();
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("WORKSPACE_BAD_REQUEST", workspaceErrorMessage(e));
        }
    }

    private String workspaceErrorMessage(IllegalArgumentException e) {
        String message = e.getMessage();
        if (message != null && message.startsWith("Diary file is not available:")) {
            return "Diary file is not available.";
        }
        return message;
    }

    private String content(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return "";
        }
        try {
            ONode node = ONode.ofJson(raw);
            if (node.toData() instanceof Map) {
                return node.get("content").getString();
            }
            throw new IllegalArgumentException(
                    "请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    private interface Callback {
        Map<String, Object> run();
    }
}
