package com.jimuqu.solonclaw.learning;

import com.jimuqu.solonclaw.skill.DynamicSkill;
import com.jimuqu.solonclaw.skill.SkillsManager;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 自动技能服务
 * <p>
 * 负责分析技能需求，自动生成和注册新技能
 * 使用 AI 智能判断是否需要创建新技能
 *
 * @author SolonClaw
 */
@Component
public class AutoSkillService {

    private static final Logger log = LoggerFactory.getLogger(AutoSkillService.class);

    @Inject
    private KnowledgeStore knowledgeStore;

    @Inject(required = false)
    private SkillsManager skillsManager;

    @Inject(required = false)
    private ChatModel chatModel;

    /**
     * 技能分析提示词模板
     */
    private static final String SKILL_ANALYSIS_PROMPT = """
        你是一个 AI Agent 的技能分析专家。请分析以下技能需求，判断是否应该创建新技能。

        ## 技能需求
        名称: %s
        描述: %s
        优先级: %d
        来源上下文: %s

        ## 分析要求
        1. 判断这个技能是否应该被创建
        2. 如果创建，设计技能的详细配置
        3. 确定技能应该使用的工具
        4. 设计技能的触发条件和指令

        ## 输出格式（JSON）
        {
            "shouldCreate": true/false,
            "reason": "判断理由",
            "skillConfig": {
                "name": "技能名称",
                "description": "技能描述",
                "instruction": "技能的详细指令",
                "condition": "触发条件，如 contains('关键词1') || contains('关键词2')",
                "tools": ["工具1", "工具2"],
                "enabled": true
            }
        }
        """;

    /**
     * 分析技能需求
     * <p>
     * 使用 AI 分析是否应该创建新技能
     *
     * @param requestId 技能需求ID
     * @return 是否应该创建
     */
    public boolean analyzeSkillRequest(long requestId) {
        log.info("开始分析技能需求: requestId={}", requestId);

        try {
            // 1. 获取技能需求详情
            var requests = knowledgeStore.getSkillRequests(null, 100);
            var request = requests.stream()
                .filter(r -> r.id() == requestId)
                .findFirst()
                .orElse(null);

            if (request == null) {
                log.warn("未找到技能需求: requestId={}", requestId);
                return false;
            }

            // 2. 检查是否已存在同名技能
            if (skillsManager.hasSkill(request.skillName())) {
                log.info("技能已存在: {}", request.skillName());
                knowledgeStore.updateSkillRequestStatus(requestId, "already_exists");
                return false;
            }

            // 3. 使用 AI 分析
            String fullPrompt = SKILL_ANALYSIS_PROMPT.formatted(
                request.skillName(),
                request.skillDescription(),
                request.priority(),
                request.metadata() != null ? request.metadata() : "无上下文"
            );
            fullPrompt = "你是 SolonClaw AI Agent 的技能分析专家。\n\n" + fullPrompt;

            ChatResponse response = chatModel.prompt(fullPrompt).call();

            String analysis = response.getContent();

            // 4. 解析 AI 响应
            Map<String, Object> analysisResult = parseJsonResponse(analysis);

            boolean shouldCreate = (boolean) analysisResult.getOrDefault("shouldCreate", false);

            if (!shouldCreate) {
                String reason = (String) analysisResult.getOrDefault("reason", "未提供理由");
                log.info("AI 判断不应创建技能: requestId={}, reason={}", requestId, reason);
                knowledgeStore.updateSkillRequestStatus(requestId, "rejected");
                return false;
            }

            // 5. 创建技能
            @SuppressWarnings("unchecked")
            Map<String, Object> skillConfigMap = (Map<String, Object>) analysisResult.get("skillConfig");

            if (skillConfigMap == null) {
                log.warn("AI 未提供技能配置: requestId={}", requestId);
                knowledgeStore.updateSkillRequestStatus(requestId, "failed");
                return false;
            }

            boolean created = createSkillFromConfig(skillConfigMap);

            if (created) {
                knowledgeStore.updateSkillRequestStatus(requestId, "completed");
                log.info("技能创建成功: requestId={}, skillName={}", requestId, request.skillName());
            } else {
                knowledgeStore.updateSkillRequestStatus(requestId, "failed");
                log.warn("技能创建失败: requestId={}", requestId);
            }

            return created;

        } catch (Exception e) {
            log.error("分析技能需求失败: requestId={}", requestId, e);
            knowledgeStore.updateSkillRequestStatus(requestId, "error");
            return false;
        }
    }

    /**
     * 处理所有待安装的技能请求
     * <p>
     * 按优先级处理所有 pending 状态的技能需求
     *
     * @return 成功创建的技能数量
     */
    public ProcessResult processPendingSkillRequests() {
        log.info("开始处理待安装的技能请求");

        var pendingRequests = knowledgeStore.getPendingSkillRequests(20);

        if (pendingRequests.isEmpty()) {
            log.info("没有待处理的技能请求");
            return new ProcessResult(0, 0, 0);
        }

        // 按优先级排序（数字越小优先级越高）
        pendingRequests.sort((a, b) -> Integer.compare(a.priority(), b.priority()));

        int successCount = 0;
        int totalCount = pendingRequests.size();
        int waitingConfirmationCount = 0;

        for (var request : pendingRequests) {
            // 先将状态更新为 in_progress
            knowledgeStore.updateSkillRequestStatus(request.id(), "in_progress");

            boolean success = analyzeSkillRequest(request.id());
            if (success) {
                successCount++;
            } else {
                // 检查是否为等待确认状态
                var status = knowledgeStore.getSkillRequests(null, 100).stream()
                    .filter(r -> r.id() == request.id())
                    .findFirst()
                    .map(r -> r.status())
                    .orElse("unknown");

                if ("pending_approval".equals(status)) {
                    waitingConfirmationCount++;
                }
            }
        }

        log.info("处理完成: 共 {} 个请求, 成功创建 {} 个技能, 等待确认 {} 个", totalCount, successCount, waitingConfirmationCount);
        return new ProcessResult(totalCount, successCount, waitingConfirmationCount);
    }

    /**
     * 使用 AI 判断是否需要新技能
     * <p>
     * 根据反省内容判断是否需要创建新技能
     *
     * @param reflectionId 反省记录ID
     * @return 是否需要新技能
     */
    public boolean shouldCreateNewSkill(long reflectionId) {
        try {
            var reflections = knowledgeStore.getReflections(null, null, 1);
            var reflection = reflections.stream()
                .filter(r -> r.id() == reflectionId)
                .findFirst()
                .orElse(null);

            if (reflection == null) {
                return false;
            }

            // 检查反省内容是否包含技能需求关键词
            String content = reflection.content();
            if (content == null) {
                return false;
            }

            // 简单的关键词检测
            String[] skillKeywords = {"需要", "缺少", "应该有", "建议添加", "新技能", "新功能"};
            for (String keyword : skillKeywords) {
                if (content.contains(keyword)) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            log.error("判断是否需要新技能失败", e);
            return false;
        }
    }

    /**
     * 自动创建技能
     * <p>
     * 根据反省记录自动创建技能
     *
     * @param reflectionId 反省记录ID
     * @param skillName    技能名称
     * @param description  技能描述
     * @return 是否创建成功
     */
    public boolean autoCreateSkill(long reflectionId, String skillName, String description) {
        log.info("自动创建技能: reflectionId={}, skillName={}", reflectionId, skillName);

        try {
            // 1. 先创建技能需求
            long requestId = knowledgeStore.saveSkillRequest(
                reflectionId,
                skillName,
                description,
                5,  // 默认优先级
                "pending",
                null
            );

            // 2. 分析并创建
            return analyzeSkillRequest(requestId);

        } catch (Exception e) {
            log.error("自动创建技能失败", e);
            return false;
        }
    }

    /**
     * 从配置创建技能
     */
    private boolean createSkillFromConfig(Map<String, Object> configMap) {
        try {
            String name = (String) configMap.get("name");
            String description = (String) configMap.get("description");
            String instruction = (String) configMap.get("instruction");
            String condition = (String) configMap.get("condition");
            Boolean enabled = (Boolean) configMap.get("enabled");

            @SuppressWarnings("unchecked")
            List<String> tools = (List<String>) configMap.get("tools");

            DynamicSkill.SkillConfig config = new DynamicSkill.SkillConfig(
                name,
                description,
                instruction,
                condition,
                tools,
                enabled != null ? enabled : true
            );

            return skillsManager.addSkill(config);

        } catch (Exception e) {
            log.error("从配置创建技能失败", e);
            return false;
        }
    }

    /**
     * 解析 JSON 响应
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonResponse(String jsonResponse) {
        try {
            // 简化实现：返回默认值，实际项目中应该使用 proper JSON parser
            // TODO: 使用合适的 JSON 库解析
            log.debug("JSON 响应: {}", jsonResponse);
            return Map.of("shouldCreate", false, "reason", "简化实现");
        } catch (Exception e) {
            log.warn("解析 AI 响应失败", e);
            return Map.of("shouldCreate", false, "reason", "解析失败");
        }
    }

    /**
     * 处理结果记录
     */
    public record ProcessResult(
            int totalProcessed,
            int approved,
            int waitingConfirmation
    ) {
    }
}