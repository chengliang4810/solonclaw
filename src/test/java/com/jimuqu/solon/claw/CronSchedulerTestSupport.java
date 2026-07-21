package com.jimuqu.solon.claw;

import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.tool.runtime.CronjobTools;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.annotation.Param;

/** 定时任务工具与调度器测试共用的任务构造和工具元数据辅助方法。 */
final class CronSchedulerTestSupport {
    private CronSchedulerTestSupport() {}

    static CronJobRecord job(String id, String sourceKey) {
        long now = System.currentTimeMillis();
        CronJobRecord job = new CronJobRecord();
        job.setJobId(id);
        job.setName(id);
        job.setCronExpr("* * * * *");
        job.setPrompt("scheduled prompt");
        job.setSourceKey(sourceKey);
        job.setDeliverPlatform("local");
        job.setStatus("ACTIVE");
        job.setNextRunAt(now - 1000L);
        job.setLastRunAt(0L);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return job;
    }

    static String repeat(String value, int times) {
        StringBuilder builder = new StringBuilder(value.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    static Method cronjobToolMethod() {
        for (Method method : CronjobTools.class.getMethods()) {
            if ("cronjob".equals(method.getName())
                    && method.getAnnotation(ToolMapping.class) != null) {
                return method;
            }
        }
        throw new IllegalStateException("cronjob tool method not found");
    }

    static FunctionTool cronjobFunctionTool(ToolProvider provider) {
        for (FunctionTool tool : provider.getTools()) {
            if ("cronjob".equals(tool.name())) {
                return tool;
            }
        }
        throw new IllegalStateException("cronjob function tool not found");
    }

    static String cronjobList(CronjobTools tools) throws Exception {
        return tools.cronjob(
                "list", null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    /** cronjob list 调用，支持 includeDisabled 参数（使用 19 参数重载）。 */
    static String cronjobList(CronjobTools tools, Boolean includeDisabled) throws Exception {
        return tools.cronjob(
                "list",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                includeDisabled,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** cronjob inspect 调用，jobId + 全部可选参数为 null。 */
    static String cronjobInspect(CronjobTools tools, String jobId) throws Exception {
        return tools.cronjob(
                "inspect", jobId, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    /** cronjob inspect 调用，支持 limit 参数。 */
    static String cronjobInspect(CronjobTools tools, String jobId, Integer limit) throws Exception {
        return tools.cronjob(
                "inspect", jobId, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, limit, null);
    }

    static String paramDescription(Method method, String name) {
        for (Parameter parameter : method.getParameters()) {
            Param annotation = parameter.getAnnotation(Param.class);
            if (annotation != null && name.equals(annotation.name())) {
                return annotation.description();
            }
        }
        throw new IllegalStateException("cronjob parameter not found: " + name);
    }

    static boolean paramRequired(Method method, String name) {
        for (Parameter parameter : method.getParameters()) {
            Param annotation = parameter.getAnnotation(Param.class);
            if (annotation != null && name.equals(annotation.name())) {
                return annotation.required();
            }
        }
        throw new IllegalStateException("cronjob parameter not found: " + name);
    }
}
