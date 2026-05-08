package com.jimuqu.solon.claw.tool.runtime;

/** Optional auxiliary judge for smart command approval. */
public interface SmartApprovalJudge {
    SmartApprovalDecision judge(String toolName, String command, String description);
}
