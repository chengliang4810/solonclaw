package com.jimuqu.solonclaw.memory.summary;

import cn.hutool.core.util.StrUtil;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.agent.react.intercept.summarize.*;
import org.noear.solon.annotation.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 摘要策略工厂
 * <p>
 * 根据配置创建合适的摘要策略组合
 *
 * @author SolonClaw
 */
@Component
public class SummarizationStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(SummarizationStrategyFactory.class);

    private static final String DEFAULT_HIERARCHICAL_PROMPT = """
            请将以下对话内容整合为一段连贯的摘要：
            - 保留关键信息和上下文
            - 突出重要的事件和决策
            - 省略冗余的思考过程
            - 保持时序逻辑清晰

            摘要长度控制在 {max_length} 字符以内。
            """;

    /**
     * 创建摘要策略
     *
     * @param config   配置
     * @param chatModel 聊天模型
     * @return 摘要策略实例
     */
    public SummarizationStrategy createStrategy(MemorySummarizationConfig config, ChatModel chatModel) {
        if (!config.isEnabled()) {
            log.info("记忆摘要功能已禁用");
            return null;
        }

        String strategyType = config.getStrategy();

        return switch (strategyType.toLowerCase()) {
            case "lstm" -> createLLMStrategy(config, chatModel);
            case "keyinfo" -> createKeyInfoStrategy(config, chatModel);
            case "hierarchical" -> createHierarchicalStrategy(config, chatModel);
            case "composite" -> createCompositeStrategy(config, chatModel);
            default -> {
                log.warn("未知的摘要策略类型: {}，使用默认 composite 策略", strategyType);
                yield createCompositeStrategy(config, chatModel);
            }
        };
    }

    /**
     * 创建 LLM 摘要策略
     */
    private SummarizationStrategy createLLMStrategy(MemorySummarizationConfig config, ChatModel chatModel) {
        log.info("创建 LLM 摘要策略");
        return new LLMSummarizationStrategy(chatModel);
    }

    /**
     * 创建关键信息提取策略
     */
    private SummarizationStrategy createKeyInfoStrategy(MemorySummarizationConfig config, ChatModel chatModel) {
        log.info("创建关键信息提取策略");

        String prompt = config.getKeyInfoPrompt();
        KeyInfoExtractionStrategy keyInfoExtractionStrategy = new KeyInfoExtractionStrategy(chatModel);
        if (StrUtil.isNotBlank(prompt)) {
            keyInfoExtractionStrategy.setSystemInstruction(prompt);
        }
        return keyInfoExtractionStrategy;
    }

    /**
     * 创建层级滚动摘要策略
     */
    private SummarizationStrategy createHierarchicalStrategy(MemorySummarizationConfig config, ChatModel chatModel) {
        log.info("创建层级滚动摘要策略，最大摘要长度: {}", config.getMaxSummaryLength());

        HierarchicalSummarizationStrategy strategy = new HierarchicalSummarizationStrategy(chatModel);
        strategy.setMaxSummaryLength(config.getMaxSummaryLength());

        String prompt = config.getHierarchicalPrompt();
        if (prompt != null && !prompt.isBlank()) {
            strategy.setSystemInstruction(prompt);
        } else {
            strategy.setSystemInstruction(
                DEFAULT_HIERARCHICAL_PROMPT.replace("{max_length}", String.valueOf(config.getMaxSummaryLength()))
            );
        }

        return strategy;
    }

    /**
     * 创建组合策略（推荐）
     * <p>
     * 顺序：VectorStore（可选）-> KeyInfo -> Hierarchical
     */
    public SummarizationStrategy createCompositeStrategy(MemorySummarizationConfig config, ChatModel chatModel) {
        log.info("创建组合摘要策略");

        CompositeSummarizationStrategy composite = new CompositeSummarizationStrategy();

        // 1. 关键信息提取（事实看板）
        if (config.isKeyInfoEnabled()) {
            SummarizationStrategy keyInfoStrategy = createKeyInfoStrategy(config, chatModel);
            composite.addStrategy(keyInfoStrategy);
            log.info("  - 已添加：关键信息提取策略");
        }

        // 2. 层级滚动摘要（全局进度）
        if (config.isHierarchicalEnabled()) {
            SummarizationStrategy hierarchicalStrategy = createHierarchicalStrategy(config, chatModel);
            composite.addStrategy(hierarchicalStrategy);
            log.info("  - 已添加：层级滚动摘要策略");
        }

        // 3. 如果两者都禁用，至少添加一个 LLM 基础策略
        if (!config.isKeyInfoEnabled() && !config.isHierarchicalEnabled()) {
            SummarizationStrategy llmStrategy = createLLMStrategy(config, chatModel);
            composite.addStrategy(llmStrategy);
            log.info("  - 已添加：LLM 基础摘要策略（默认）");
        }

        return composite;
    }
}
