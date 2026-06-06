package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.agent.AgentRuntimeScope;

/** 上下文拼装服务接口。 */
public interface ContextService {
    /** 构建来源键对应的系统提示词。 */
    String buildSystemPrompt(String sourceKey);

    /**
     * 构建System提示词。
     *
     * @param sourceKey 渠道来源键。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回创建好的System提示词。
     */
    default String buildSystemPrompt(String sourceKey, AgentRuntimeScope agentScope) {
        return buildSystemPrompt(sourceKey);
    }
}
