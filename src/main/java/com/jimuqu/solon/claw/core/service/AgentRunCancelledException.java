package com.jimuqu.solon.claw.core.service;

/** 表示Agent运行Cancelled异常，用于向上层传递可识别的失败原因。 */
public class AgentRunCancelledException extends RuntimeException {
    /** 创建Agent运行Cancelled Exception实例。 */
    public AgentRunCancelledException() {
        super("当前任务已停止。");
    }
}
