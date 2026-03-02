package com.jimuqu.solonclaw.autonomous;

import java.util.Map;

/**
 * 资源
 * <p>
 * 代表 Agent 可用的资源
 *
 * @author SolonClaw
 */
public class Resource {

    private final String id;
    private final ResourceType type;
    private final String name;
    private final double quality;
    private final Map<String, Object> metadata;

    public Resource(String id, ResourceType type, String name, double quality, Map<String, Object> metadata) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.quality = quality;
        this.metadata = metadata;
    }

    // Getters
    public String getId() {
        return id;
    }

    public ResourceType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return name; // 简化实现：使用 name 作为描述
    }

    public double getQuality() {
        return quality;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}