package com.jimuqu.solonclaw.autonomous;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 资源管理器
 * <p>
 * 管理 Agent 的资源（技能、工具、知识等）
 *
 * @author SolonClaw
 */
@Component
public class ResourceManager {

    private static final Logger log = LoggerFactory.getLogger(ResourceManager.class);

    @Inject
    private AutonomousConfig config;

    /**
     * 资源存储
     */
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final Map<String, ResourceUsage> resourceUsage = new ConcurrentHashMap<>();

    /**
     * 初始化
     */
    public void init() {
        log.info("初始化资源管理器");

        // 注册初始资源
        registerInitialResources();

        log.info("资源管理器初始化完成，资源总数: {}", resources.size());
    }

    /**
     * 注册初始资源
     */
    private void registerInitialResources() {
        // 技能资源
        registerResource(new Resource(
            "skill-shell",
            ResourceType.SKILL,
            "Shell 命令执行技能",
            1.0,
            Map.of(
                "enabled", true,
                "available", true
            )
        ));

        registerResource(new Resource(
            "skill-file",
            ResourceType.SKILL,
            "文件操作技能",
            1.0,
            Map.of(
                "enabled", true,
                "available", true
            )
        ));

        registerResource(new Resource(
            "skill-http",
            ResourceType.SKILL,
            "HTTP 请求技能",
            1.0,
            Map.of(
                "enabled", true,
                "available", true
            )
        ));

        // 工具资源
        registerResource(new Resource(
            "tool-shell",
            ResourceType.TOOL,
            "Shell 工具",
            1.0,
            Map.of(
                "enabled", true,
                "available", true
            )
        ));

        registerResource(new Resource(
            "tool-browser",
            ResourceType.TOOL,
            "浏览器工具",
            1.0,
            Map.of(
                "enabled", true,
                "available", true
            )
        ));

        // 知识资源
        registerResource(new Resource(
            "knowledge-experience",
            ResourceType.KNOWLEDGE,
            "经验知识库",
            1.0,
            Map.of(
                "enabled", true,
                "available", true
            )
        ));
    }

    /**
     * 注册资源
     */
    public void registerResource(Resource resource) {
        if (resource == null) {
            log.warn("资源为空，跳过");
            return;
        }

        resources.put(resource.getId(), resource);
        log.debug("注册资源: id={}, type={}, name={}",
            resource.getId(), resource.getType(), resource.getName());
    }

    /**
     * 获取资源
     */
    public Resource getResource(String resourceId) {
        return resources.get(resourceId);
    }

    /**
     * 获取所有资源
     */
    public Map<String, Resource> getAvailableResources() {
        return new HashMap<>(resources);
    }

    /**
     * 按类型获取资源
     */
    public List<Resource> getResourcesByType(ResourceType type) {
        return resources.values().stream()
            .filter(r -> r.getType() == type)
            .collect(Collectors.toList());
    }

    /**
     * 检查资源是否可用
     */
    public boolean isResourceAvailable(String resourceId) {
        Resource resource = resources.get(resourceId);
        if (resource == null) {
            return false;
        }

        Boolean enabled = (Boolean) resource.getMetadata().getOrDefault("enabled", true);
        Boolean available = (Boolean) resource.getMetadata().getOrDefault("available", true);

        return enabled && available;
    }

    /**
     * 使用资源
     */
    public boolean useResource(String resourceId, String taskId) {
        if (!isResourceAvailable(resourceId)) {
            log.warn("资源不可用: resourceId={}", resourceId);
            return false;
        }

        try {
            // 记录使用
            recordUsage(resourceId, taskId);

            log.debug("使用资源: resourceId={}, taskId={}", resourceId, taskId);
            return true;

        } catch (Exception e) {
            log.error("使用资源失败: resourceId={}", resourceId, e);
            return false;
        }
    }

    /**
     * 释放资源
     */
    public void releaseResource(String resourceId, String taskId) {
        ResourceUsage usage = resourceUsage.get(resourceId);
        if (usage != null) {
            usage.release();
            log.debug("释放资源: resourceId={}, taskId={}", resourceId, taskId);
        }
    }

    /**
     * 记录资源使用
     */
    private void recordUsage(String resourceId, String taskId) {
        ResourceUsage usage = resourceUsage.computeIfAbsent(resourceId, id ->
            new ResourceUsage(id)
        );
        usage.use(taskId);
    }

    /**
     * 获取资源统计
     */
    public Map<String, Object> getResourceStats() {
        Map<String, Object> stats = new HashMap<>();

        // 按类型统计
        for (ResourceType type : ResourceType.values()) {
            List<Resource> typeResources = getResourcesByType(type);
            long availableCount = typeResources.stream()
                .filter(r -> isResourceAvailable(r.getId()))
                .count();

            stats.put(type.name().toLowerCase() + "_total", typeResources.size());
            stats.put(type.name().toLowerCase() + "_available", availableCount);
        }

        // 总体统计
        stats.put("total", resources.size());
        stats.put("available", resources.values().stream()
            .filter(r -> isResourceAvailable(r.getId()))
            .count());

        return stats;
    }

    /**
     * 获取资源使用情况
     */
    public Map<String, Object> getResourceUsage() {
        Map<String, Object> usage = new HashMap<>();

        for (Map.Entry<String, ResourceUsage> entry : resourceUsage.entrySet()) {
            ResourceUsage ru = entry.getValue();
            usage.put(entry.getKey(), Map.of(
                "totalUses", ru.getTotalUses(),
                "currentUses", ru.getCurrentUses(),
                "lastUsed", ru.getLastUsed()
            ));
        }

        return usage;
    }

    /**
     * 清理过期资源
     */
    public void cleanup() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(24);

            // 清理未使用超过 24 小时的资源使用记录
            resourceUsage.entrySet().removeIf(entry -> {
                ResourceUsage usage = entry.getValue();
                if (usage.getLastUsed() != null && usage.getLastUsed().isBefore(cutoff)) {
                    log.debug("清理资源使用记录: resourceId={}", entry.getKey());
                    return true;
                }
                return false;
            });

        } catch (Exception e) {
            log.error("清理资源失败", e);
        }
    }

    /**
     * 资源使用记录
     */
    private static class ResourceUsage {
        private final String resourceId;
        private long totalUses = 0;
        private long currentUses = 0;
        private LocalDateTime lastUsed;

        public ResourceUsage(String resourceId) {
            this.resourceId = resourceId;
        }

        public void use(String taskId) {
            totalUses++;
            currentUses++;
            lastUsed = LocalDateTime.now();
        }

        public void release() {
            if (currentUses > 0) {
                currentUses--;
            }
        }

        public long getTotalUses() {
            return totalUses;
        }

        public long getCurrentUses() {
            return currentUses;
        }

        public LocalDateTime getLastUsed() {
            return lastUsed;
        }
    }
}