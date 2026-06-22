package com.jimuqu.solon.claw.gateway.command;

import java.util.Map;

/** 承载定时任务编辑命令解析结果，隔离命令服务中的请求参数结构。 */
class CronEditRequest {
    /** 记录待编辑的定时任务标识。 */
    final String jobId;

    /** 保存写入定时任务服务的请求体。 */
    final Map<String, Object> body;

    /**
     * 创建定时任务编辑请求。
     *
     * @param jobId 待编辑的定时任务标识。
     * @param body 写入定时任务服务的请求体。
     */
    CronEditRequest(String jobId, Map<String, Object> body) {
        this.jobId = jobId;
        this.body = body;
    }
}
