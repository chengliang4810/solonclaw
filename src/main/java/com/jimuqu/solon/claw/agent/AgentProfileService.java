package com.jimuqu.solon.claw.agent;

/**
 * 旧会话 Agent 配置机制的依赖占位。
 *
 * <p>管理命令、Dashboard、工具和运行时切换均已移除；该类型仅用于逐步收敛现有构造器签名，不能提供任何用户能力。
 */
public final class AgentProfileService {
    /** 保留现有依赖注入签名，不再保存或操作旧 Agent 配置。 */
    public AgentProfileService(
            AgentProfileRepository repository, AgentRuntimeService runtimeService) {}
}
