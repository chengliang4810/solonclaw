package com.jimuqu.claw.agent.runtime.support;

import com.jimuqu.claw.agent.job.AgentTurnSpec;
import com.jimuqu.claw.agent.job.JobDeliveryMode;
import com.jimuqu.claw.agent.model.enums.RuntimeSourceKind;
import com.jimuqu.claw.agent.model.route.ReplyTarget;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 描述一次隔离 agent turn 执行请求。
 */
@Data
@NoArgsConstructor
public class AgentTurnRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private RuntimeSourceKind sourceKind = RuntimeSourceKind.JOB_AGENT_TURN;
    private String jobName;
    private String boundSessionKey;
    private ReplyTarget boundReplyTarget;
    private JobDeliveryMode deliveryMode = JobDeliveryMode.NONE;
    private AgentTurnSpec agentTurn = new AgentTurnSpec();
}
