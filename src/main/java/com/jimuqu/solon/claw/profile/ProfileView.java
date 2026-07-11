package com.jimuqu.solon.claw.profile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 汇总 Dashboard、CLI 和工具层共同需要的 Profile 隔离路径与运行状态。 */
public class ProfileView {
    /** Profile 名。 */
    private final String name;

    /** 是否为 sticky 活动 Profile。 */
    private final boolean active;

    /** 是否为当前 JVM 实际加载的 Profile。 */
    private final boolean current;

    /** Profile 工作区。 */
    private final Path home;

    /** 用户可见职责说明。 */
    private final String description;

    /** 非密模型标识。 */
    private final String model;

    /** 网关运行状态。 */
    private final ProfileGatewayStatus gateway;

    /** 已安装技能数量。 */
    private final long skillsCount;

    /** 配置文件。 */
    private final Path config;

    /** 凭据文件。 */
    private final Path credentials;

    /** 身份说明文件。 */
    private final Path soul;

    /** 会话数据库。 */
    private final Path sessions;

    /** 主记忆文件。 */
    private final Path memoryFile;

    /** 增量记忆目录。 */
    private final Path memoryDir;

    /** 技能目录。 */
    private final Path skillsDir;

    /** MCP 配置边界。 */
    private final Path mcpConfig;

    /** 国内渠道配置边界。 */
    private final Path channelsConfig;

    /** 日志目录。 */
    private final Path logs;

    /** 本机快捷命令别名。 */
    private final List<String> aliases;

    /** 分发清单的非密快照。 */
    private final Map<String, Object> distribution;

    /** 是否明确禁用内置技能初始化。 */
    private final boolean noBundledSkills;

    /**
     * 创建 Profile 只读视图。
     *
     * @param name Profile 名。
     * @param active 是否为 sticky 活动项。
     * @param current 是否为当前 JVM 实际加载项。
     * @param home Profile 工作区。
     * @param description 职责说明。
     * @param model 模型标识。
     * @param gateway 网关状态。
     * @param skillsCount 技能数量。
     * @param config 配置文件。
     * @param credentials 凭据文件。
     * @param soul 身份说明文件。
     * @param sessions 会话数据库。
     * @param memoryFile 主记忆文件。
     * @param memoryDir 增量记忆目录。
     * @param skillsDir 技能目录。
     * @param mcpConfig MCP 配置文件。
     * @param channelsConfig 国内渠道配置文件。
     * @param logs 日志目录。
     * @param aliases 快捷命令别名。
     * @param distribution 分发清单。
     * @param noBundledSkills 是否禁用内置技能。
     */
    public ProfileView(
            String name,
            boolean active,
            boolean current,
            Path home,
            String description,
            String model,
            ProfileGatewayStatus gateway,
            long skillsCount,
            Path config,
            Path credentials,
            Path soul,
            Path sessions,
            Path memoryFile,
            Path memoryDir,
            Path skillsDir,
            Path mcpConfig,
            Path channelsConfig,
            Path logs,
            List<String> aliases,
            Map<String, Object> distribution,
            boolean noBundledSkills) {
        this.name = name;
        this.active = active;
        this.current = current;
        this.home = home;
        this.description = description == null ? "" : description;
        this.model = model == null ? "" : model;
        this.gateway = gateway;
        this.skillsCount = skillsCount;
        this.config = config;
        this.credentials = credentials;
        this.soul = soul;
        this.sessions = sessions;
        this.memoryFile = memoryFile;
        this.memoryDir = memoryDir;
        this.skillsDir = skillsDir;
        this.mcpConfig = mcpConfig;
        this.channelsConfig = channelsConfig;
        this.logs = logs;
        this.aliases = aliases == null ? new ArrayList<String>() : new ArrayList<String>(aliases);
        this.distribution =
                distribution == null
                        ? new LinkedHashMap<String, Object>()
                        : new LinkedHashMap<String, Object>(distribution);
        this.noBundledSkills = noBundledSkills;
    }

    /**
     * @return Profile 名。
     */
    public String getName() {
        return name;
    }

    /**
     * @return sticky 活动项时返回 true。
     */
    public boolean isActive() {
        return active;
    }

    /**
     * @return 当前 JVM 实际加载项时返回 true。
     */
    public boolean isCurrent() {
        return current;
    }

    /**
     * @return Profile 工作区。
     */
    public Path getHome() {
        return home;
    }

    /**
     * @return 用户可见职责说明。
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return 非密模型标识。
     */
    public String getModel() {
        return model;
    }

    /**
     * @return 网关状态。
     */
    public ProfileGatewayStatus getGateway() {
        return gateway;
    }

    /**
     * @return 已安装技能数量。
     */
    public long getSkillsCount() {
        return skillsCount;
    }

    /**
     * @return 配置文件。
     */
    public Path getConfig() {
        return config;
    }

    /**
     * @return 配置文件是否存在。
     */
    public boolean isConfigExists() {
        return exists(config);
    }

    /**
     * @return Profile 局部凭据文件。
     */
    public Path getCredentials() {
        return credentials;
    }

    /**
     * @return 凭据文件是否存在。
     */
    public boolean isCredentialsExists() {
        return exists(credentials);
    }

    /**
     * @return 身份说明文件。
     */
    public Path getSoul() {
        return soul;
    }

    /**
     * @return 身份说明文件是否存在。
     */
    public boolean isSoulExists() {
        return exists(soul);
    }

    /**
     * @return 会话数据库路径。
     */
    public Path getSessions() {
        return sessions;
    }

    /**
     * @return 主记忆文件。
     */
    public Path getMemoryFile() {
        return memoryFile;
    }

    /**
     * @return 增量记忆目录。
     */
    public Path getMemoryDir() {
        return memoryDir;
    }

    /**
     * @return 技能目录。
     */
    public Path getSkillsDir() {
        return skillsDir;
    }

    /**
     * @return MCP 配置文件边界。
     */
    public Path getMcpConfig() {
        return mcpConfig;
    }

    /**
     * @return 国内渠道配置文件边界。
     */
    public Path getChannelsConfig() {
        return channelsConfig;
    }

    /**
     * @return 日志目录。
     */
    public Path getLogs() {
        return logs;
    }

    /**
     * @return 本机快捷命令别名的防御性复制。
     */
    public List<String> getAliases() {
        return new ArrayList<String>(aliases);
    }

    /**
     * @return 分发清单的防御性复制。
     */
    public Map<String, Object> getDistribution() {
        return new LinkedHashMap<String, Object>(distribution);
    }

    /**
     * @return 明确禁用内置技能初始化时返回 true。
     */
    public boolean isNoBundledSkills() {
        return noBundledSkills;
    }

    /**
     * 转换为 Dashboard 和工具层可直接序列化的映射。
     *
     * @return 不包含凭据内容的 Profile 映射。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("active", Boolean.valueOf(active));
        result.put("current", Boolean.valueOf(current));
        result.put("home", path(home));
        result.put("description", description);
        result.put("model", model);
        result.put(
                "gateway", gateway == null ? new LinkedHashMap<String, Object>() : gateway.toMap());
        result.put("skills_count", Long.valueOf(skillsCount));
        result.put("config", path(config));
        result.put("config_exists", Boolean.valueOf(isConfigExists()));
        result.put("credentials", path(credentials));
        result.put("credentials_exists", Boolean.valueOf(isCredentialsExists()));
        result.put("soul", path(soul));
        result.put("soul_exists", Boolean.valueOf(isSoulExists()));
        result.put("sessions", path(sessions));
        result.put("memory_file", path(memoryFile));
        result.put("memory_dir", path(memoryDir));
        result.put("skills_dir", path(skillsDir));
        result.put("mcp_config", path(mcpConfig));
        result.put("channels_config", path(channelsConfig));
        result.put("logs", path(logs));
        result.put("aliases", getAliases());
        result.put("distribution", getDistribution());
        result.put("no_bundled_skills", Boolean.valueOf(noBundledSkills));
        return result;
    }

    /** 判断路径对应的普通文件是否存在。 */
    private static boolean exists(Path value) {
        return value != null && java.nio.file.Files.isRegularFile(value);
    }

    /** 将路径转换为稳定绝对文本。 */
    private static String path(Path value) {
        return value == null ? "" : value.toAbsolutePath().normalize().toString();
    }
}
