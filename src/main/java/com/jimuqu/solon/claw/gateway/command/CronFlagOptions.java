package com.jimuqu.solon.claw.gateway.command;

import java.util.ArrayList;
import java.util.List;

/** 承载定时任务 slash 命令解析出的选项状态，避免命令服务继续持有庞大参数对象。 */
class CronFlagOptions {
    /** 记录定时任务名称。 */
    String name;

    /** 记录定时任务结果投递目标。 */
    String deliver;

    /** 记录投递目标中的会话标识。 */
    String deliverChatId;

    /** 记录投递目标中的线程标识。 */
    String deliverThreadId;

    /** 标记是否清空投递会话标识。 */
    boolean clearDeliverChatId;

    /** 标记是否清空投递线程标识。 */
    boolean clearDeliverThreadId;

    /** 记录重复执行次数。 */
    Integer repeat;

    /** 标记是否清空重复执行次数。 */
    boolean clearRepeat;

    /** 记录查询或展示条数限制。 */
    Integer limit;

    /** 记录暂停、恢复或变更定时任务时的原因说明。 */
    String reason;

    /** 记录定时任务触发类型。 */
    String triggerType;

    /** 保存命令中显式指定的技能列表。 */
    final List<String> skills = new ArrayList<String>();

    /** 保存命令中新增的技能列表。 */
    final List<String> addSkills = new ArrayList<String>();

    /** 保存命令中移除的技能列表。 */
    final List<String> removeSkills = new ArrayList<String>();

    /** 标记是否清空技能配置。 */
    boolean clearSkills;

    /** 标记命令是否作用于全部定时任务。 */
    boolean all;

    /** 记录定时任务提示词正文。 */
    String prompt;

    /** 记录定时任务调度表达式。 */
    String schedule;

    /** 记录定时任务脚本内容。 */
    String script;

    /** 记录定时任务执行工作目录。 */
    String workdir;

    /** 记录定时任务上下文来源。 */
    String contextFrom;

    /** 记录定时任务依赖关系。 */
    String dependsOn;

    /** 记录定时任务启用的工具集。 */
    String enabledToolsets;

    /** 记录定时任务模型名称。 */
    String model;

    /** 记录定时任务模型提供方。 */
    String provider;

    /** 记录定时任务模型基础 URL。 */
    String baseUrl;

    /** 记录定时任务状态过滤条件。 */
    String status;

    /** 记录定时任务目标状态。 */
    String state;

    /** 记录定时任务暂停原因。 */
    String pausedReason;

    /** 标记是否清空模型配置。 */
    boolean clearModel;

    /** 标记是否清空模型提供方配置。 */
    boolean clearProvider;

    /** 标记是否清空模型基础 URL 配置。 */
    boolean clearBaseUrl;

    /** 标记是否清空脚本配置。 */
    boolean clearScript;

    /** 标记是否清空工作目录配置。 */
    boolean clearWorkdir;

    /** 标记是否清空上下文来源配置。 */
    boolean clearContextFrom;

    /** 标记是否清空依赖关系配置。 */
    boolean clearDependsOn;

    /** 标记是否清空工具集配置。 */
    boolean clearToolsets;

    /** 标记定时任务是否禁用 Agent 主循环。 */
    boolean noAgent;

    /** 标记定时任务是否启用 Agent 主循环。 */
    boolean agent;

    /** 标记是否包装定时任务响应。 */
    boolean wrapResponse;

    /** 标记是否输出原始结果。 */
    boolean raw;

    /** 标记是否输出 JSON 结果。 */
    boolean json;

    /** 保存命令解析后剩余的位置参数。 */
    final List<String> positionals = new ArrayList<String>();
}
