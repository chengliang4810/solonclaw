package com.jimuqu.solonclaw.memory.summary;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

/**
 * 记忆摘要配置
 * <p>
 * 配置 SummarizationInterceptor 和相关的摘要策略
 *
 * @author SolonClaw
 */
@Configuration
public class MemorySummarizationConfig {

    /**
     * 摘要开关
     */
    @Inject("${solonclaw.memory.summarization.enabled:true}")
    private boolean enabled = true;

    /**
     * 触发摘要的最大消息数
     * <p>
     * 当会话中的消息数量超过此值时，触发摘要压缩
     */
    @Inject("${solonclaw.memory.summarization.maxMessages:12}")
    private int maxMessages = 12;

    /**
     * 摘要策略类型
     * <p>
     * 可选值：lstm, keyInfo, hierarchical, composite
     */
    @Inject("${solonclaw.memory.summarization.strategy:composite}")
    private String strategy = "composite";

    /**
     * 摘要最大长度（字符数）
     */
    @Inject("${solonclaw.memory.summarization.maxSummaryLength:500}")
    private int maxSummaryLength = 500;

    /**
     * 是否启用关键信息提取
     */
    @Inject("${solonclaw.memory.summarization.keyInfoEnabled:true}")
    private boolean keyInfoEnabled = true;

    /**
     * 是否启用层级滚动摘要
     */
    @Inject("${solonclaw.memory.summarization.hierarchicalEnabled:true}")
    private boolean hierarchicalEnabled = true;

    /**
     * 关键信息提取提示词
     */
    @Inject("${solonclaw.memory.summarization.keyInfoPrompt:}")
    private String keyInfoPrompt;

    /**
     * 层级摘要提示词
     */
    @Inject("${solonclaw.memory.summarization.hierarchicalPrompt:}")
    private String hierarchicalPrompt;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public int getMaxSummaryLength() {
        return maxSummaryLength;
    }

    public void setMaxSummaryLength(int maxSummaryLength) {
        this.maxSummaryLength = maxSummaryLength;
    }

    public boolean isKeyInfoEnabled() {
        return keyInfoEnabled;
    }

    public void setKeyInfoEnabled(boolean keyInfoEnabled) {
        this.keyInfoEnabled = keyInfoEnabled;
    }

    public boolean isHierarchicalEnabled() {
        return hierarchicalEnabled;
    }

    public void setHierarchicalEnabled(boolean hierarchicalEnabled) {
        this.hierarchicalEnabled = hierarchicalEnabled;
    }

    public String getKeyInfoPrompt() {
        return keyInfoPrompt;
    }

    public void setKeyInfoPrompt(String keyInfoPrompt) {
        this.keyInfoPrompt = keyInfoPrompt;
    }

    public String getHierarchicalPrompt() {
        return hierarchicalPrompt;
    }

    public void setHierarchicalPrompt(String hierarchicalPrompt) {
        this.hierarchicalPrompt = hierarchicalPrompt;
    }
}
