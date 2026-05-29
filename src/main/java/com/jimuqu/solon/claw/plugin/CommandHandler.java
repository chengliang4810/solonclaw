package com.jimuqu.solon.claw.plugin;

/** 斜杠命令处理器。 */
@FunctionalInterface
public interface CommandHandler {
    String handle(String rawArgs);
}
