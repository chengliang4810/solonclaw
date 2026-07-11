package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.model.SkillDescriptor;
import com.jimuqu.solon.claw.core.model.SkillView;
import com.jimuqu.solon.claw.core.service.SkillGuardService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.core.service.SkillImportService;
import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.SkillBrowseResult;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.service.DefaultSkillGuardService;
import com.jimuqu.solon.claw.skillhub.service.DefaultSkillHubService;
import com.jimuqu.solon.claw.skillhub.service.DefaultSkillImportService;
import com.jimuqu.solon.claw.skillhub.source.GitHubSkillSource;
import com.jimuqu.solon.claw.skillhub.support.DefaultSkillHubHttpClient;
import com.jimuqu.solon.claw.skillhub.support.GitHubAuth;
import com.jimuqu.solon.claw.skillhub.support.SkillHubHttpClient;
import com.jimuqu.solon.claw.skillhub.support.SkillHubStateStore;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.constants.SkillConstants;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
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

    /** 解析 Dashboard 请求的目标 Profile。 */
    private final DashboardProfileScope profileScope;

    /** 复用运行时 Skills Hub 搜索与已安装状态。 */
    private final SkillHubService skillHubService;

    /**
     * 创建控制台技能服务实例，并注入运行所需依赖。
     *
     * @param localSkillService 本地技能服务依赖。
     * @param preferenceStore 本地偏好存储依赖。
     */
    public DashboardSkillsService(
            LocalSkillService localSkillService, SqlitePreferenceStore preferenceStore) {
        this(localSkillService, preferenceStore, new DashboardProfileScope(), null);
    }

    /**
     * 创建支持跨 Profile 管理的技能服务。
     *
     * @param localSkillService 当前 Dashboard Profile 的技能服务。
     * @param preferenceStore 当前 Dashboard Profile 的偏好存储。
     * @param profileScope Profile 请求解析器。
     */
    public DashboardSkillsService(
            LocalSkillService localSkillService,
            SqlitePreferenceStore preferenceStore,
            DashboardProfileScope profileScope) {
        this(localSkillService, preferenceStore, profileScope, null);
    }

    /**
     * 创建同时支持 Skills Hub 搜索的跨 Profile 技能服务。
     *
     * @param localSkillService 当前 Dashboard Profile 的技能服务。
     * @param preferenceStore 当前 Dashboard Profile 的偏好存储。
     * @param profileScope Profile 请求解析器。
     * @param skillHubService Skills Hub 服务。
     */
    public DashboardSkillsService(
            LocalSkillService localSkillService,
            SqlitePreferenceStore preferenceStore,
            DashboardProfileScope profileScope,
            SkillHubService skillHubService) {
        this.localSkillService = localSkillService;
        this.preferenceStore = preferenceStore;
        this.profileScope = profileScope;
        this.skillHubService = skillHubService;
    }

    /**
     * 搜索 Skills Hub，并返回 Builder 使用的稳定结构。
     *
     * @param query 搜索词；空白时不发起网络请求。
     * @param source 来源过滤器；空白时使用 all。
     * @param limit 返回上限，最终限制在 1 到 50。
     * @return 结果、来源计数、超时来源和已安装映射。
     * @throws Exception Hub 搜索失败。
     */
    public Map<String, Object> searchHub(String query, String source, int limit) throws Exception {
        return searchHub(query, source, limit, null);
    }

    /**
     * 搜索指定 Profile 的 Skills Hub，并从同一 Profile 的状态文件返回已安装映射。
     *
     * @param query 搜索词；空白时不发起网络请求。
     * @param source 来源过滤器；空白时使用 all。
     * @param limit 返回上限，最终限制在 1 到 50。
     * @param profile 可选 Profile；为空时使用当前 Dashboard Profile。
     * @return 结果、来源计数、超时来源和目标 Profile 已安装映射。
     * @throws Exception Profile 解析或 Hub 搜索失败。
     */
    public Map<String, Object> searchHub(String query, String source, int limit, String profile)
            throws Exception {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.length() == 0) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("results", Collections.emptyList());
            empty.put("source_counts", Collections.emptyMap());
            empty.put("timed_out", Collections.emptyList());
            empty.put("installed", Collections.emptyMap());
            return empty;
        }
        SkillHubService targetHub = skillHubServiceForProfile(profile);
        String sourceFilter = source == null || source.trim().length() == 0 ? "all" : source.trim();
        int capped = Math.min(Math.max(limit, 1), 50);
        SkillBrowseResult search = targetHub.search(normalizedQuery, sourceFilter, capped);
        List<SkillMeta> items =
                search == null || search.getItems() == null
                        ? Collections.<SkillMeta>emptyList()
                        : search.getItems();
        Map<String, Integer> sourceCounts = new LinkedHashMap<String, Integer>();
        Map<String, SkillMeta> unique = new LinkedHashMap<String, SkillMeta>();
        for (SkillMeta item : items) {
            if (item == null) {
                continue;
            }
            String sourceKey = item.getSource() == null ? "" : item.getSource();
            sourceCounts.put(
                    sourceKey,
                    Integer.valueOf(
                            sourceCounts.containsKey(sourceKey)
                                    ? sourceCounts.get(sourceKey).intValue() + 1
                                    : 1));
            String identifier = item.getIdentifier();
            SkillMeta current = unique.get(identifier);
            if (current == null || trustRank(item) > trustRank(current)) {
                unique.put(identifier, item);
            }
        }
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        for (SkillMeta item : unique.values()) {
            if (results.size() >= capped) {
                break;
            }
            results.add(skillMeta(item));
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("results", results);
        response.put("source_counts", sourceCounts);
        response.put(
                "timed_out",
                search == null || search.getTimedOutSources() == null
                        ? Collections.emptyList()
                        : new ArrayList<String>(search.getTimedOutSources()));
        response.put("installed", installedHubSkills(targetHub));
        return response;
    }

    /** 选择当前运行时 Hub，或为非当前 Profile 构造完全绑定其配置与技能目录的 Hub。 */
    private SkillHubService skillHubServiceForProfile(String profile) {
        if (profileScope == null) {
            return requireCurrentSkillHub();
        }
        DashboardProfileScope.Resolved resolved = profileScope.resolve(profile);
        if (resolved.isCurrent()) {
            return requireCurrentSkillHub();
        }
        return createScopedSkillHubService(profileScope.loadConfig(resolved));
    }

    /** 返回当前 Profile 的注入 Hub，并在运行时缺失时给出稳定错误。 */
    private SkillHubService requireCurrentSkillHub() {
        if (skillHubService == null) {
            throw new IllegalStateException("Skills Hub service is unavailable.");
        }
        return skillHubService;
    }

    /** 为非当前 Profile 复制运行时 Hub 依赖图；protected 仅供 Profile 隔离测试替换边界。 */
    protected SkillHubService createScopedSkillHubService(AppConfig config) {
        File skillsDir = FileUtil.file(config.getRuntime().getSkillsDir());
        SkillHubStateStore stateStore = new SkillHubStateStore(skillsDir);
        SkillGuardService guardService = new DefaultSkillGuardService();
        SkillImportService importService =
                new DefaultSkillImportService(skillsDir, guardService, stateStore);
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(config);
        SkillHubHttpClient httpClient = new DefaultSkillHubHttpClient(securityPolicyService);
        GitHubAuth gitHubAuth = new GitHubAuth(httpClient);
        GitHubSkillSource gitHubSkillSource =
                new GitHubSkillSource(gitHubAuth, httpClient, stateStore);
        return new DefaultSkillHubService(
                new File(System.getProperty("user.dir", ".")),
                skillsDir,
                importService,
                guardService,
                stateStore,
                httpClient,
                gitHubAuth,
                gitHubSkillSource);
    }

    /** 转换 Hub 元数据为 Dashboard Builder 契约字段。 */
    private Map<String, Object> skillMeta(SkillMeta meta) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", meta.getName());
        item.put("description", meta.getDescription());
        item.put("source", meta.getSource());
        item.put("identifier", meta.getIdentifier());
        item.put("trust_level", meta.getTrustLevel());
        item.put("repo", meta.getRepo());
        item.put(
                "tags",
                meta.getTags() == null
                        ? Collections.emptyList()
                        : new ArrayList<String>(meta.getTags()));
        return item;
    }

    /** 生成 identifier 到已安装摘要的映射；状态文件不可读时按上游语义返回空映射。 */
    private Map<String, Object> installedHubSkills(SkillHubService hubService) {
        Map<String, Object> installed = new LinkedHashMap<String, Object>();
        List<HubInstallRecord> records;
        try {
            records = hubService.listInstalled();
        } catch (Exception ignored) {
            return installed;
        }
        if (records == null) {
            return installed;
        }
        for (HubInstallRecord record : records) {
            if (record == null
                    || record.getIdentifier() == null
                    || record.getIdentifier().trim().length() == 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", record.getName());
            item.put("trust_level", record.getTrustLevel());
            item.put("scan_verdict", record.getScanVerdict());
            installed.put(record.getIdentifier(), item);
        }
        return installed;
    }

    /** 保持与 Builder 搜索一致的信任级别优先级。 */
    private int trustRank(SkillMeta meta) {
        String trust = meta == null || meta.getTrustLevel() == null ? "" : meta.getTrustLevel();
        if ("builtin".equals(trust)) {
            return 2;
        }
        if ("trusted".equals(trust)) {
            return 1;
        }
        return 0;
    }

    /**
     * 读取技能。
     *
     * @return 返回读取到的技能。
     */
    public List<Map<String, Object>> getSkills() throws Exception {
        return getSkills(null);
    }

    /** 读取指定 Profile 的技能清单与启用状态。 */
    public List<Map<String, Object>> getSkills(String profile) throws Exception {
        ProfileSkillsContext context = context(profile);
        try {
            return getSkills(context.localSkillService, context.preferenceStore);
        } finally {
            context.close();
        }
    }

    /** 使用已绑定 Profile 的依赖组装技能清单。 */
    private List<Map<String, Object>> getSkills(
            LocalSkillService skills, SqlitePreferenceStore preferences) throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (SkillDescriptor descriptor : skills.listSkills(null)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", descriptor.canonicalName());
            item.put("description", descriptor.getDescription());
            item.put(
                    "category",
                    descriptor.getCategory() == null ? "general" : descriptor.getCategory());
            item.put("enabled", isSkillEnabled(preferences, descriptor.canonicalName()));
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
        return toggleSkill(null, name, enabled);
    }

    /** 切换指定 Profile 的技能启用状态。 */
    public Map<String, Object> toggleSkill(String profile, String name, boolean enabled)
            throws Exception {
        ProfileSkillsContext context = context(profile);
        try {
            context.preferenceStore.setSkillEnabledGlobal(name, enabled);
            return Collections.<String, Object>singletonMap("ok", true);
        } finally {
            context.close();
        }
    }

    /**
     * 读取技能内容并组装展示视图。
     *
     * @param name 名称参数。
     * @param filePath 目标文件相对路径或绝对路径。
     * @return 返回视图技能结果。
     */
    public Map<String, Object> viewSkill(String name, String filePath) throws Exception {
        return viewSkill((String) null, name, filePath);
    }

    /** 读取指定 Profile 的技能文件。 */
    public Map<String, Object> viewSkill(String profile, String name, String filePath)
            throws Exception {
        ProfileSkillsContext context = context(profile);
        try {
            return viewSkill(context.localSkillService, name, filePath);
        } finally {
            context.close();
        }
    }

    /** 使用已绑定 Profile 的技能服务组装详情。 */
    private Map<String, Object> viewSkill(LocalSkillService skills, String name, String filePath)
            throws Exception {
        SkillView view = skills.viewSkill(name, filePath);
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
        return getSkillFiles(null, name);
    }

    /** 读取指定 Profile 的技能关联文件清单。 */
    public List<Map<String, Object>> getSkillFiles(String profile, String name) throws Exception {
        ProfileSkillsContext context = context(profile);
        try {
            SkillView view = context.localSkillService.viewSkill(name, null);
            return skillFiles(view.getDescriptor());
        } finally {
            context.close();
        }
    }

    /**
     * 读取Toolsets。
     *
     * @return 返回读取到的Toolsets。
     */
    public List<Map<String, Object>> getToolsets() {
        return getToolsets(preferenceStore);
    }

    /** 读取指定 Profile 的工具集启用状态。 */
    public List<Map<String, Object>> getToolsets(String profile) {
        DashboardProfileScope.Resolved resolved = profileScope.resolve(profile);
        if (resolved.isCurrent()) {
            return getToolsets();
        }
        ProfileSkillsContext context = null;
        try {
            context = context(resolved.getName());
            return getToolsets(context.preferenceStore);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    /** 使用已绑定 Profile 的偏好存储组装工具集。 */
    private List<Map<String, Object>> getToolsets(SqlitePreferenceStore preferences) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        result.add(
                toolset(
                        preferences,
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
                        preferences,
                        "agent",
                        "代理工具",
                        "委托与任务规划能力",
                        Arrays.asList(ToolNameConstants.TODO, ToolNameConstants.DELEGATE_TASK)));
        result.add(
                toolset(
                        preferences,
                        "memory",
                        "记忆工具",
                        "长期记忆与会话搜索能力",
                        Arrays.asList(ToolNameConstants.MEMORY, ToolNameConstants.SESSION_SEARCH)));
        result.add(
                toolset(
                        preferences,
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
                        preferences,
                        "messaging",
                        "消息工具",
                        "国内渠道消息投递能力",
                        Collections.singletonList(ToolNameConstants.SEND_MESSAGE)));
        result.add(
                toolset(
                        preferences,
                        "automation",
                        "自动化工具",
                        "定时任务调度能力",
                        Collections.singletonList(ToolNameConstants.CRONJOB)));
        result.add(
                toolset(
                        preferences,
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
                        preferences,
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
            SqlitePreferenceStore preferences,
            String name,
            String label,
            String description,
            List<String> tools) {
        boolean enabled = true;
        for (String tool : tools) {
            enabled = enabled && isToolEnabled(preferences, tool);
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
    private boolean isSkillEnabled(SqlitePreferenceStore preferences, String name) {
        try {
            return preferences.isSkillEnabledGlobal(name);
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
    private boolean isToolEnabled(SqlitePreferenceStore preferences, String toolName) {
        try {
            return preferences.isToolEnabledGlobal(toolName);
        } catch (SQLException e) {
            return true;
        }
    }

    /** 为目标 Profile 创建独立技能目录和偏好数据库上下文。 */
    private ProfileSkillsContext context(String profile) throws Exception {
        DashboardProfileScope.Resolved resolved = profileScope.resolve(profile);
        if (resolved.isCurrent()) {
            return new ProfileSkillsContext(localSkillService, preferenceStore, null);
        }
        AppConfig config = profileScope.loadConfig(resolved);
        SqliteDatabase database = new SqliteDatabase(config);
        SqlitePreferenceStore preferences = new SqlitePreferenceStore(database);
        return new ProfileSkillsContext(
                new LocalSkillService(config, preferences), preferences, database);
    }

    /** 一次 Profile 技能请求使用的独立依赖，结束后释放目标数据库连接。 */
    private static final class ProfileSkillsContext {
        /** 已绑定 Profile 的技能服务。 */
        private final LocalSkillService localSkillService;

        /** 已绑定 Profile 的偏好存储。 */
        private final SqlitePreferenceStore preferenceStore;

        /** 非当前 Profile 临时打开的数据库；当前 Profile 为 null。 */
        private final SqliteDatabase database;

        /** 创建 Profile 技能请求上下文。 */
        private ProfileSkillsContext(
                LocalSkillService localSkillService,
                SqlitePreferenceStore preferenceStore,
                SqliteDatabase database) {
            this.localSkillService = localSkillService;
            this.preferenceStore = preferenceStore;
            this.database = database;
        }

        /** 释放本次请求临时打开的数据库。 */
        private void close() {
            if (database != null) {
                database.shutdown();
            }
        }
    }
}
