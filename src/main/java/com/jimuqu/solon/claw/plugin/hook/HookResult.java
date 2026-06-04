package com.jimuqu.solon.claw.plugin.hook;

/** 钩子返回值。null 表示不干预，非 null 表示拦截/替换。 */
public class HookResult {
    private final String action;
    private final String message;
    private final String content;

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

    public String getAction() {
        return action;
    }

    public String getMessage() {
        return message;
    }

    public String getContent() {
        return content;
    }

    public boolean isBlock() {
        return "block".equals(action);
    }

    public boolean isReplace() {
        return "replace".equals(action);
    }

    public boolean isInject() {
        return "inject".equals(action);
    }
}
