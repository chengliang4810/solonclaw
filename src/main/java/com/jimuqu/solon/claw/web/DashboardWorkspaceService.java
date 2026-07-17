package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.MemoryArchiveService;
import com.jimuqu.solon.claw.context.MemoryArchiveState;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.gateway.service.ProfileRuntimeBundle;
import com.jimuqu.solon.claw.profile.ProfileBeanResolver;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Dashboard 人格工作区文件服务。 */
public class DashboardWorkspaceService {
    /** 注入persona工作区服务，用于调用对应业务能力。 */
    private final PersonaWorkspaceService personaWorkspaceService;

    /** 解析 Dashboard 请求的目标 Profile。 */
    private final DashboardProfileScope profileScope;

    /** 按已校验 Profile 作用域返回对应归档服务的延迟解析器。 */
    private final Function<DashboardProfileScope.Resolved, MemoryArchiveService>
            memoryArchiveServiceResolver;

    /**
     * 创建控制台工作区服务实例，并注入运行所需依赖。
     *
     * @param personaWorkspaceService persona工作区服务依赖。
     */
    public DashboardWorkspaceService(PersonaWorkspaceService personaWorkspaceService) {
        this(personaWorkspaceService, new DashboardProfileScope(), (MemoryArchiveService) null);
    }

    /**
     * 创建支持跨 Profile 管理的工作区服务。
     *
     * @param personaWorkspaceService 当前 Dashboard Profile 的工作区服务。
     * @param profileScope Profile 请求解析器。
     */
    public DashboardWorkspaceService(
            PersonaWorkspaceService personaWorkspaceService, DashboardProfileScope profileScope) {
        this(personaWorkspaceService, profileScope, (MemoryArchiveService) null);
    }

    /** 创建同时暴露每日记忆归档管理能力的工作区服务。 */
    public DashboardWorkspaceService(
            PersonaWorkspaceService personaWorkspaceService,
            DashboardProfileScope profileScope,
            MemoryArchiveService memoryArchiveService) {
        this(
                personaWorkspaceService,
                profileScope,
                resolved -> resolveProfileArchiveService(resolved, memoryArchiveService));
    }

    /** 创建可注入 Profile 归档服务解析器的工作区服务，供隔离测试复用。 */
    DashboardWorkspaceService(
            PersonaWorkspaceService personaWorkspaceService,
            DashboardProfileScope profileScope,
            Function<DashboardProfileScope.Resolved, MemoryArchiveService>
                    memoryArchiveServiceResolver) {
        this.personaWorkspaceService = personaWorkspaceService;
        this.profileScope = profileScope;
        this.memoryArchiveServiceResolver = memoryArchiveServiceResolver;
    }

    /**
     * 读取Files。
     *
     * @return 返回读取到的Files。
     */
    public Map<String, Object> getFiles() {
        return getFiles(null);
    }

    /**
     * 读取指定 Profile 的受控上下文文件。
     *
     * @param profile 可选目标 Profile。
     * @return 返回读取到的文件列表。
     */
    public Map<String, Object> getFiles(String profile) {
        PersonaWorkspaceService workspace = workspace(profile);
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        for (String key : workspace.orderedKeys()) {
            files.add(describeFile(workspace, key));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("files", files);
        return result;
    }

    /**
     * 读取文件。
     *
     * @param key 配置键或映射键。
     * @return 返回读取到的文件。
     */
    public Map<String, Object> getFile(String key) {
        return getFile(null, key);
    }

    /** 读取指定 Profile 的受控上下文文件。 */
    public Map<String, Object> getFile(String profile, String key) {
        PersonaWorkspaceService workspace = workspace(profile);
        return describeFile(workspace, key);
    }

    /**
     * 保存文件。
     *
     * @param key 配置键或映射键。
     * @param content 待处理内容。
     * @return 返回文件结果。
     */
    public Map<String, Object> saveFile(String key, String content) {
        return saveFile(null, key, content);
    }

    /** 写入指定 Profile 的受控上下文文件。 */
    public Map<String, Object> saveFile(String profile, String key, String content) {
        PersonaWorkspaceService workspace = workspace(profile);
        workspace.write(key, content);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("file", describeFile(workspace, key));
        return result;
    }

    /**
     * 执行restore文件相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回restore文件结果。
     */
    public Map<String, Object> restoreFile(String key) {
        return restoreFile(null, key);
    }

    /** 恢复指定 Profile 的受控上下文模板。 */
    public Map<String, Object> restoreFile(String profile, String key) {
        PersonaWorkspaceService workspace = workspace(profile);
        workspace.restoreTemplate(key);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("file", describeFile(workspace, key));
        return result;
    }

    /**
     * 列出Diary Files。
     *
     * @return 返回Diary Files列表。
     */
    public Map<String, Object> listDiaryFiles() {
        return listDiaryFiles(null);
    }

    /** 列出指定 Profile 的日记文件。 */
    public Map<String, Object> listDiaryFiles(String profile) {
        PersonaWorkspaceService workspace = workspace(profile);
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        for (String relativePath : workspace.listDiaryRelativePaths()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", FileUtil.file(relativePath).getName());
            item.put("relativePath", relativePath);
            item.put("path", diaryReference(relativePath));
            item.put("kind", diaryKind(relativePath));
            files.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("files", files);
        return result;
    }

    /** 返回当前 Profile 的每日记忆归档诊断状态。 */
    public Map<String, Object> memoryArchiveState() {
        return memoryArchiveState(null);
    }

    /** 返回指定 Profile 的每日记忆归档诊断状态。 */
    public Map<String, Object> memoryArchiveState(String profile) {
        return describeArchiveState(requireMemoryArchiveService(profile).state());
    }

    /** 立即执行一轮旧每日记忆归档，并返回持久化诊断状态。 */
    public Map<String, Object> runMemoryArchive() throws Exception {
        return runMemoryArchive(null);
    }

    /** 立即执行指定 Profile 的旧每日记忆归档。 */
    public Map<String, Object> runMemoryArchive(String profile) throws Exception {
        return describeArchiveState(requireMemoryArchiveService(profile).runOnce());
    }

    /** 从不可变原文恢复活动每日记忆，拒绝覆盖不同内容。 */
    public Map<String, Object> restoreMemoryArchive(String relativePath) throws Exception {
        return restoreMemoryArchive(null, relativePath);
    }

    /** 从指定 Profile 的不可变原文恢复活动每日记忆。 */
    public Map<String, Object> restoreMemoryArchive(String profile, String relativePath)
            throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", Boolean.TRUE);
        result.put("message", requireMemoryArchiveService(profile).restore(relativePath));
        result.put("relativePath", relativePath);
        return result;
    }

    /** 将归档状态转换为 Dashboard 和 Agent 工具共用的稳定字段集合。 */
    private Map<String, Object> describeArchiveState(MemoryArchiveState state) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("lastStartedAt", Long.valueOf(state.getLastStartedAt()));
        result.put("lastCompletedAt", Long.valueOf(state.getLastCompletedAt()));
        result.put("lastOutcome", state.getLastOutcome());
        result.put("selectedCount", Integer.valueOf(state.getSelectedCount()));
        result.put("archivedCount", Integer.valueOf(state.getArchivedCount()));
        result.put("summarizedByAiCount", Integer.valueOf(state.getSummarizedByAiCount()));
        result.put(
                "summarizedByFallbackCount", Integer.valueOf(state.getSummarizedByFallbackCount()));
        result.put("memoryCandidateCount", Integer.valueOf(state.getMemoryCandidateCount()));
        result.put("failedCount", Integer.valueOf(state.getFailedCount()));
        result.put("lastError", state.getLastError());
        result.put("lastFallbackReason", state.getLastFallbackReason());
        result.put("durationMs", Long.valueOf(state.getDurationMs()));
        return result;
    }

    /** 返回已配置的记忆归档服务，否则拒绝生产入口调用。 */
    private MemoryArchiveService requireMemoryArchiveService(String profile) {
        DashboardProfileScope.Resolved resolved = profileScope.resolve(profile);
        MemoryArchiveService service =
                memoryArchiveServiceResolver == null
                        ? null
                        : memoryArchiveServiceResolver.apply(resolved);
        if (service == null) {
            throw new IllegalStateException("每日记忆归档服务不可用。");
        }
        return service;
    }

    /** 当前 Profile 直接复用注入服务；命名 Profile 只从其独立子容器解析，禁止回退 default。 */
    private static MemoryArchiveService resolveProfileArchiveService(
            DashboardProfileScope.Resolved resolved, MemoryArchiveService currentService) {
        if (resolved.isCurrent()) {
            return currentService;
        }
        ProfileMultiplexRuntimeManager manager =
                ProfileBeanResolver.getBean(ProfileMultiplexRuntimeManager.class);
        if (manager == null) {
            throw new IllegalStateException("目标 Profile 运行时不可用。");
        }
        ProfileRuntimeBundle runtime = manager.requireRuntime(resolved.getName());
        MemoryArchiveService service = runtime.appContext().getBean(MemoryArchiveService.class);
        if (service == null) {
            throw new IllegalStateException("目标 Profile 的每日记忆归档服务不可用。");
        }
        return service;
    }

    /** 标记日记列表项属于活动日记、不可变归档原文或派生摘要。 */
    private String diaryKind(String relativePath) {
        if (!relativePath.startsWith("memory/archive/")) {
            return "active";
        }
        return relativePath.endsWith(".summary.md") ? "summary" : "archive";
    }

    /**
     * 读取Diary文件。
     *
     * @param relativePath 文件或目录路径参数。
     * @return 返回读取到的Diary文件。
     */
    public Map<String, Object> getDiaryFile(String relativePath) {
        return getDiaryFile(null, relativePath);
    }

    /** 读取指定 Profile 的日记文件。 */
    public Map<String, Object> getDiaryFile(String profile, String relativePath) {
        PersonaWorkspaceService workspace = workspace(profile);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", FileUtil.file(relativePath).getName());
        result.put("relativePath", relativePath);
        result.put("path", diaryReference(relativePath));
        result.put("content", workspace.readDiary(relativePath));
        return result;
    }

    /**
     * 读取受控工作区文件的下载内容；只允许 PersonaWorkspaceService 暴露的固定 key。
     *
     * @param path 前端传入的工作区文件路径、文件名或 workspace:// 引用。
     * @param fileName 可选下载文件名。
     * @return 返回下载内容。
     */
    public DownloadContent downloadFile(String path, String fileName) {
        return downloadFile(null, path, fileName);
    }

    /** 下载指定 Profile 的受控上下文文件。 */
    public DownloadContent downloadFile(String profile, String path, String fileName) {
        PersonaWorkspaceService workspace = workspace(profile);
        String key = normalizeDownloadKey(workspace, path);
        String resolvedName = safeDownloadName(fileName, workspace.fileName(key));
        return new DownloadContent(
                resolvedName, workspace.read(key).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 执行describe文件相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回describe文件结果。
     */
    private Map<String, Object> describeFile(PersonaWorkspaceService workspace, String key) {
        File file = workspace.file(key);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("key", key);
        result.put("name", workspace.fileName(key));
        result.put("path", fileReference(key));
        result.put("exists", workspace.exists(key));
        result.put("modTime", file.exists() ? Long.valueOf(file.lastModified()) : null);
        result.put("content", workspace.read(key));
        return result;
    }

    /**
     * 将前端路径规范化为受控工作区 key。
     *
     * @param path 文件或目录路径参数。
     * @return 返回受控 key。
     */
    private String normalizeDownloadKey(PersonaWorkspaceService workspace, String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        if (value.startsWith("workspace://files/")) {
            value = value.substring("workspace://files/".length());
        }
        if (value.indexOf('/') >= 0) {
            value = FileUtil.file(value).getName();
        }
        if (workspace.orderedKeys().contains(value)) {
            return value;
        }
        Map<String, String> names = new HashMap<String, String>();
        for (String key : workspace.orderedKeys()) {
            names.put(workspace.fileName(key), key);
        }
        String key = names.get(value);
        if (key == null) {
            throw new IllegalArgumentException("Workspace file is not available.");
        }
        return key;
    }

    /**
     * 生成安全下载文件名，避免响应头中出现路径片段。
     *
     * @param requestedName 前端请求的文件名。
     * @param fallbackName 默认文件名。
     * @return 返回安全文件名。
     */
    private String safeDownloadName(String requestedName, String fallbackName) {
        String value =
                requestedName == null || requestedName.trim().length() == 0
                        ? fallbackName
                        : requestedName.trim();
        value = FileUtil.file(value.replace('\\', '/')).getName();
        return value.length() == 0 ? fallbackName : value;
    }

    /**
     * 执行文件引用相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回文件Reference结果。
     */
    private String fileReference(String key) {
        return "workspace://files/" + key;
    }

    /**
     * 执行diary引用相关逻辑。
     *
     * @param relativePath 文件或目录路径参数。
     * @return 返回diary Reference结果。
     */
    private String diaryReference(String relativePath) {
        return "workspace://diaries/" + relativePath.replace('\\', '/');
    }

    /** 为目标 Profile 创建只绑定其工作区的上下文文件服务。 */
    private PersonaWorkspaceService workspace(String profile) {
        DashboardProfileScope.Resolved resolved = profileScope.resolve(profile);
        if (resolved.isCurrent()) {
            return personaWorkspaceService;
        }
        AppConfig config = new AppConfig();
        config.getWorkspace().setDir(resolved.getHome().toString());
        config.normalizePaths();
        return new PersonaWorkspaceService(config);
    }

    /** 承载受控工作区文件下载内容。 */
    public static final class DownloadContent {
        /** 下载文件名。 */
        private final String fileName;

        /** 下载文件内容。 */
        private final byte[] bytes;

        /**
         * 创建下载内容。
         *
         * @param fileName 下载文件名。
         * @param bytes 下载文件内容。
         */
        public DownloadContent(String fileName, byte[] bytes) {
            this.fileName = fileName;
            this.bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        /**
         * 读取下载文件名。
         *
         * @return 返回下载文件名。
         */
        public String getFileName() {
            return fileName;
        }

        /**
         * 读取下载文件内容。
         *
         * @return 返回下载文件内容。
         */
        public byte[] getBytes() {
            return bytes.clone();
        }
    }
}
