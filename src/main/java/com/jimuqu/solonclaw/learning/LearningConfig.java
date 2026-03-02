package com.jimuqu.solonclaw.learning;

import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 学习系统配置
 * <p>
 * 管理 SolonClaw 学习系统的配置参数
 *
 * @author SolonClaw
 */
@Configuration
public class LearningConfig {

    private static final Logger log = LoggerFactory.getLogger(LearningConfig.class);

    /**
     * 学习系统是否启用
     */
    @Inject("${solonclaw.learning.enabled:true}")
    private boolean enabled = true;

    /**
     * 反思配置
     */
    @Inject("${solonclaw.learning.reflection.cron:0 0 * * * ?}")
    private String reflectionCron = "0 0 * * * ?"; // 每小时

    @Inject("${solonclaw.learning.reflection.timeWindowHours:1}")
    private int reflectionTimeWindowHours = 1;

    @Inject("${solonclaw.learning.reflection.maxMessagesPerReflection:200}")
    private int maxMessagesPerReflection = 200;

    /**
     * 自动技能配置
     */
    @Inject("${solonclaw.learning.autoSkill.processCron:0 */15 * * * ?}")
    private String autoSkillProcessCron = "0 */15 * * * ?"; // 每15分钟

    @Inject("${solonclaw.learning.autoSkill.minConfidenceThreshold:0.8}")
    private double minConfidenceThreshold = 0.8;

    @Inject("${solonclaw.learning.autoSkill.realtimeAnalysisEnabled:true}")
    private boolean realtimeAnalysisEnabled = true;

    /**
     * 知识库配置
     */
    @Inject("${solonclaw.learning.knowledge.maxSearchResults:5}")
    private int maxSearchResults = 5;

    @Inject("${solonclaw.learning.knowledge.minConfidenceThreshold:0.6}")
    private double knowledgeMinConfidenceThreshold = 0.6;

    /**
     * 获取反思配置
     */
    public ReflectionConfig getReflectionConfig() {
        return new ReflectionConfig(
            reflectionCron,
            reflectionTimeWindowHours,
            maxMessagesPerReflection
        );
    }

    /**
     * 获取自动技能配置
     */
    public AutoSkillConfig getAutoSkillConfig() {
        return new AutoSkillConfig(
            autoSkillProcessCron,
            minConfidenceThreshold,
            realtimeAnalysisEnabled
        );
    }

    /**
     * 获取知识库配置
     */
    public KnowledgeConfig getKnowledgeConfig() {
        return new KnowledgeConfig(
            maxSearchResults,
            knowledgeMinConfidenceThreshold
        );
    }

    /**
     * 是否启用学习系统
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 启用学习系统
     */
    public void enable() {
        this.enabled = true;
        log.info("学习系统已启用");
    }

    /**
     * 禁用学习系统
     */
    public void disable() {
        this.enabled = false;
        log.info("学习系统已禁用");
    }

    /**
     * 反思配置
     */
    public record ReflectionConfig(
            String cron,
            int timeWindowHours,
            int maxMessagesPerReflection
    ) {
    }

    /**
     * 自动技能配置
     */
    public record AutoSkillConfig(
            String processCron,
            double minConfidenceThreshold,
            boolean realtimeAnalysisEnabled
    ) {
    }

    /**
     * 知识库配置
     */
    public record KnowledgeConfig(
            int maxSearchResults,
            double minConfidenceThreshold
    ) {
    }
}
