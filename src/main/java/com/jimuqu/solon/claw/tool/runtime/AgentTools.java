package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.SecretRedactor;
import lombok.RequiredArgsConstructor;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** Agent 管理工具。 */
@RequiredArgsConstructor
public class AgentTools {
    /** 注入Agent角色配置服务，用于调用对应业务能力。 */
    private final AgentProfileService agentProfileService;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 记录Agent中的来源键。 */
    private final String sourceKey;

    /**
     * 执行AgentManage相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回Agent Manage结果。
     */
    @ToolMapping(
            name = "agent_manage",
            description =
                    "Manage named Agents with slash-command compatible args: list, use <name>, create <name> [role], show <name>, model/tools/skills/memory <name> ..., delete <name>. The built-in default Agent cannot be edited or deleted.")
    public String agentManage(
            @Param(
                            name = "args",
                            description =
                                    "Agent command arguments, for example: list, create coder 你是代码助手, use coder, tools coder read_file,skills_list")
                    String args) {
        try {
            String result = agentProfileService.handleCommand(args, sessionRepository, sourceKey);
            return ToolResultEnvelope.ok("Agent 管理完成")
                    .preview(SecretRedactor.redact(result, 2000))
                    .toJson();
        } catch (Exception e) {
            return ToolResultEnvelope.error(
                            SecretRedactor.redact(
                                    e.getMessage() == null
                                            ? e.getClass().getSimpleName()
                                            : e.getMessage(),
                                    1000))
                    .toJson();
        }
    }
}
