package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.Map;

/** 表示消息网关工具参数解析结果，避免审批服务主类承载临时数据结构。 */
final class GatewayToolArgsResult {
    /** 保存参数映射，便于按键快速查询。 */
    private final Map<String, Object> args;

    /** 标记参数是否解析成功。 */
    private final boolean valid;

    /** 记录参数解析失败时展示给调用方的消息。 */
    private final String message;

    /** 记录原始参数文本，便于审批拒绝时保留低敏上下文。 */
    private final String rawText;

    /**
     * 创建消息网关工具参数结果实例。
     *
     * @param args 工具或命令参数。
     * @param valid 是否解析成功。
     * @param message 平台消息或错误消息。
     * @param rawText 原始文本参数。
     */
    private GatewayToolArgsResult(
            Map<String, Object> args, boolean valid, String message, String rawText) {
        this.args = args == null ? new LinkedHashMap<String, Object>() : args;
        this.valid = valid;
        this.message = StrUtil.nullToEmpty(message);
        this.rawText = StrUtil.nullToEmpty(rawText);
    }

    /**
     * 创建解析成功的工具参数结果。
     *
     * @param args 工具或命令参数。
     * @return 解析成功的工具参数结果。
     */
    static GatewayToolArgsResult valid(Map<String, Object> args) {
        return new GatewayToolArgsResult(args, true, "", "");
    }

    /**
     * 创建解析失败的工具参数结果。
     *
     * @param message 平台消息或错误消息。
     * @param rawText 原始文本参数。
     * @return 解析失败的工具参数结果。
     */
    static GatewayToolArgsResult invalid(String message, String rawText) {
        return new GatewayToolArgsResult(
                new LinkedHashMap<String, Object>(), false, message, rawText);
    }

    /**
     * 读取解析后的参数。
     *
     * @return 解析后的参数。
     */
    Map<String, Object> getArgs() {
        return args;
    }

    /**
     * 判断参数是否解析成功。
     *
     * @return 参数解析成功时返回 true。
     */
    boolean isValid() {
        return valid;
    }

    /**
     * 读取参数解析消息。
     *
     * @return 参数解析消息。
     */
    String getMessage() {
        return message;
    }

    /**
     * 读取原始参数文本。
     *
     * @return 原始参数文本。
     */
    String getRawText() {
        return rawText;
    }
}
