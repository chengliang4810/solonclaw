package com.jimuqu.solon.claw.context;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill provenance tracking. Records how a skill was created (agent-created, hub-installed,
 * bundled, manual) and its trust level for curator lifecycle decisions.
 */
public class SkillProvenance {
    public static final String SOURCE_AGENT_CREATED = "agent-created";
    public static final String SOURCE_HUB_INSTALLED = "hub-installed";
    public static final String SOURCE_BUNDLED = "bundled";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_EXTERNAL = "external";

    public static final String TRUST_AGENT = "agent-created";
    public static final String TRUST_HUB = "hub-verified";
    public static final String TRUST_BUNDLED = "bundled";
    public static final String TRUST_USER = "user-authored";
    public static final String TRUST_EXTERNAL = "external";

    private String skillName;
    private String source;
    private String trustLevel;
    private String createdBy;
    private long createdAt;
    private String hubIdentifier;
    private String bundleName;
    private String externalDir;

    public SkillProvenance() {}

    public SkillProvenance(String skillName, String source) {
        this.skillName = skillName;
        this.source = source;
        this.createdAt = System.currentTimeMillis();
        this.trustLevel = deriveTrustLevel(source);
    }

    public static SkillProvenance agentCreated(String skillName, String createdBy) {
        SkillProvenance p = new SkillProvenance(skillName, SOURCE_AGENT_CREATED);
        p.createdBy = createdBy;
        return p;
    }

    public static SkillProvenance hubInstalled(String skillName, String hubIdentifier) {
        SkillProvenance p = new SkillProvenance(skillName, SOURCE_HUB_INSTALLED);
        p.hubIdentifier = hubIdentifier;
        return p;
    }

    public static SkillProvenance bundled(String skillName, String bundleName) {
        SkillProvenance p = new SkillProvenance(skillName, SOURCE_BUNDLED);
        p.bundleName = bundleName;
        return p;
    }

    public static SkillProvenance external(String skillName, String externalDir) {
        SkillProvenance p = new SkillProvenance(skillName, SOURCE_EXTERNAL);
        p.externalDir = externalDir;
        return p;
    }

    public boolean isCuratorManaged() {
        return SOURCE_AGENT_CREATED.equals(source);
    }

    public boolean isHubManaged() {
        return SOURCE_HUB_INSTALLED.equals(source);
    }

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

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTrustLevel() {
        return trustLevel;
    }

    public void setTrustLevel(String trustLevel) {
        this.trustLevel = trustLevel;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getHubIdentifier() {
        return hubIdentifier;
    }

    public void setHubIdentifier(String hubIdentifier) {
        this.hubIdentifier = hubIdentifier;
    }

    public String getBundleName() {
        return bundleName;
    }

    public void setBundleName(String bundleName) {
        this.bundleName = bundleName;
    }

    public String getExternalDir() {
        return externalDir;
    }

    public void setExternalDir(String externalDir) {
        this.externalDir = externalDir;
    }
}
