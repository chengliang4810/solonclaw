package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import java.util.ArrayList;
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
     * 执行describe文件相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回describe文件结果。
     */
    private Map<String, Object> describeFile(String key) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("key", key);
        result.put("name", personaWorkspaceService.fileName(key));
        result.put("path", fileReference(key));
        result.put("exists", personaWorkspaceService.exists(key));
        result.put("content", personaWorkspaceService.read(key));
        return result;
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
}
