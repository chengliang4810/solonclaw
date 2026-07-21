package com.jimuqu.solon.claw.web;

import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.DownloadedFile;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 人格工作区文件接口。 */
@Controller
public class DashboardWorkspaceController {
    /** 注入工作区服务，用于调用对应业务能力。 */
    private final DashboardWorkspaceService workspaceService;

    /**
     * 创建控制台工作区控制器实例，并注入运行所需依赖。
     *
     * @param workspaceService 工作区服务依赖。
     */
    public DashboardWorkspaceController(DashboardWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * 执行files相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回files结果。
     */
    @Mapping(value = "/api/workspace/files", method = MethodType.GET)
    public Map<String, Object> files(Context context) {
        return execute(
                context,
                new Callback() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() {
                        return workspaceService.getFiles(context.param("profile"));
                    }
                });
    }

    /**
     * 执行文件相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param key 配置键或映射键。
     * @return 返回文件结果。
     */
    @Mapping(value = "/api/workspace/files/{key}", method = MethodType.GET)
    public Map<String, Object> file(Context context, final String key) {
        return execute(
                context,
                new Callback() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() {
                        return workspaceService.getFile(context.param("profile"), key);
                    }
                });
    }

    /**
     * 执行save，服务于控制台工作区主流程相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param key 配置键或映射键。
     * @return 返回save结果。
     */
    @Mapping(value = "/api/workspace/files/{key}", method = MethodType.PUT)
    public Map<String, Object> save(Context context, final String key) throws Exception {
        return execute(
                context,
                new Callback() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() {
                        String content = content(context);
                        return workspaceService.saveFile(profile(context), key, content);
                    }
                });
    }

    /**
     * 执行restore相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param key 配置键或映射键。
     * @return 返回restore结果。
     */
    @Mapping(value = "/api/workspace/files/{key}/restore", method = MethodType.POST)
    public Map<String, Object> restore(Context context, final String key) {
        return execute(
                context,
                new Callback() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() {
                        return workspaceService.restoreFile(profile(context), key);
                    }
                });
    }

    /**
     * 执行diaries相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回diaries结果。
     */
    @Mapping(value = "/api/workspace/diaries", method = MethodType.GET)
    public Map<String, Object> diaries(Context context) {
        return execute(
                context,
                new Callback() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() {
                        return workspaceService.listDiaryFiles(context.param("profile"));
                    }
                });
    }

    /**
     * 执行diary相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回diary结果。
     */
    @Mapping(value = "/api/workspace/diaries/read", method = MethodType.GET)
    public Map<String, Object> diary(Context context) {
        final String relativePath = context.param("path");
        return execute(
                context,
                new Callback() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() {
                        return workspaceService.getDiaryFile(
                                context.param("profile"), relativePath);
                    }
                });
    }

    /** 返回当前 Profile 的每日记忆归档状态。 */
    @Mapping(value = "/api/workspace/memory/archive", method = MethodType.GET)
    public Map<String, Object> memoryArchiveState(Context context) {
        return execute(
                context,
                new Callback() {
                    /** 读取持久化归档状态。 */
                    @Override
                    public Map<String, Object> run() {
                        return workspaceService.memoryArchiveState(context.param("profile"));
                    }
                });
    }

    /** 立即执行一轮每日记忆归档。 */
    @Mapping(value = "/api/workspace/memory/archive/run", method = MethodType.POST)
    public Map<String, Object> runMemoryArchive(Context context) {
        return execute(
                context,
                new Callback() {
                    /** 执行归档并返回本轮状态。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return workspaceService.runMemoryArchive(profile(context));
                    }
                });
    }

    /** 从指定不可变归档原文恢复活动每日记忆。 */
    @Mapping(value = "/api/workspace/memory/archive/restore", method = MethodType.POST)
    public Map<String, Object> restoreMemoryArchive(Context context) {
        return execute(
                context,
                new Callback() {
                    /** 读取受控路径并执行非覆盖恢复。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Object path = body(context).get("path");
                        return workspaceService.restoreMemoryArchive(
                                profile(context), path == null ? "" : String.valueOf(path));
                    }
                });
    }

    /**
     * 下载受控工作区文件；仅允许工作区固定文件 key 或文件名。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回下载文件。
     */
    @Mapping(value = "/api/solonclaw/download", method = MethodType.GET)
    public Object download(Context context) {
        try {
            DashboardWorkspaceService.DownloadContent content =
                    workspaceService.downloadFile(
                            context.param("profile"), context.param("path"), context.param("name"));
            return new DownloadedFile(
                            "text/plain;charset=UTF-8", content.getBytes(), content.getFileName())
                    .asAttachment(true);
        } catch (DashboardProfileScope.ProfileNotFoundException e) {
            context.status(404);
            return DashboardResponse.error("WORKSPACE_PROFILE_NOT_FOUND", workspaceErrorMessage(e));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("WORKSPACE_BAD_REQUEST", workspaceErrorMessage(e));
        }
    }

    /**
     * 执行当前回调或工具调用。
     *
     * @param context 当前请求或运行上下文。
     * @param callback 回调参数。
     * @return 返回执行结果。
     */
    private Map<String, Object> execute(Context context, Callback callback) {
        try {
            return callback.run();
        } catch (DashboardProfileScope.ProfileNotFoundException e) {
            context.status(404);
            return DashboardResponse.error("WORKSPACE_PROFILE_NOT_FOUND", workspaceErrorMessage(e));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("WORKSPACE_BAD_REQUEST", workspaceErrorMessage(e));
        } catch (IllegalStateException e) {
            context.status(409);
            return DashboardResponse.error("WORKSPACE_CONFLICT", workspaceErrorMessage(e));
        } catch (Exception e) {
            context.status(500);
            return DashboardResponse.error("WORKSPACE_ARCHIVE_FAILED", "每日记忆归档操作失败。");
        }
    }

    /**
     * 执行工作区错误消息相关逻辑。
     *
     * @param e 捕获到的异常。
     * @return 返回工作区Error消息结果。
     */
    private String workspaceErrorMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message != null && message.startsWith("Diary file is not available:")) {
            return "Diary file is not available.";
        }
        return message;
    }

    /**
     * 执行content相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回content结果。
     */
    private String content(Context context) {
        Map<String, Object> body = body(context);
        Object content = body.get("content");
        return content == null ? "" : String.valueOf(content);
    }

    /** 写请求中请求体 profile 优先，未提供时使用查询参数。 */
    private String profile(Context context) {
        Object bodyProfile = body(context).get("profile");
        if (bodyProfile != null && String.valueOf(bodyProfile).trim().length() > 0) {
            return String.valueOf(bodyProfile).trim();
        }
        return context.param("profile");
    }

    /** 读取并校验 JSON 对象请求体，空请求体返回空映射。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return java.util.Collections.emptyMap();
        }
        try {
            ONode node = ONode.ofJson(raw);
            if (node.toData() instanceof Map) {
                return (Map<String, Object>) node.toData();
            }
            throw new IllegalArgumentException(
                    "请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    /** 定义Callback的抽象契约，供不同运行时实现保持一致行为。 */
    private interface Callback {
        /**
         * 执行异步任务主体。
         *
         * @return 返回运行结果。
         */
        Map<String, Object> run() throws Exception;
    }
}
