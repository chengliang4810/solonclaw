package com.jimuqu.solon.claw.plugin.hook;

/** 钩子返回值。null 表示不干预，非 null 表示拦截/替换。 */
public class HookResult {
    /** 记录钩子中的action。 */
    private final String action;

    /** 记录钩子中的消息。 */
    private final String message;

    /** 记录钩子中的content。 */
    private final String content;

    /**
     * 创建钩子结果实例，并注入运行所需依赖。
     *
     * @param action 操作参数。
     * @param message 平台消息或错误消息。
     * @param content 待处理内容。
     */
    private HookResult(String action, String message, String content) {
        this.action = action;
        this.message = message;
        this.content = content;
    }

    /** 阻止执行。 */
    public static HookResult block(String message) {
        return new HookResult("block", message, null);
    }

    /** 替换内容。 */
    public static HookResult replace(String content) {
        return new HookResult("replace", null, content);
    }

    /** 注入上下文。 */
    public static HookResult inject(String context) {
        return new HookResult("inject", null, context);
    }

    /**
     * 读取Action。
     *
     * @return 返回读取到的Action。
     */
    public String getAction() {
        return action;
    }

    /**
     * 读取消息。
     *
     * @return 返回读取到的消息。
     */
    public String getMessage() {
        return message;
    }

    /**
     * 读取Content。
     *
     * @return 返回读取到的Content。
     */
    public String getContent() {
        return content;
    }

    /**
     * 判断是否块。
     *
     * @return 如果块满足条件则返回 true，否则返回 false。
     */
    public boolean isBlock() {
        return "block".equals(action);
    }

    /**
     * 判断是否Replace。
     *
     * @return 如果Replace满足条件则返回 true，否则返回 false。
     */
    public boolean isReplace() {
        return "replace".equals(action);
    }

    /**
     * 判断是否Inject。
     *
     * @return 如果Inject满足条件则返回 true，否则返回 false。
     */
    public boolean isInject() {
        return "inject".equals(action);
    }
}
