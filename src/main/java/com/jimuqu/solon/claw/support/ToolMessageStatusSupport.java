package com.jimuqu.solon.claw.support;

import org.noear.solon.ai.chat.message.ToolMessage;

/** 工具消息执行状态的持久化约定，状态写入 ToolMessage metadata 后随会话 NDJSON 保存。 */
public final class ToolMessageStatusSupport {
    /** ToolMessage metadata 中保存工具终态的稳定字段名。 */
    public static final String STATUS_METADATA_KEY = "solonclaw.tool.status";

    /** 工具正常完成状态。 */
    public static final String STATUS_DONE = "done";

    /** 工具执行失败状态。 */
    public static final String STATUS_ERROR = "error";

    /** 禁止创建工具状态辅助类实例。 */
    private ToolMessageStatusSupport() {}

    /**
     * 标记工具消息的已确认终态，供 Dashboard 会话回放使用。
     *
     * @param message 待持久化的工具消息。
     * @param failed 是否以失败结束。
     */
    public static void mark(ToolMessage message, boolean failed) {
        if (message != null) {
            message.addMetadata(STATUS_METADATA_KEY, failed ? STATUS_ERROR : STATUS_DONE);
        }
    }

    /**
     * 读取工具消息终态；旧会话缺少 metadata 时按历史行为视为正常完成。
     *
     * @param message 从 NDJSON 还原的工具消息。
     * @return 返回 done 或 error。
     */
    public static String statusOf(ToolMessage message) {
        if (message != null && message.hasMetadata(STATUS_METADATA_KEY)) {
            Object value = message.getMetadataAs(STATUS_METADATA_KEY);
            if (STATUS_ERROR.equalsIgnoreCase(String.valueOf(value))) {
                return STATUS_ERROR;
            }
        }
        return STATUS_DONE;
    }
}
