package com.jimuqu.solon.claw.plugin;

/** 斜杠命令处理器。 */
@FunctionalInterface
public interface CommandHandler {
    /**
     * 执行handle相关逻辑。
     *
     * @param rawArgs 原始Args参数。
     * @return 返回handle结果。
     */
    String handle(String rawArgs);
}
