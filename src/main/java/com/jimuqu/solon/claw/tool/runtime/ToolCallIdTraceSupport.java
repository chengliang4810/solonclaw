package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.List;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** 工具调用标识追踪辅助逻辑，统一在 ReActTrace 中记录模型返回的 tool call id。 */
final class ToolCallIdTraceSupport {
    /** 工具类不允许创建实例。 */
    private ToolCallIdTraceSupport() {}

    /**
     * 捕获 Assistant 消息中的工具调用标识。
     *
     * @param trace ReAct 运行轨迹。
     * @param message Assistant 消息。
     * @param prefix extra 键前缀。
     */
    static void capture(ReActTrace trace, AssistantMessage message, String prefix) {
        if (trace == null || message == null || message.getToolCalls() == null) {
            return;
        }
        int base = Math.max(0, trace.getToolCallCount());
        List<ToolCall> calls = message.getToolCalls();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (call == null || StrUtil.isBlank(call.getId())) {
                continue;
            }
            trace.setExtra(prefix + key(call.getName(), base + i), call.getId());
        }
    }

    /**
     * 读取当前或上一轮工具调用标识。
     *
     * @param trace ReAct 运行轨迹。
     * @param prefix extra 键前缀。
     * @param toolName 工具名称。
     * @return 返回捕获到的工具调用标识，未捕获时返回空字符串。
     */
    static String captured(ReActTrace trace, String prefix, String toolName) {
        int count = Math.max(0, trace.getToolCallCount());
        String captured = trace.getExtraAs(prefix + key(toolName, count));
        if (StrUtil.isBlank(captured)) {
            captured = trace.getExtraAs(prefix + key(toolName, count - 1));
        }
        return StrUtil.nullToEmpty(captured);
    }

    /**
     * 生成工具调用 extra 键。
     *
     * @param toolName 工具名称。
     * @param index 工具调用序号。
     * @return 返回 extra 键片段。
     */
    private static String key(String toolName, int index) {
        return StrUtil.blankToDefault(toolName, "unknown") + "." + Math.max(0, index);
    }
}
