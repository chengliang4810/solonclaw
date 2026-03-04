package com.jimuqu.solonclaw.memory.summary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.agent.react.intercept.summarize.CompositeSummarizationStrategy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 摘要策略工厂测试
 *
 * @author SolonClaw
 */
class SummarizationStrategyFactoryTest {

    private SummarizationStrategyFactory factory;
    private ChatModel mockChatModel;
    private MemorySummarizationConfig config;

    @BeforeEach
    void setUp() {
        factory = new SummarizationStrategyFactory();
        mockChatModel = mock(ChatModel.class);
        config = new MemorySummarizationConfig();
    }

    @Test
    void testCreateStrategyWhenDisabled() {
        // Given
        config.setEnabled(false);

        // When
        SummarizationStrategy strategy = factory.createStrategy(config, mockChatModel);

        // Then
        assertNull(strategy, "禁用时应返回 null");
    }

    @Test
    void testCreateLLMStrategy() {
        // Given
        config.setEnabled(true);
        config.setStrategy("lstm");

        // When
        SummarizationStrategy strategy = factory.createStrategy(config, mockChatModel);

        // Then
        assertNotNull(strategy, "LLM 策略不应为 null");
    }

    @Test
    void testCreateKeyInfoStrategy() {
        // Given
        config.setEnabled(true);
        config.setStrategy("keyInfo");

        // When
        SummarizationStrategy strategy = factory.createStrategy(config, mockChatModel);

        // Then
        assertNotNull(strategy, "关键信息提取策略不应为 null");
    }

    @Test
    void testCreateHierarchicalStrategy() {
        // Given
        config.setEnabled(true);
        config.setStrategy("hierarchical");
        config.setMaxSummaryLength(500);

        // When
        SummarizationStrategy strategy = factory.createStrategy(config, mockChatModel);

        // Then
        assertNotNull(strategy, "层级摘要策略不应为 null");
    }

    @Test
    void testCreateCompositeStrategy() {
        // Given
        config.setEnabled(true);
        config.setStrategy("composite");
        config.setKeyInfoEnabled(true);
        config.setHierarchicalEnabled(true);

        // When
        SummarizationStrategy strategy = factory.createStrategy(config, mockChatModel);

        // Then
        assertNotNull(strategy, "组合策略不应为 null");
        assertTrue(strategy instanceof CompositeSummarizationStrategy, "应该是 CompositeSummarizationStrategy 类型");
    }

    @Test
    void testCreateCompositeStrategyWithOnlyKeyInfo() {
        // Given
        config.setEnabled(true);
        config.setStrategy("composite");
        config.setKeyInfoEnabled(true);
        config.setHierarchicalEnabled(false);

        // When
        SummarizationStrategy strategy = factory.createStrategy(config, mockChatModel);

        // Then
        assertNotNull(strategy, "组合策略不应为 null");
        assertTrue(strategy instanceof CompositeSummarizationStrategy, "应该是 CompositeSummarizationStrategy 类型");
    }

    @Test
    void testCreateCompositeStrategyWithOnlyHierarchical() {
        // Given
        config.setEnabled(true);
        config.setStrategy("composite");
        config.setKeyInfoEnabled(false);
        config.setHierarchicalEnabled(true);

        // When
        SummarizationStrategy strategy = factory.createStrategy(config, mockChatModel);

        // Then
        assertNotNull(strategy, "组合策略不应为 null");
        assertTrue(strategy instanceof CompositeSummarizationStrategy, "应该是 CompositeSummarizationStrategy 类型");
    }

    @Test
    void testCreateCompositeStrategyWithBothDisabled() {
        // Given
        config.setEnabled(true);
        config.setStrategy("composite");
        config.setKeyInfoEnabled(false);
        config.setHierarchicalEnabled(false);

        // When
        SummarizationStrategy strategy = factory.createStrategy(config, mockChatModel);

        // Then
        assertNotNull(strategy, "组合策略不应为 null（应回退到 LLM 策略）");
        assertTrue(strategy instanceof CompositeSummarizationStrategy, "应该是 CompositeSummarizationStrategy 类型");
    }

    @Test
    void testCreateStrategyWithUnknownType() {
        // Given
        config.setEnabled(true);
        config.setStrategy("unknown_type");

        // When
        SummarizationStrategy strategy = factory.createStrategy(config, mockChatModel);

        // Then
        assertNotNull(strategy, "未知策略类型应回退到 composite");
        assertTrue(strategy instanceof CompositeSummarizationStrategy, "应该回退到 CompositeSummarizationStrategy");
    }

    @Test
    void testConfigMaxMessages() {
        // Given
        config.setMaxMessages(15);

        // When & Then
        assertEquals(15, config.getMaxMessages(), "maxMessages 应该被正确设置");
    }

    @Test
    void testConfigMaxSummaryLength() {
        // Given
        config.setMaxSummaryLength(1000);

        // When & Then
        assertEquals(1000, config.getMaxSummaryLength(), "maxSummaryLength 应该被正确设置");
    }

    @Test
    void testConfigCustomPrompts() {
        // Given
        String customKeyInfoPrompt = "自定义关键信息提示词";
        String customHierarchicalPrompt = "自定义层级摘要提示词";

        config.setKeyInfoPrompt(customKeyInfoPrompt);
        config.setHierarchicalPrompt(customHierarchicalPrompt);

        // When & Then
        assertEquals(customKeyInfoPrompt, config.getKeyInfoPrompt(), "keyInfoPrompt 应该被正确设置");
        assertEquals(customHierarchicalPrompt, config.getHierarchicalPrompt(), "hierarchicalPrompt 应该被正确设置");
    }
}
