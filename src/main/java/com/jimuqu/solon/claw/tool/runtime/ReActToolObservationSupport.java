package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;

/** 适配 Solon AI ReAct 工具观察结果，统一承载项目内工具拦截器需要共享的结果文本。 */
public final class ReActToolObservationSupport {
    /** 观察结果在 trace extra 中的键名，用于让多个拦截器顺序改写同一工具结果。 */
    private static final String OBSERVATION_EXTRA_KEY = "solonclaw.react.tool_observation";

    private ReActToolObservationSupport() {}

    /**
     * 清空当前工具调用的观察结果，避免上一轮工具结果影响本轮拦截器判断。
     *
     * @param trace ReAct 轨迹。
     * @param exchanger Solon AI 4 工具交换对象。
     */
    public static void clear(ReActTrace trace, ToolExchanger exchanger) {
        if (exchanger != null) {
            exchanger.setResult(null);
        }
        if (trace != null) {
            trace.getExtras().remove(OBSERVATION_EXTRA_KEY);
        }
    }

    /**
     * 写入工具观察结果，并同步到 ToolExchanger 与 trace extra，方便后续拦截器读取改写后的值。
     *
     * @param trace ReAct 轨迹。
     * @param exchanger Solon AI 4 工具交换对象。
     * @param result 工具结果文本。
     */
    public static void set(ReActTrace trace, ToolExchanger exchanger, String result) {
        String normalized = StrUtil.nullToEmpty(result);
        if (exchanger != null) {
            exchanger.setResult(normalized);
        }
        if (trace != null) {
            trace.setExtra(OBSERVATION_EXTRA_KEY, normalized);
        }
    }

    /**
     * 读取当前工具观察结果，优先使用 ToolExchanger 中最新结果，其次使用 trace extra 中的改写值。
     *
     * @param trace ReAct 轨迹。
     * @param exchanger Solon AI 4 工具交换对象。
     * @return 返回当前工具结果文本。
     */
    public static String get(ReActTrace trace, ToolExchanger exchanger) {
        if (exchanger != null && StrUtil.isNotBlank(exchanger.getResult())) {
            return exchanger.getResult();
        }
        if (trace != null) {
            String value = trace.getExtraAs(OBSERVATION_EXTRA_KEY);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return exchanger == null ? "" : StrUtil.nullToEmpty(exchanger.getResult());
    }
}
