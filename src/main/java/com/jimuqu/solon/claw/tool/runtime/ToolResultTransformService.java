package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** Lightweight generic tool-result transform hook for repo-owned extensions. */
public class ToolResultTransformService {
    private final List<ToolResultTransformer> transformers =
            new CopyOnWriteArrayList<ToolResultTransformer>();

    public void addTransformer(ToolResultTransformer transformer) {
        if (transformer != null) {
            transformers.add(transformer);
        }
    }

    public void removeTransformer(ToolResultTransformer transformer) {
        if (transformer != null) {
            transformers.remove(transformer);
        }
    }

    public String transform(ToolResultContext context) {
        String original = context == null ? "" : StrUtil.nullToEmpty(context.getResult());
        if (transformers.isEmpty() || context == null) {
            return original;
        }
        for (ToolResultTransformer transformer : transformers) {
            try {
                String transformed = transformer.transform(context);
                if (transformed != null) {
                    return transformed;
                }
            } catch (Exception ignored) {
            }
        }
        return original;
    }

    public ReActInterceptor buildInterceptor() {
        return new TransformInterceptor(this);
    }

    public interface ToolResultTransformer {
        String transform(ToolResultContext context) throws Exception;
    }

    public static class ToolResultContext {
        private final String toolName;
        private final Map<String, Object> args;
        private final String result;
        private final String sessionId;
        private final String toolCallId;
        private final long durationMs;

        public ToolResultContext(
                String toolName,
                Map<String, Object> args,
                String result,
                String sessionId,
                String toolCallId,
                long durationMs) {
            this.toolName = toolName;
            this.args = args;
            this.result = StrUtil.nullToEmpty(result);
            this.sessionId = sessionId;
            this.toolCallId = toolCallId;
            this.durationMs = durationMs;
        }

        public String getToolName() {
            return toolName;
        }

        public Map<String, Object> getArgs() {
            return args;
        }

        public String getResult() {
            return result;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getToolCallId() {
            return toolCallId;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }

    private static class TransformInterceptor implements ReActInterceptor {
        private static final String EXTRA_TOOL_CALL_ID_PREFIX =
                "solonclaw.tool_result_transform.tool_call_id.";

        private final ToolResultTransformService service;

        private TransformInterceptor(ToolResultTransformService service) {
            this.service = service;
        }

        @Override
        public void onReason(ReActTrace trace, AssistantMessage message) {
            captureToolCallIds(trace, message);
        }

        @Override
        public void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
            if (trace != null) {
                trace.setExtra(extraArgsKey(toolName), args);
            }
        }

        @Override
        public void onObservation(
                ReActTrace trace, String toolName, String result, long durationMs) {
            if (trace == null || service == null) {
                return;
            }
            String original = StrUtil.nullToEmpty(trace.getLastObservation());
            if (StrUtil.isEmpty(original)) {
                original = StrUtil.nullToEmpty(result);
            }
            ToolResultContext context =
                    new ToolResultContext(
                            toolName,
                            argsFor(trace, toolName),
                            original,
                            trace.getSession() == null ? null : trace.getSession().getSessionId(),
                            toolCallId(trace, toolName),
                            durationMs);
            trace.setLastObservation(service.transform(context));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> argsFor(ReActTrace trace, String toolName) {
            Object value = trace.getExtra(extraArgsKey(toolName));
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            return null;
        }

        private static String extraArgsKey(String toolName) {
            return "solonclaw.tool_result_transform.args."
                    + StrUtil.blankToDefault(toolName, "unknown");
        }

        private static void captureToolCallIds(ReActTrace trace, AssistantMessage message) {
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
                trace.setExtra(
                        EXTRA_TOOL_CALL_ID_PREFIX + key(call.getName(), base + i), call.getId());
            }
        }

        private static String toolCallId(ReActTrace trace, String toolName) {
            int count = Math.max(0, trace.getToolCallCount());
            String captured = trace.getExtraAs(EXTRA_TOOL_CALL_ID_PREFIX + key(toolName, count));
            if (StrUtil.isBlank(captured)) {
                captured = trace.getExtraAs(EXTRA_TOOL_CALL_ID_PREFIX + key(toolName, count - 1));
            }
            if (StrUtil.isNotBlank(captured)) {
                return captured;
            }
            return StrUtil.blankToDefault(toolName, "tool") + "-" + count;
        }

        private static String key(String toolName, int index) {
            return StrUtil.blankToDefault(toolName, "unknown") + "." + Math.max(0, index);
        }
    }
}
