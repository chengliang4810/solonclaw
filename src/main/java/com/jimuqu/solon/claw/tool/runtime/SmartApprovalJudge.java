package com.jimuqu.solon.claw.tool.runtime;

/** Optional auxiliary judge for Hermes-style smart command approval. */
public interface SmartApprovalJudge {
    SmartApprovalDecision judge(String toolName, String command, String description);
}
