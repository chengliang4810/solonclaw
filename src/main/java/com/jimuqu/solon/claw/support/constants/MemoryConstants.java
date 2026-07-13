package com.jimuqu.solon.claw.support.constants;

/** 长期记忆常量。 */
public interface MemoryConstants {
    /** TARGET记忆的统一常量值。 */
    String TARGET_MEMORY = "memory";

    /** TARGET用户的统一常量值。 */
    String TARGET_USER = "user";

    /** TARGETTODAY的统一常量值。 */
    String TARGET_TODAY = "today";

    /** 专题记忆目标前缀，完整格式为 topic:名称。 */
    String TARGET_TOPIC_PREFIX = "topic:";

    /** ACTIONADD的统一常量值。 */
    String ACTION_ADD = "add";

    /** ACTIONREPLACE的统一常量值。 */
    String ACTION_REPLACE = "replace";

    /** ACTIONREMOVE的统一常量值。 */
    String ACTION_REMOVE = "remove";

    /** ACTIONREAD的统一常量值。 */
    String ACTION_READ = "read";

    /** 记忆文件名称的统一常量值。 */
    String MEMORY_FILE_NAME = "MEMORY.md";

    /** 用户文件名称的统一常量值。 */
    String USER_FILE_NAME = "USER.md";

    /** DAILY记忆目录名称的统一常量值。 */
    String DAILY_MEMORY_DIR_NAME = "memory";

    /** 记忆审批开关与待审批队列的持久化状态文件名。 */
    String APPROVAL_STATE_FILE_NAME = ".solonclaw-memory-approvals.json";

    /** 记忆审批状态跨进程互斥锁文件名。 */
    String APPROVAL_LOCK_FILE_NAME = ".solonclaw-memory-approvals.lock";
}
