package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.ProfileTaskRecord;
import com.jimuqu.solon.claw.profile.ProfileRuntimeIdentity;
import com.jimuqu.solon.claw.profile.task.ProfileTaskSubmissionBridge;
import com.jimuqu.solon.claw.support.IdSupport;
import java.util.ArrayList;
import java.util.List;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 普通 Profile 主会话使用的跨智能体任务分配工具。 */
public class ProfileTaskTools {
    /** 当前 Profile 配置。 */
    private final AppConfig appConfig;

    /** 当前会话来源键。 */
    private final String sourceKey;

    /** 创建会话级工具。 */
    public ProfileTaskTools(AppConfig appConfig, String sourceKey) {
        this.appConfig = appConfig;
        this.sourceKey = sourceKey;
    }

    /** 向一个非 default 智能体分配持久协作任务。 */
    @ToolMapping(
            name = "assign_profile_task",
            description =
                    "Assign a persistent collaboration task to an existing non-default agent. The assignee must execute it and cannot delegate it to another profile.")
    public String assign(
            @Param(name = "assignee", description = "目标智能体名称") String assignee,
            @Param(name = "title", description = "任务标题") String title,
            @Param(name = "prompt", description = "完整任务描述和必要上下文") String prompt,
            @Param(name = "dependency_ids", description = "全部完成后才执行的前置任务ID", required = false)
                    List<String> dependencyIds,
            @Param(name = "timeout_minutes", description = "本次执行超时分钟数", required = false)
                    Integer timeoutMinutes)
            throws Exception {
        AgentRunContext run = AgentRunContext.current();
        if (run != null
                && ("profile_task".equalsIgnoreCase(run.getRunKind())
                        || "subagent".equalsIgnoreCase(run.getRunKind()))) {
            throw new IllegalStateException(
                    "Collaboration tasks and subagents cannot assign cross-profile tasks");
        }
        if (StrUtil.isBlank(assignee) || StrUtil.isBlank(title) || StrUtil.isBlank(prompt)) {
            throw new IllegalArgumentException("assignee, title and prompt are required");
        }
        ProfileTaskRecord task = new ProfileTaskRecord();
        task.setTaskId("pt-" + IdSupport.newId());
        task.setSourceProfile(ProfileRuntimeIdentity.resolve(appConfig));
        task.setTargetProfile(assignee.trim());
        task.setSourceKey(sourceKey);
        task.setTitle(title.trim());
        task.setPrompt(prompt.trim());
        task.setMaxAttempts(appConfig.getTask().getProfileTaskMaxAttempts());
        int timeout =
                timeoutMinutes == null
                        ? appConfig.getTask().getProfileTaskDefaultTimeoutMinutes()
                        : timeoutMinutes.intValue();
        task.setTimeoutMinutes(
                Math.min(timeout, appConfig.getTask().getProfileTaskMaxTimeoutMinutes()));
        task.setDependencyIds(
                dependencyIds == null
                        ? new ArrayList<String>()
                        : new ArrayList<String>(dependencyIds));
        return ONode.serialize(ProfileTaskSubmissionBridge.require().save(task));
    }
}
