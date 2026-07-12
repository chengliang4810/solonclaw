package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.model.DelegationResult;
import com.jimuqu.solon.claw.core.model.DelegationTask;
import java.util.List;
import java.util.Map;

/** 子代理委托服务接口。 */
public interface DelegationService {
    /** 单任务委托。 */
    DelegationResult delegateSingle(String sourceKey, String prompt, String context)
            throws Exception;

    /**
     * 执行委托Single相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param task 任务参数。
     * @return 返回委托Single结果。
     */
    default DelegationResult delegateSingle(String sourceKey, DelegationTask task)
            throws Exception {
        return delegateSingle(
                sourceKey,
                task == null ? null : task.getPrompt(),
                task == null ? null : task.getContext());
    }

    /** 批量并行委托。 */
    List<DelegationResult> delegateBatch(String sourceKey, List<DelegationTask> tasks)
            throws Exception;

    /** 顶层会话返回 true；子 Agent 内部委派返回 false，以便 orchestrator 同步汇总工作结果。 */
    default boolean shouldRunInBackground() {
        return false;
    }

    /**
     * 调度顶层后台委派；实现必须立即返回句柄，并在完成后把结果回流父会话。
     *
     * @param sourceKey 父会话来源键。
     * @param tasks 已校验的结构化任务。
     * @return 后台调度句柄。
     */
    default Map<String, Object> delegateInBackground(String sourceKey, List<DelegationTask> tasks) {
        throw new UnsupportedOperationException("Background delegation unavailable");
    }

    /**
     * 取消指定父会话仍在执行或排队的后台委派。
     *
     * @param parentSessionId 发起委派的父会话标识。
     * @return 本次新取消的后台委派数量。
     */
    default int cancelBackgroundForSession(String parentSessionId) {
        return 0;
    }

    /** 关闭当前 Profile 内的后台委派执行资源。 */
    default void shutdown() {}

    /**
     * 写入Spawn Paused。
     *
     * @param paused paused 参数。
     */
    default void setSpawnPaused(boolean paused) {}

    /**
     * 判断是否Spawn Paused。
     *
     * @return 如果Spawn Paused满足条件则返回 true，否则返回 false。
     */
    default boolean isSpawnPaused() {
        return false;
    }

    /**
     * 中断子Agent。
     *
     * @param subagentId 子Agent标识。
     * @return 返回interrupt Subagent结果。
     */
    default boolean interruptSubagent(String subagentId) {
        return false;
    }

    /**
     * 执行activeSubagents相关逻辑。
     *
     * @return 返回active Subagents结果。
     */
    default List<Map<String, Object>> activeSubagents() {
        return java.util.Collections.emptyList();
    }
}
