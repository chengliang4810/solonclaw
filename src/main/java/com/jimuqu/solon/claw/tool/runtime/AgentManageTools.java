package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供当前会话活跃子 Agent 查询和受审批中断操作。 */
public class AgentManageTools {
    /** 子 Agent 委托服务。 */
    private final DelegationService delegationService;

    /** 当前父会话来源键，用于隔离子 Agent 查询和中断。 */
    private final String sourceKey;

    /**
     * 创建 Agent 管理工具。
     *
     * @param delegationService 子 Agent 委托服务。
     * @param sourceKey 当前父会话来源键。
     */
    public AgentManageTools(DelegationService delegationService, String sourceKey) {
        this.delegationService = delegationService;
        this.sourceKey = sourceKey;
    }

    /**
     * 查询或控制当前运行时的子 Agent。
     *
     * @param action 操作名称。
     * @param subagentId 待中断的子 Agent 标识。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "agent_manage",
            description =
                    "Inspect active subagents created by the current conversation, or interrupt one after explicit user approval. Actions: status, list, interrupt.")
    public String agentManage(
            @Param(name = "action", description = "status, list, interrupt") String action,
            @Param(
                            name = "subagent_id",
                            required = false,
                            description = "Subagent id for action=interrupt")
                    String subagentId) {
        if (delegationService == null) {
            return ToolResultEnvelope.error("agent service unavailable").toJson();
        }
        try {
            Map<String, Object> result = run(action, subagentId);
            return ToolResultEnvelope.ok("Agent 管理完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            return ToolResultEnvelope.error(SecretRedactor.redact(e.getMessage(), 1000)).toJson();
        }
    }

    /**
     * 执行 Agent 管理动作。
     *
     * @param action 操作名称。
     * @param subagentId 待中断的子 Agent 标识。
     * @return 返回动作结果。
     */
    private Map<String, Object> run(String action, String subagentId) {
        String normalized =
                StrUtil.blankToDefault(action, "status").trim().toLowerCase(Locale.ROOT);
        if ("status".equals(normalized) || "list".equals(normalized)) {
            return status();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if ("interrupt".equals(normalized)) {
            if (StrUtil.isBlank(subagentId)) {
                throw new IllegalArgumentException("subagent_id is required for interrupt");
            }
            result.put("subagent_id", subagentId.trim());
            result.put(
                    "interrupt_requested",
                    Boolean.valueOf(
                            delegationService.interruptSubagent(sourceKey, subagentId.trim())));
            return result;
        }
        throw new IllegalArgumentException("Unsupported agent action: " + normalized);
    }

    /**
     * 构建不包含来源键和输出正文的子 Agent 状态快照。
     *
     * @return 返回安全状态快照。
     */
    private Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("spawn_paused", Boolean.valueOf(delegationService.isSpawnPaused()));
        List<Map<String, Object>> active = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : delegationService.activeSubagents(sourceKey)) {
            if (item == null) {
                continue;
            }
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            copy(item, summary, "subagent_id");
            copy(item, summary, "parent_run_id");
            copy(item, summary, "child_run_id");
            copy(item, summary, "status");
            copy(item, summary, "depth");
            copy(item, summary, "heartbeat_at");
            active.add(summary);
        }
        result.put("active_subagents", active);
        return result;
    }

    /**
     * 复制状态快照中的允许字段。
     *
     * @param source 原始状态。
     * @param target 安全状态。
     * @param key 字段名。
     */
    private void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }
}
