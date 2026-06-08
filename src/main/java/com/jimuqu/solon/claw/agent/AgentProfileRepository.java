package com.jimuqu.solon.claw.agent;

import java.util.List;

/** 定义Agent角色配置的抽象契约，供不同运行时实现保持一致行为。 */
public interface AgentProfileRepository {
    /**
     * 执行save，服务于Agent角色配置主流程相关逻辑。
     *
     * @param profile 文件或目录路径参数。
     * @return 返回save结果。
     */
    AgentProfile save(AgentProfile profile) throws Exception;

    /**
     * 根据名称查找对应数据。
     *
     * @param agentName Agent名称参数。
     * @return 返回按名称查找得到的结果。
     */
    AgentProfile findByName(String agentName) throws Exception;

    /**
     * 列出全部。
     *
     * @return 返回全部列表。
     */
    List<AgentProfile> listAll() throws Exception;

    /**
     * 根据名称删除对应数据。
     *
     * @param agentName Agent名称参数。
     */
    void deleteByName(String agentName) throws Exception;
}
