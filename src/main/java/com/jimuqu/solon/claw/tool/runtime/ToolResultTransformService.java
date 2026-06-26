package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供工具结果Transform相关业务能力，封装调用方不需要感知的运行细节。 */
public class ToolResultTransformService {
    /** 记录工具结果转换器降级的低敏诊断日志，不输出工具结果正文。 */
    private static final Logger log = LoggerFactory.getLogger(ToolResultTransformService.class);

    /** 保存transformers集合，维持调用顺序或去重语义。 */
    private final List<ToolResultTransformer> transformers =
            new CopyOnWriteArrayList<ToolResultTransformer>();

    /**
     * 追加Transformer。
     *
     * @param transformer transformer 参数。
     */
    public void addTransformer(ToolResultTransformer transformer) {
        if (transformer != null) {
            transformers.add(transformer);
        }
    }

    /**
     * 移除Transformer。
     *
     * @param transformer transformer 参数。
     */
    public void removeTransformer(ToolResultTransformer transformer) {
        if (transformer != null) {
            transformers.remove(transformer);
        }
    }

    /**
     * 执行转换相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回transform结果。
     */
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
            } catch (Exception e) {
                log.warn("工具结果转换器执行失败，继续使用原始结果 transformer={} error={}",
                        transformer.getClass().getName(),
                        e.getClass().getSimpleName());
            }
        }
        return original;
    }

    /**
     * 构建Interceptor。
     *
     * @return 返回创建好的Interceptor。
     */
    public ReActInterceptor buildInterceptor() {
        return new TransformInterceptor(this);
    }

    /** 定义工具结果Transformer的抽象契约，供不同运行时实现保持一致行为。 */
    public interface ToolResultTransformer {
        /**
         * 执行转换相关逻辑。
         *
         * @param context 当前请求或运行上下文。
         * @return 返回transform结果。
         */
        String transform(ToolResultContext context) throws Exception;
    }

    /** 承载工具结果上下文相关状态和辅助逻辑。 */
    public static class ToolResultContext {
        /** 记录工具结果上下文中的工具名称。 */
        private final String toolName;

        /** 保存参数映射，便于按键快速查询。 */
        private final Map<String, Object> args;

        /** 记录工具结果上下文中的结果。 */
        private final String result;

        /** 记录工具结果上下文中的会话标识。 */
        private final String sessionId;

        /** 记录工具结果上下文中的工具Call标识。 */
        private final String toolCallId;

        /** 记录工具结果上下文中的durationMs。 */
        private final long durationMs;

        /**
         * 创建工具结果上下文实例，并注入运行所需依赖。
         *
         * @param toolName 工具名称。
         * @param args 工具或命令参数。
         * @param result 结果响应或执行结果。
         * @param sessionId 当前会话标识。
         * @param toolCallId 工具Call标识。
         * @param durationMs durationMs 参数。
         */
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

        /**
         * 读取工具名称。
         *
         * @return 返回读取到的工具名称。
         */
        public String getToolName() {
            return toolName;
        }

        /**
         * 读取参数。
         *
         * @return 返回读取到的参数。
         */
        public Map<String, Object> getArgs() {
            return args;
        }

        /**
         * 读取结果。
         *
         * @return 返回读取到的结果。
         */
        public String getResult() {
            return result;
        }

        /**
         * 读取会话标识。
         *
         * @return 返回读取到的会话标识。
         */
        public String getSessionId() {
            return sessionId;
        }

        /**
         * 读取工具Call标识。
         *
         * @return 返回读取到的工具Call标识。
         */
        public String getToolCallId() {
            return toolCallId;
        }

        /**
         * 读取Duration Ms。
         *
         * @return 返回读取到的Duration Ms。
         */
        public long getDurationMs() {
            return durationMs;
        }
    }

    /** 承载转换Interceptor相关状态和辅助逻辑。 */
    private static class TransformInterceptor implements ReActInterceptor {
        /** EXTRA工具CALL标识PREFIX的统一常量值。 */
        private static final String EXTRA_TOOL_CALL_ID_PREFIX =
                "solonclaw.tool_result_transform.tool_call_id.";

        /** 注入服务，用于调用对应业务能力。 */
        private final ToolResultTransformService service;

        /**
         * 创建Transform Interceptor实例，并注入运行所需依赖。
         *
         * @param service service依赖组件。
         */
        private TransformInterceptor(ToolResultTransformService service) {
            this.service = service;
        }

        /**
         * 响应原因事件。
         *
         * @param trace trace 参数。
         * @param response 模型响应。
         * @param message 平台消息或错误消息。
         * @param durationMs 推理耗时。
         */
        @Override
        public void onReasonEnd(
                ReActTrace trace, ChatResponse response, AssistantMessage message, long durationMs) {
            ToolCallIdTraceSupport.capture(trace, message, EXTRA_TOOL_CALL_ID_PREFIX);
        }

        /**
         * 响应Action事件。
         *
         * @param trace trace 参数。
         * @param exchanger 工具交换对象。
         */
        @Override
        public void onAction(ReActTrace trace, ToolExchanger exchanger) {
            if (trace != null) {
                String toolName = exchanger == null ? null : exchanger.getToolName();
                Map<String, Object> args = exchanger == null ? null : exchanger.getArgs();
                trace.setExtra(extraArgsKey(toolName), args);
            }
        }

        /**
         * 响应观察结果事件。
         *
         * @param trace trace 参数。
         * @param exchanger 工具交换对象。
         * @param message 工具消息。
         * @param error 工具异常。
         * @param durationMs durationMs 参数。
         */
        @Override
        public void onObservation(
                ReActTrace trace,
                ToolExchanger exchanger,
                ChatMessage message,
                Throwable error,
                long durationMs) {
            if (trace == null || service == null) {
                return;
            }
            String toolName = exchanger == null ? null : exchanger.getToolName();
            String original = ReActToolObservationSupport.get(trace, exchanger);
            ToolResultContext context =
                    new ToolResultContext(
                            toolName,
                            argsFor(trace, toolName),
                            original,
                            trace.getSession() == null ? null : trace.getSession().getSessionId(),
                            toolCallId(trace, toolName),
                            durationMs);
            ReActToolObservationSupport.set(trace, exchanger, service.transform(context));
        }

        /**
         * 执行参数For相关逻辑。
         *
         * @param trace trace 参数。
         * @param toolName 工具名称。
         * @return 返回参数For结果。
         */
        @SuppressWarnings("unchecked")
        private static Map<String, Object> argsFor(ReActTrace trace, String toolName) {
            Object value = trace.getExtra(extraArgsKey(toolName));
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            return null;
        }

        /**
         * 执行extra参数键相关逻辑。
         *
         * @param toolName 工具名称。
         * @return 返回extra参数键结果。
         */
        private static String extraArgsKey(String toolName) {
            return "solonclaw.tool_result_transform.args."
                    + StrUtil.blankToDefault(toolName, "unknown");
        }

        /**
         * 执行工具Call标识相关逻辑。
         *
         * @param trace trace 参数。
         * @param toolName 工具名称。
         * @return 返回工具Call标识。
         */
        private static String toolCallId(ReActTrace trace, String toolName) {
            int count = Math.max(0, trace.getToolCallCount());
            String captured =
                    ToolCallIdTraceSupport.captured(trace, EXTRA_TOOL_CALL_ID_PREFIX, toolName);
            if (StrUtil.isNotBlank(captured)) {
                return captured;
            }
            return StrUtil.blankToDefault(toolName, "tool") + "-" + count;
        }
    }
}
