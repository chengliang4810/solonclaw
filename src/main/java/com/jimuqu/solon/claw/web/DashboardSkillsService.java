package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.model.SkillDescriptor;
import com.jimuqu.solon.claw.core.model.SkillView;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.constants.SkillConstants;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard 技能与工具集展示服务。 */
public class DashboardSkillsService {
    /** 注入本地技能服务，用于调用对应业务能力。 */
    private final LocalSkillService localSkillService;

    /** 记录控制台技能中的preferenceStore。 */
    private final SqlitePreferenceStore preferenceStore;

    /**
     * 创建控制台技能服务实例，并注入运行所需依赖。
     *
     * @param localSkillService 本地技能服务依赖。
     * @param preferenceStore 本地偏好存储依赖。
     */
    public DashboardSkillsService(
            LocalSkillService localSkillService, SqlitePreferenceStore preferenceStore) {
        this.localSkillService = localSkillService;
        this.preferenceStore = preferenceStore;
    }

    /**
     * 读取技能。
     *
     * @return 返回读取到的技能。
     */
    public List<Map<String, Object>> getSkills() throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (SkillDescriptor descriptor : localSkillService.listSkills(null)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", descriptor.canonicalName());
            item.put("description", descriptor.getDescription());
            item.put(
                    "category",
                    descriptor.getCategory() == null ? "general" : descriptor.getCategory());
            item.put("enabled", isSkillEnabled(descriptor.canonicalName()));
            result.add(item);
        }
        return result;
    }

    /**
     * 执行toggle技能相关逻辑。
     *
     * @param name 名称参数。
     * @param enabled 启用状态开关值。
     * @return 返回toggle技能结果。
     */
    public Map<String, Object> toggleSkill(String name, boolean enabled) throws Exception {
        preferenceStore.setSkillEnabledGlobal(name, enabled);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 读取技能内容并组装展示视图。
     *
     * @param name 名称参数。
     * @param filePath 目标文件相对路径或绝对路径。
     * @return 返回视图技能结果。
     */
    public Map<String, Object> viewSkill(String name, String filePath) throws Exception {
        SkillView view = localSkillService.viewSkill(name, filePath);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", view.getDescriptor().canonicalName());
        result.put("description", view.getDescriptor().getDescription());
        result.put(
                "category",
                view.getDescriptor().getCategory() == null
                        ? SkillConstants.DEFAULT_CATEGORY
                        : view.getDescriptor().getCategory());
        result.put("filePath", view.getFilePath());
        result.put("content", view.getContent());
        result.put("files", skillFiles(view.getDescriptor()));
        return result;
    }

    /**
     * 读取技能Files。
     *
     * @param name 名称参数。
     * @return 返回读取到的技能Files。
     */
    public List<Map<String, Object>> getSkillFiles(String name) throws Exception {
        SkillView view = localSkillService.viewSkill(name, null);
        return skillFiles(view.getDescriptor());
    }

    /**
     * 读取Toolsets。
     *
     * @return 返回读取到的Toolsets。
     */
    public List<Map<String, Object>> getToolsets() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        result.add(
                toolset(
                        "code",
                        "代码工具",
                        "官方 Shell/Python/Node.js、文件读写与代码搜索能力",
                        Arrays.asList(
                                ToolNameConstants.EXECUTE_SHELL,
                                ToolNameConstants.PROCESS,
                                ToolNameConstants.EXECUTE_CODE,
                                ToolNameConstants.EXECUTE_PYTHON,
                                ToolNameConstants.EXECUTE_JS,
                                ToolNameConstants.GET_CURRENT_TIME,
                                ToolNameConstants.FILE_READ,
                                ToolNameConstants.FILE_WRITE,
                                ToolNameConstants.READ_FILE,
                                ToolNameConstants.WRITE_FILE,
                                ToolNameConstants.SEARCH_FILES,
                                ToolNameConstants.FILE_LIST,
                                ToolNameConstants.FILE_DELETE,
                                ToolNameConstants.PATCH,
                                ToolNameConstants.CODESEARCH)));
        result.add(
                toolset(
                        "agent",
                        "代理工具",
                        "委托与任务规划能力",
                        Arrays.asList(ToolNameConstants.TODO, ToolNameConstants.DELEGATE_TASK)));
        result.add(
                toolset(
                        "memory",
                        "记忆工具",
                        "长期记忆与会话搜索能力",
                        Arrays.asList(ToolNameConstants.MEMORY, ToolNameConstants.SESSION_SEARCH)));
        result.add(
                toolset(
                        "skills",
                        "技能工具",
                        "本地技能与 Skills Hub 管理能力",
                        Arrays.asList(
                                ToolNameConstants.SKILLS_LIST,
                                ToolNameConstants.SKILL_VIEW,
                                ToolNameConstants.SKILL_FILES,
                                ToolNameConstants.SKILL_MANAGE,
                                ToolNameConstants.SKILLS_HUB_SEARCH,
                                ToolNameConstants.SKILLS_HUB_INSPECT,
                                ToolNameConstants.SKILLS_HUB_INSTALL,
                                ToolNameConstants.SKILLS_HUB_LIST,
                                ToolNameConstants.SKILLS_HUB_CHECK,
                                ToolNameConstants.SKILLS_HUB_UPDATE,
                                ToolNameConstants.SKILLS_HUB_AUDIT,
                                ToolNameConstants.SKILLS_HUB_UNINSTALL,
                                ToolNameConstants.SKILLS_HUB_TAP)));
        result.add(
                toolset(
                        "messaging",
                        "消息工具",
                        "国内渠道消息投递能力",
                        Collections.singletonList(ToolNameConstants.SEND_MESSAGE)));
        result.add(
                toolset(
                        "automation",
                        "自动化工具",
                        "定时任务调度能力",
                        Collections.singletonList(ToolNameConstants.CRONJOB)));
        result.add(
                toolset(
                        "config",
                        "配置工具",
                        "读取和修改工作区配置与密钥",
                        Arrays.asList(
                                ToolNameConstants.CONFIG_GET,
                                ToolNameConstants.CONFIG_SET,
                                ToolNameConstants.CONFIG_SET_SECRET,
                                ToolNameConstants.CONFIG_REFRESH)));
        result.add(
                toolset(
                        "gateway",
                        "工具网关",
                        "基于 Solon AI ToolGateway 的工具发现、详情查看与按名调用能力",
                        Collections.singletonList(ToolNameConstants.TOOL_GATEWAY)));
        return result;
    }

    /**
     * 执行工具集相关逻辑。
     *
     * @param name 名称参数。
     * @param label label 参数。
     * @param description 描述参数。
     * @param tools tools 参数。
     * @return 返回toolset结果。
     */
    private Map<String, Object> toolset(
            String name, String label, String description, List<String> tools) {
        boolean enabled = true;
        for (String tool : tools) {
            enabled = enabled && isToolEnabled(tool);
        }

        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", name);
        item.put("label", label);
        item.put("description", description);
        item.put("enabled", enabled);
        item.put("configured", true);
        item.put("tools", tools);
        return item;
    }

    /**
     * 执行技能Files相关逻辑。
     *
     * @param descriptor descriptor 参数。
     * @return 返回技能Files结果。
     */
    private List<Map<String, Object>> skillFiles(SkillDescriptor descriptor) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        result.add(skillFile(SkillConstants.SKILL_FILE_NAME));
        for (String path : descriptor.getLinkedFiles()) {
            result.add(skillFile(path));
        }
        return result;
    }

    /**
     * 执行技能文件相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回技能文件结果。
     */
    private Map<String, Object> skillFile(String path) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("path", path);
        item.put("name", fileName(path));
        item.put("isDir", false);
        return item;
    }

    /**
     * 执行文件名称相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回文件名称结果。
     */
    private String fileName(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    /**
     * 判断是否技能启用。
     *
     * @param name 名称参数。
     * @return 如果技能启用满足条件则返回 true，否则返回 false。
     */
    private boolean isSkillEnabled(String name) {
        try {
            return preferenceStore.isSkillEnabledGlobal(name);
        } catch (SQLException e) {
            return true;
        }
    }

    /**
     * 判断是否工具启用。
     *
     * @param toolName 工具名称。
     * @return 如果工具启用满足条件则返回 true，否则返回 false。
     */
    private boolean isToolEnabled(String toolName) {
        try {
            return preferenceStore.isToolEnabledGlobal(toolName);
        } catch (SQLException e) {
            return true;
        }
    }
}
