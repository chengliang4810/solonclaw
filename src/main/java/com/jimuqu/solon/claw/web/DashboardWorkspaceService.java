package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard 人格工作区文件服务。 */
public class DashboardWorkspaceService {
    /** 注入persona工作区服务，用于调用对应业务能力。 */
    private final PersonaWorkspaceService personaWorkspaceService;

    /**
     * 创建控制台工作区服务实例，并注入运行所需依赖。
     *
     * @param personaWorkspaceService persona工作区服务依赖。
     */
    public DashboardWorkspaceService(PersonaWorkspaceService personaWorkspaceService) {
        this.personaWorkspaceService = personaWorkspaceService;
    }

    /**
     * 读取Files。
     *
     * @return 返回读取到的Files。
     */
    public Map<String, Object> getFiles() {
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        for (String key : personaWorkspaceService.orderedKeys()) {
            files.add(describeFile(key));
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
        return describeFile(key);
    }

    /**
     * 保存文件。
     *
     * @param key 配置键或映射键。
     * @param content 待处理内容。
     * @return 返回文件结果。
     */
    public Map<String, Object> saveFile(String key, String content) {
        personaWorkspaceService.write(key, content);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("file", describeFile(key));
        return result;
    }

    /**
     * 执行restore文件相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回restore文件结果。
     */
    public Map<String, Object> restoreFile(String key) {
        personaWorkspaceService.restoreTemplate(key);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("file", describeFile(key));
        return result;
    }

    /**
     * 列出Diary Files。
     *
     * @return 返回Diary Files列表。
     */
    public Map<String, Object> listDiaryFiles() {
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        for (String relativePath : personaWorkspaceService.listDiaryRelativePaths()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", FileUtil.file(relativePath).getName());
            item.put("relativePath", relativePath);
            item.put("path", diaryReference(relativePath));
            files.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("files", files);
        return result;
    }

    /**
     * 读取Diary文件。
     *
     * @param relativePath 文件或目录路径参数。
     * @return 返回读取到的Diary文件。
     */
    public Map<String, Object> getDiaryFile(String relativePath) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", FileUtil.file(relativePath).getName());
        result.put("relativePath", relativePath);
        result.put("path", diaryReference(relativePath));
        result.put("content", personaWorkspaceService.readDiary(relativePath));
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
        String key = normalizeDownloadKey(path);
        String resolvedName = safeDownloadName(fileName, personaWorkspaceService.fileName(key));
        return new DownloadContent(
                resolvedName, personaWorkspaceService.read(key).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 执行describe文件相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回describe文件结果。
     */
    private Map<String, Object> describeFile(String key) {
        File file = personaWorkspaceService.file(key);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("key", key);
        result.put("name", personaWorkspaceService.fileName(key));
        result.put("path", fileReference(key));
        result.put("exists", personaWorkspaceService.exists(key));
        result.put("modTime", file.exists() ? Long.valueOf(file.lastModified()) : null);
        result.put("content", personaWorkspaceService.read(key));
        return result;
    }

    /**
     * 将前端路径规范化为受控工作区 key。
     *
     * @param path 文件或目录路径参数。
     * @return 返回受控 key。
     */
    private String normalizeDownloadKey(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        if (value.startsWith("workspace://files/")) {
            value = value.substring("workspace://files/".length());
        }
        if (value.indexOf('/') >= 0) {
            value = FileUtil.file(value).getName();
        }
        if (personaWorkspaceService.orderedKeys().contains(value)) {
            return value;
        }
        Map<String, String> names = new HashMap<String, String>();
        for (String key : personaWorkspaceService.orderedKeys()) {
            names.put(personaWorkspaceService.fileName(key), key);
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
        String value = requestedName == null || requestedName.trim().length() == 0
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
