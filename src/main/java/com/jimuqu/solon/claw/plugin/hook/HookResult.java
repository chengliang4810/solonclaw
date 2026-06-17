package com.jimuqu.solon.claw.plugin.hook;

/** 钩子返回值。null 表示不干预，非 null 表示拦截/替换。 */
public class HookResult {
    /** 阻断、替换或注入上下文的动作标识。 */
    private final String action;

    /** 阻断执行时返回给调用方的说明文本。 */
    private final String message;

    /** 替换内容或注入上下文时携带的文本。 */
    private final String content;

    /**
     * 创建钩子处理结果。
     *
     * @param action 钩子动作标识。
     * @param message 阻断消息。
     * @param content 替换内容或注入上下文。
     */
    private HookResult(String action, String message, String content) {
        this.action = action;
        this.message = message;
        this.content = content;
    }

    /**
     * 构造阻断结果，调用方应停止当前工具或模型动作。
     *
     * @param message 展示给用户或审计日志的阻断原因。
     * @return 阻断动作结果。
     */
    public static HookResult block(String message) {
        return new HookResult("block", message, null);
    }

    /**
     * 构造内容替换结果，调用方可用新内容覆盖原内容。
     *
     * @param content 替换后的文本。
     * @return 替换动作结果。
     */
    public static HookResult replace(String content) {
        return new HookResult("replace", null, content);
    }

    /**
     * 构造上下文注入结果，调用方可把文本追加到模型上下文。
     *
     * @param context 需要注入的上下文文本。
     * @return 注入动作结果。
     */
    public static HookResult inject(String context) {
        return new HookResult("inject", null, context);
    }

    /**
     * 读取钩子动作标识。
     *
     * @return block、replace 或 inject。
     */
    public String getAction() {
        return action;
    }

    /**
     * 读取阻断消息。
     *
     * @return 阻断动作携带的用户可读消息。
     */
    public String getMessage() {
        return message;
    }

    /**
     * 读取替换或注入文本。
     *
     * @return 替换内容或注入上下文。
     */
    public String getContent() {
        return content;
    }

    /**
     * 判断是否为阻断动作。
     *
     * @return 动作为 block 时返回 true。
     */
    public boolean isBlock() {
        return "block".equals(action);
    }

    /**
     * 判断是否为替换动作。
     *
     * @return 动作为 replace 时返回 true。
     */
    public boolean isReplace() {
        return "replace".equals(action);
    }

    /**
     * 判断是否为上下文注入动作。
     *
     * @return 动作为 inject 时返回 true。
     */
    public boolean isInject() {
        return "inject".equals(action);
    }
}
