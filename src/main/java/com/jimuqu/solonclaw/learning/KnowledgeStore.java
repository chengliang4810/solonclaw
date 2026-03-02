package com.jimuqu.solonclaw.learning;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 知识存储服务
 * <p>
 * 负责管理 AI Agent 学习到的知识、经验和模式
 *
 * @author SolonClaw
 */
@Component
public class KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStore.class);

    @Inject
    private com.jimuqu.solonclaw.memory.SessionStore sessionStore;

    // ==================== 反省管理 ====================

    /**
     * 保存反省记录
     *
     * @param sessionId         会话ID
     * @param reflectionType    反省类型（如：error_recovery, task_completion, optimization）
     * @param content           反省内容
     * @param context           上下文信息
     * @param actionItems       行动项（JSON格式）
     * @param effectivenessScore 有效性评分（0-1）
     * @return 反省记录ID
     */
    public long saveReflection(String sessionId, String reflectionType, String content,
                             String context, String actionItems, Double effectivenessScore) {
        log.debug("保存反省: sessionId={}, type={}", sessionId, reflectionType);
        return sessionStore.saveReflection(sessionId, reflectionType, content,
            context, actionItems, effectivenessScore);
    }

    /**
     * 获取反省记录
     *
     * @param sessionId      会话ID（可选）
     * @param reflectionType 反省类型（可选）
     * @param limit          返回数量限制
     * @return 反省记录列表
     */
    public List<com.jimuqu.solonclaw.memory.SessionStore.Reflection> getReflections(
            String sessionId, String reflectionType, int limit) {
        log.debug("获取反省记录: sessionId={}, type={}, limit={}", sessionId, reflectionType, limit);
        return sessionStore.getReflections(sessionId, reflectionType, limit);
    }

    /**
     * 获取最近的反省记录
     */
    public List<com.jimuqu.solonclaw.memory.SessionStore.Reflection> getRecentReflections(int limit) {
        return getReflections(null, null, limit);
    }

    /**
     * 获取特定类型的反省记录
     */
    public List<com.jimuqu.solonclaw.memory.SessionStore.Reflection> getReflectionsByType(
            String reflectionType, int limit) {
        return getReflections(null, reflectionType, limit);
    }

    // ==================== 经验管理 ====================

    /**
     * 保存经验条目
     *
     * @param experienceType 经验类型（如：problem_solving, optimization, best_practice）
     * @param title          经验标题
     * @param content        经验内容
     * @param sourceType     来源类型（如：session, tool, agent）
     * @param sourceId       来源ID
     * @param success        是否成功
     * @param confidence     可信度（0-1）
     * @return 经验条目ID
     */
    public long saveExperience(String experienceType, String title, String content,
                             String sourceType, String sourceId, Boolean success,
                             Double confidence) {
        log.debug("保存经验: type={}, title={}", experienceType, title);
        return sessionStore.saveExperience(experienceType, title, content,
            sourceType, sourceId, success, confidence);
    }

    /**
     * 搜索经验
     *
     * @param experienceType 经验类型（可选）
     * @param keyword        关键词
     * @param limit          返回数量限制
     * @return 经验列表
     */
    public List<com.jimuqu.solonclaw.memory.SessionStore.Experience> searchExperiences(
            String experienceType, String keyword, int limit) {
        log.debug("搜索经验: type={}, keyword={}", experienceType, keyword);
        return sessionStore.searchExperiences(experienceType, keyword, limit);
    }

    /**
     * 根据类型获取经验
     */
    public List<com.jimuqu.solonclaw.memory.SessionStore.Experience> getExperiencesByType(
            String experienceType, int limit) {
        return searchExperiences(experienceType, null, limit);
    }

    /**
     * 根据关键词搜索所有类型的经验
     */
    public List<com.jimuqu.solonclaw.memory.SessionStore.Experience> searchAllExperiences(
            String keyword, int limit) {
        return searchExperiences(null, keyword, limit);
    }

    /**
     * 更新经验使用统计
     *
     * @param experienceId        经验ID
     * @param effectivenessScore 有效性评分（0-1）
     */
    public void updateExperienceUsage(long experienceId, double effectivenessScore) {
        log.debug("更新经验使用: experienceId={}, score={}", experienceId, effectivenessScore);
        sessionStore.updateExperienceUsage(experienceId, effectivenessScore);
    }

    // ==================== 技能需求管理 ====================

    /**
     * 保存技能需求
     *
     * @param reflectionId       关联的反省ID（可选）
     * @param skillName          技能名称
     * @param skillDescription   技能描述
     * @param priority           优先级（1-10，数字越小优先级越高）
     * @param status             状态（pending, in_progress, completed）
     * @param metadata           元数据（JSON格式）
     * @return 技能需求ID
     */
    public long saveSkillRequest(Long reflectionId, String skillName, String skillDescription,
                                Integer priority, String status, String metadata) {
        log.debug("保存技能需求: skillName={}, priority={}", skillName, priority);
        return sessionStore.saveSkillRequest(reflectionId, skillName, skillDescription,
            priority, status, metadata);
    }

    /**
     * 获取技能需求列表
     *
     * @param status 状态筛选（可选）
     * @param limit  返回数量限制
     * @return 技能需求列表
     */
    public List<com.jimuqu.solonclaw.memory.SessionStore.SkillRequest> getSkillRequests(
            String status, int limit) {
        log.debug("获取技能需求: status={}, limit={}", status, limit);
        return sessionStore.getSkillRequests(status, limit);
    }

    /**
     * 获取待处理的技能需求
     */
    public List<com.jimuqu.solonclaw.memory.SessionStore.SkillRequest> getPendingSkillRequests(int limit) {
        return getSkillRequests("pending", limit);
    }

    /**
     * 更新技能需求状态
     *
     * @param requestId 技能需求ID
     * @param status    新状态
     */
    public void updateSkillRequestStatus(long requestId, String status) {
        log.debug("更新技能需求状态: requestId={}, status={}", requestId, status);
        sessionStore.updateSkillRequestStatus(requestId, status);
    }

    // ==================== 便捷方法 ====================

    /**
     * 从任务执行中学习
     * 自动创建反省记录和经验条目
     *
     * @param sessionId       会话ID
     * @param taskDescription 任务描述
     * @param success         是否成功
     * @param lessons         学到的经验
     * @return 创建的反省记录ID
     */
    public long learnFromTask(String sessionId, String taskDescription, boolean success, String lessons) {
        // 保存反省记录
        long reflectionId = saveReflection(
            sessionId,
            success ? "task_success" : "task_failure",
            lessons,
            taskDescription,
            null,
            null
        );

        // 保存经验条目
        saveExperience(
            success ? "successful_task" : "failed_task",
            taskDescription,
            lessons,
            "session",
            sessionId,
            success,
            success ? 0.7 : 0.5
        );

        log.info("从任务中学习: sessionId={}, success={}, lessons={}", sessionId, success, lessons);
        return reflectionId;
    }

    /**
     * 记录错误并学习
     *
     * @param sessionId   会话ID
     * @param errorType   错误类型
     * @param errorMessage 错误消息
     * @param solution    解决方案
     * @return 创建的反省记录ID
     */
    public long learnFromError(String sessionId, String errorType, String errorMessage, String solution) {
        long reflectionId = saveReflection(
            sessionId,
            "error_recovery",
            solution,
            "Error: " + errorType + " - " + errorMessage,
            null,
            null
        );

        // 保存错误处理经验
        saveExperience(
            "error_handling",
            errorType + " 解决方案",
            solution,
            "session",
            sessionId,
            true,
            0.8
        );

        log.warn("从错误中学习: sessionId={}, errorType={}", sessionId, errorType);
        return reflectionId;
    }

    /**
     * 请求新技能
     * 当 Agent 发现需要某个新技能时调用
     *
     * @param reflectionId     关联的反省ID
     * @param skillName        技能名称
     * @param skillDescription 技能描述
     * @param priority         优先级
     * @return 技能需求ID
     */
    public long requestSkill(Long reflectionId, String skillName, String skillDescription, int priority) {
        long requestId = saveSkillRequest(
            reflectionId,
            skillName,
            skillDescription,
            priority,
            "pending",
            null
        );

        log.info("请求新技能: skillName={}, priority={}, requestId={}", skillName, priority, requestId);
        return requestId;
    }
}
