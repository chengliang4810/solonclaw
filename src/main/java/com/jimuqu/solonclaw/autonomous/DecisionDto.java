package com.jimuqu.solonclaw.autonomous;

/**
 * 决策结果 DTO
 * <p>
 * 用于 API 响应的简化决策对象
 *
 * @author SolonClaw
 */
public class DecisionDto {

    private String action;
    private String description;
    private double confidence;
    private String reasoning;

    public DecisionDto() {
    }

    public DecisionDto(String action, String description, double confidence, String reasoning) {
        this.action = action;
        this.description = description;
        this.confidence = confidence;
        this.reasoning = reasoning;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    /**
     * 从 Decision 转换为 DTO
     */
    public static DecisionDto from(DecisionEngine.Decision decision) {
        if (decision == null || decision.nextAction() == null) {
            return new DecisionDto(
                "WAIT",
                "等待新任务",
                1.0,
                "系统处于待命状态，等待新任务或目标"
            );
        }

        return new DecisionDto(
            decision.nextAction().type().name(),
            decision.nextAction().description(),
            0.85,
            decision.analysis() != null ? decision.analysis() : "基于当前状态分析的决策"
        );
    }
}
