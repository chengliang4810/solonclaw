package com.jimuqu.solon.claw.context;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.Map;

/** 承载技能来源追踪相关状态和辅助逻辑。 */
public class SkillProvenance {
    /** 来源Agent创建的统一常量值。 */
    public static final String SOURCE_AGENT_CREATED = "agent-created";

    /** 来源中心INSTALLED的统一常量值。 */
    public static final String SOURCE_HUB_INSTALLED = "hub-installed";

    /** 来源BUNDLED的统一常量值。 */
    public static final String SOURCE_BUNDLED = "bundled";

    /** 来源MANUAL的统一常量值。 */
    public static final String SOURCE_MANUAL = "manual";

    /** 来源外部的统一常量值。 */
    public static final String SOURCE_EXTERNAL = "external";

    /** TRUSTAgent的统一常量值。 */
    public static final String TRUST_AGENT = "agent-created";

    /** TRUST中心的统一常量值。 */
    public static final String TRUST_HUB = "hub-verified";

    /** TRUSTBUNDLED的统一常量值。 */
    public static final String TRUST_BUNDLED = "bundled";

    /** TRUST用户的统一常量值。 */
    public static final String TRUST_USER = "user-authored";

    /** TRUST外部的统一常量值。 */
    public static final String TRUST_EXTERNAL = "external";

    /** 记录技能来源追踪中的技能名称。 */
    private String skillName;

    /** 记录技能来源追踪中的来源。 */
    private String source;

    /** 记录技能来源追踪中的trust级别。 */
    private String trustLevel;

    /** 记录技能来源追踪中的创建根据。 */
    private String createdBy;

    /** 记录技能来源追踪中的创建时间。 */
    private long createdAt;

    /** 记录技能来源追踪中的中心Identifier。 */
    private String hubIdentifier;

    /** 记录技能来源追踪中的包名称。 */
    private String bundleName;

    /** 记录技能来源追踪中的外部目录。 */
    private String externalDir;

    /** 创建技能来源追踪实例。 */
    public SkillProvenance() {}

    /**
     * 创建技能来源追踪实例，并注入运行所需依赖。
     *
     * @param skillName 技能名称参数。
     * @param source 来源参数。
     */
    public SkillProvenance(String skillName, String source) {
        this.skillName = skillName;
        this.source = source;
        this.createdAt = System.currentTimeMillis();
        this.trustLevel = deriveTrustLevel(source);
    }

    /**
     * 执行Agent创建相关逻辑。
     *
     * @param skillName 技能名称参数。
     * @param createdBy createdBy 参数。
     * @return 返回Agent创建结果。
     */
    public static SkillProvenance agentCreated(String skillName, String createdBy) {
        SkillProvenance p = new SkillProvenance(skillName, SOURCE_AGENT_CREATED);
        p.createdBy = createdBy;
        return p;
    }

    /**
     * 执行中心Installed相关逻辑。
     *
     * @param skillName 技能名称参数。
     * @param hubIdentifier hubIdentifier标识或键值。
     * @return 返回中心Installed结果。
     */
    public static SkillProvenance hubInstalled(String skillName, String hubIdentifier) {
        SkillProvenance p = new SkillProvenance(skillName, SOURCE_HUB_INSTALLED);
        p.hubIdentifier = hubIdentifier;
        return p;
    }

    /**
     * 执行bundled相关逻辑。
     *
     * @param skillName 技能名称参数。
     * @param bundleName bundle名称参数。
     * @return 返回bundled结果。
     */
    public static SkillProvenance bundled(String skillName, String bundleName) {
        SkillProvenance p = new SkillProvenance(skillName, SOURCE_BUNDLED);
        p.bundleName = bundleName;
        return p;
    }

    /**
     * 执行外部相关逻辑。
     *
     * @param skillName 技能名称参数。
     * @param externalDir 文件或目录路径参数。
     * @return 返回外部结果。
     */
    public static SkillProvenance external(String skillName, String externalDir) {
        SkillProvenance p = new SkillProvenance(skillName, SOURCE_EXTERNAL);
        p.externalDir = externalDir;
        return p;
    }

    /**
     * 判断是否技能维护Managed。
     *
     * @return 如果技能维护Managed满足条件则返回 true，否则返回 false。
     */
    public boolean isCuratorManaged() {
        return SOURCE_AGENT_CREATED.equals(source);
    }

    /**
     * 判断是否中心Managed。
     *
     * @return 如果中心Managed满足条件则返回 true，否则返回 false。
     */
    public boolean isHubManaged() {
        return SOURCE_HUB_INSTALLED.equals(source);
    }

    /**
     * 转换为Map。
     *
     * @return 返回转换后的Map。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("skillName", skillName);
        map.put("source", source);
        map.put("trustLevel", trustLevel);
        if (StrUtil.isNotBlank(createdBy)) {
            map.put("createdBy", createdBy);
        }
        map.put("createdAt", Long.valueOf(createdAt));
        if (StrUtil.isNotBlank(hubIdentifier)) {
            map.put("hubIdentifier", hubIdentifier);
        }
        if (StrUtil.isNotBlank(bundleName)) {
            map.put("bundleName", bundleName);
        }
        if (StrUtil.isNotBlank(externalDir)) {
            map.put("externalDir", externalDir);
        }
        return map;
    }

    /**
     * 执行deriveTrust级别相关逻辑。
     *
     * @param source 来源参数。
     * @return 返回derive Trust级别结果。
     */
    private static String deriveTrustLevel(String source) {
        if (SOURCE_AGENT_CREATED.equals(source)) {
            return TRUST_AGENT;
        }
        if (SOURCE_HUB_INSTALLED.equals(source)) {
            return TRUST_HUB;
        }
        if (SOURCE_BUNDLED.equals(source)) {
            return TRUST_BUNDLED;
        }
        if (SOURCE_EXTERNAL.equals(source)) {
            return TRUST_EXTERNAL;
        }
        return TRUST_USER;
    }

    /**
     * 读取技能名称。
     *
     * @return 返回读取到的技能名称。
     */
    public String getSkillName() {
        return skillName;
    }

    /**
     * 写入技能名称。
     *
     * @param skillName 技能名称参数。
     */
    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    /**
     * 读取来源。
     *
     * @return 返回读取到的来源。
     */
    public String getSource() {
        return source;
    }

    /**
     * 写入来源。
     *
     * @param source 来源参数。
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * 读取Trust级别。
     *
     * @return 返回读取到的Trust级别。
     */
    public String getTrustLevel() {
        return trustLevel;
    }

    /**
     * 写入Trust级别。
     *
     * @param trustLevel trustLevel 参数。
     */
    public void setTrustLevel(String trustLevel) {
        this.trustLevel = trustLevel;
    }

    /**
     * 读取创建根据。
     *
     * @return 返回读取到的创建根据。
     */
    public String getCreatedBy() {
        return createdBy;
    }

    /**
     * 写入创建根据。
     *
     * @param createdBy createdBy 参数。
     */
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    /**
     * 读取创建时间。
     *
     * @return 返回读取到的创建时间。
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 写入创建时间。
     *
     * @param createdAt createdAt 参数。
     */
    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 读取中心Identifier。
     *
     * @return 返回读取到的中心Identifier。
     */
    public String getHubIdentifier() {
        return hubIdentifier;
    }

    /**
     * 写入中心Identifier。
     *
     * @param hubIdentifier hubIdentifier标识或键值。
     */
    public void setHubIdentifier(String hubIdentifier) {
        this.hubIdentifier = hubIdentifier;
    }

    /**
     * 读取包名称。
     *
     * @return 返回读取到的包名称。
     */
    public String getBundleName() {
        return bundleName;
    }

    /**
     * 写入包名称。
     *
     * @param bundleName bundle名称参数。
     */
    public void setBundleName(String bundleName) {
        this.bundleName = bundleName;
    }

    /**
     * 读取外部Dir。
     *
     * @return 返回读取到的外部Dir。
     */
    public String getExternalDir() {
        return externalDir;
    }

    /**
     * 写入外部Dir。
     *
     * @param externalDir 文件或目录路径参数。
     */
    public void setExternalDir(String externalDir) {
        this.externalDir = externalDir;
    }
}
