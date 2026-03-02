package com.jimuqu.solonclaw.learning;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Init;
import org.noear.solon.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 学习编排服务
 * <p>
 * 负责协调和编排学习系统的各个组件，注册定时任务
 *
 * @author SolonClaw
 */
@Component
public class LearningOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(LearningOrchestrator.class);

    @Inject
    private ReflectionService reflectionService;

    @Inject
    private AutoSkillService autoSkillService;

    @Inject
    private LearningConfig learningConfig;

    @Inject
    private KnowledgeStore knowledgeStore;

    /**
     * 初始化学习系统
     */
    @Init
    public void init() {
        if (!learningConfig.isEnabled()) {
            log.info("学习系统已禁用，跳过初始化");
            return;
        }

        log.info("初始化 SolonClaw 学习系统");

        // 打印配置信息
        LearningConfig.ReflectionConfig reflectionConfig = learningConfig.getReflectionConfig();
        LearningConfig.AutoSkillConfig autoSkillConfig = learningConfig.getAutoSkillConfig();
        LearningConfig.KnowledgeConfig knowledgeConfig = learningConfig.getKnowledgeConfig();

        log.info("反思配置: cron={}, 时间窗口={}小时, 最大消息数={}",
            reflectionConfig.cron(), reflectionConfig.timeWindowHours(), reflectionConfig.maxMessagesPerReflection());

        log.info("自动技能配置: 处理cron={}, 置信度阈值={}, 实时分析={}",
            autoSkillConfig.processCron(), autoSkillConfig.minConfidenceThreshold(),
            autoSkillConfig.realtimeAnalysisEnabled());

        log.info("知识库配置: 最大搜索结果={}, 最小置信度={}",
            knowledgeConfig.maxSearchResults(), knowledgeConfig.minConfidenceThreshold());

        log.info("学习系统初始化完成");
    }

    /**
     * 定时执行反思任务
     * <p>
     * 每小时执行一次，分析最近的日志和经验
     */
    @Scheduled(cron = "${solonclaw.learning.reflection.cron:0 0 * * * ?}")
    public void scheduledReflectionTask() {
        if (!learningConfig.isEnabled()) {
            return;
        }

        log.info("开始执行定时反思任务");

        try {
            long reflectionId = reflectionService.performScheduledReflection(null);

            if (reflectionId > 0) {
                log.info("定时反思任务完成: reflectionId={}", reflectionId);
            } else {
                log.debug("定时反思任务完成: 没有需要反思的内容");
            }

        } catch (Exception e) {
            log.error("定时反思任务执行失败", e);
        }
    }

    /**
     * 定时处理技能请求
     * <p>
     * 每15分钟执行一次，处理待安装的技能请求
     */
    @Scheduled(cron = "${solonclaw.learning.autoSkill.processCron:0 */15 * * * ?}")
    public void processSkillRequestsTask() {
        if (!learningConfig.isEnabled()) {
            return;
        }

        log.info("开始处理待安装的技能请求");

        try {
            AutoSkillService.ProcessResult result = autoSkillService.processPendingSkillRequests();

            log.info("技能请求处理完成: 总计={}, 批准={}, 等待确认={}",
                result.totalProcessed(), result.approved(), result.waitingConfirmation());

        } catch (Exception e) {
            log.error("处理技能请求任务执行失败", e);
        }
    }

    /**
     * 对话完成后的回调
     * <p>
     * 在每次对话完成后触发，用于实时分析和学习
     *
     * @param sessionId 会话ID
     * @param response  Agent 响应
     * @param error     发生的错误（如果有）
     */
    public void onChatComplete(String sessionId, String response, Throwable error) {
        if (!learningConfig.isEnabled()) {
            return;
        }

        log.debug("对话完成回调: sessionId={}, hasError={}", sessionId, error != null);

        try {
            // 如果发生了错误，触发错误反思
            if (error != null) {
                log.warn("检测到错误，触发错误反省: sessionId={}, error={}",
                    sessionId, error.getMessage());

                reflectionService.triggerErrorReflection(
                    sessionId,
                    error.getClass().getSimpleName(),
                    error.getMessage(),
                    "对话执行过程中发生错误"
                );
            }

            // 如果启用了实时分析，分析技能需求
            LearningConfig.AutoSkillConfig autoSkillConfig = learningConfig.getAutoSkillConfig();
            if (autoSkillConfig.realtimeAnalysisEnabled()) {
                // TODO: 实现 analyzeSkillNeeds 方法
                // autoSkillService.analyzeSkillNeeds(sessionId, response, error);
                log.debug("实时分析技能需求功能待实现");
            }

            // 记录学习经验（如果有重要发现）
            recordLearningFromSession(sessionId, response, error);

        } catch (Exception e) {
            log.error("对话完成回调处理失败", e);
        }
    }

    /**
     * 从会话中学习
     * <p>
     * 自动提取会话中的关键信息并记录为经验
     */
    private void recordLearningFromSession(String sessionId, String response, Throwable error) {
        try {
            if (error == null && response != null && response.length() > 100) {
                // 成功的对话，记录为正面经验
                knowledgeStore.learnFromTask(
                    sessionId,
                    "完成对话任务",
                    true,
                    "成功完成用户请求: " + response.substring(0, Math.min(100, response.length()))
                );
            }

        } catch (Exception e) {
            log.debug("记录会话学习经验失败", e);
        }
    }

    /**
     * 获取学习系统统计信息
     */
    public LearningStats getStats() {
        try {
            // 获取反省记录数
            int reflectionCount = knowledgeStore.getRecentReflections(1000).size();

            // 获取经验条目数
            int experienceCount = knowledgeStore.searchAllExperiences("", 1000).size();

            // 获取待处理技能请求数
            int pendingSkillRequests = knowledgeStore.getPendingSkillRequests(1000).size();

            return new LearningStats(
                learningConfig.isEnabled(),
                reflectionCount,
                experienceCount,
                pendingSkillRequests
            );

        } catch (Exception e) {
            log.error("获取学习系统统计信息失败", e);
            return new LearningStats(learningConfig.isEnabled(), 0, 0, 0);
        }
    }

    /**
     * 手动触发反思
     */
    public long triggerReflection() {
        log.info("手动触发反思");
        return reflectionService.performScheduledReflection(null);
    }

    /**
     * 手动处理技能请求
     */
    public AutoSkillService.ProcessResult processSkillRequests() {
        log.info("手动处理技能请求");
        return autoSkillService.processPendingSkillRequests();
    }

    /**
     * 学习系统统计信息
     */
    public record LearningStats(
            boolean enabled,
            int reflectionCount,
            int experienceCount,
            int pendingSkillRequests
    ) {
    }
}
