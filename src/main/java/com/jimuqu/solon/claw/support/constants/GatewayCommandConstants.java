package com.jimuqu.solon.claw.support.constants;

/** 定义消息网关命令的抽象契约，供不同运行时实现保持一致行为。 */
public interface GatewayCommandConstants {
    /** 命令PREFIX的统一常量值。 */
    String COMMAND_PREFIX = "/";

    /** ACTION列表的统一常量值。 */
    String ACTION_LIST = "list";

    /** ACTIONENABLE的统一常量值。 */
    String ACTION_ENABLE = "enable";

    /** ACTIONDISABLE的统一常量值。 */
    String ACTION_DISABLE = "disable";

    /** ACTIONINSPECT的统一常量值。 */
    String ACTION_INSPECT = "inspect";

    /** ACTIONRELOAD的统一常量值。 */
    String ACTION_RELOAD = "reload";

    /** ACTIONCREATE的统一常量值。 */
    String ACTION_CREATE = "create";

    /** ACTIONPAUSE的统一常量值。 */
    String ACTION_PAUSE = "pause";

    /** ACTIONRESUME的统一常量值。 */
    String ACTION_RESUME = "resume";

    /** ACTIONDELETE的统一常量值。 */
    String ACTION_DELETE = "delete";

    /** ACTION运行的统一常量值。 */
    String ACTION_RUN = "run";

    /** ACTIONAPPROVE的统一常量值。 */
    String ACTION_APPROVE = "approve";

    /** ACTIONREVOKE的统一常量值。 */
    String ACTION_REVOKE = "revoke";

    /** ACTION待恢复的统一常量值。 */
    String ACTION_PENDING = "pending";

    /** ACTIONAPPROVED的统一常量值。 */
    String ACTION_APPROVED = "approved";

    /** ACTIONCLAIM管理员的统一常量值。 */
    String ACTION_CLAIM_ADMIN = "claim-admin";

    /** ACTIONCLEAR的统一常量值。 */
    String ACTION_CLEAR = "clear";

    /** ACTION搜索的统一常量值。 */
    String ACTION_SEARCH = "search";

    /** ACTIONBROWSE的统一常量值。 */
    String ACTION_BROWSE = "browse";

    /** ACTIONINSTALL的统一常量值。 */
    String ACTION_INSTALL = "install";

    /** ACTIONCHECK的统一常量值。 */
    String ACTION_CHECK = "check";

    /** ACTION更新的统一常量值。 */
    String ACTION_UPDATE = "update";

    /** ACTION审计的统一常量值。 */
    String ACTION_AUDIT = "audit";

    /** ACTIONUNINSTALL的统一常量值。 */
    String ACTION_UNINSTALL = "uninstall";

    /** ACTION来源库的统一常量值。 */
    String ACTION_TAP = "tap";

    /** ACTIONADD的统一常量值。 */
    String ACTION_ADD = "add";

    /** ACTIONREMOVE的统一常量值。 */
    String ACTION_REMOVE = "remove";

    /** 命令NEW的统一常量值。 */
    String COMMAND_NEW = "new";

    /** 命令RESET的统一常量值。 */
    String COMMAND_RESET = "reset";

    /** 命令重试的统一常量值。 */
    String COMMAND_RETRY = "retry";

    /** 命令UNDO的统一常量值。 */
    String COMMAND_UNDO = "undo";

    /** 命令BRANCH的统一常量值。 */
    String COMMAND_BRANCH = "branch";

    /** 命令RESUME的统一常量值。 */
    String COMMAND_RESUME = "resume";

    /** 命令SESSIONS的统一常量值。 */
    String COMMAND_SESSIONS = "sessions";

    /** 命令WHOAMI的统一常量值。 */
    String COMMAND_WHOAMI = "whoami";

    /** 命令 Profile 状态的统一常量值。 */
    String COMMAND_PROFILE = "profile";

    /** 命令COMMANDS的统一常量值。 */
    String COMMAND_COMMANDS = "commands";

    /** 命令洞察的统一常量值。 */
    String COMMAND_INSIGHTS = "insights";

    /** 命令标题的统一常量值。 */
    String COMMAND_TITLE = "title";

    /** 命令状态的统一常量值。 */
    String COMMAND_STATUS = "status";

    /** 命令用量的统一常量值。 */
    String COMMAND_USAGE = "usage";

    /** 命令BUSY的统一常量值。 */
    String COMMAND_BUSY = "busy";

    /** 命令队列的统一常量值。 */
    String COMMAND_QUEUE = "queue";

    /** 命令STEER的统一常量值。 */
    String COMMAND_STEER = "steer";

    /** 命令重启的统一常量值。 */
    String COMMAND_RESTART = "restart";

    /** 命令STOP的统一常量值。 */
    String COMMAND_STOP = "stop";

    /** 命令安全策略的统一常量值。 */
    String COMMAND_SECURITY = "security";

    /** 命令版本的统一常量值。 */
    String COMMAND_VERSION = "version";

    /** 命令更新的统一常量值。 */
    /** 命令模型的统一常量值。 */
    String COMMAND_MODEL = "model";

    /** 命令FAST的统一常量值。 */
    String COMMAND_FAST = "fast";

    /** 命令工具的统一常量值。 */
    String COMMAND_TOOLS = "tools";

    /** 命令TOOLSETS的统一常量值。 */
    String COMMAND_TOOLSETS = "toolsets";

    /** 命令浏览器的统一常量值。 */
    String COMMAND_BROWSER = "browser";

    /** 命令语音模式的统一常量值。 */
    String COMMAND_VOICE = "voice";

    /** 命令DEBUG的统一常量值。 */
    String COMMAND_DEBUG = "debug";

    /** 命令技能的统一常量值。 */
    String COMMAND_SKILLS = "skills";

    /** 命令技能维护的统一常量值。 */
    String COMMAND_CURATOR = "curator";

    /** 命令MEMORY的统一常量值。 */
    String COMMAND_MEMORY = "memory";

    /** 命令RELOAD技能的统一常量值。 */
    String COMMAND_RELOAD_SKILLS = "reload-skills";

    /** 命令RELOADMCP的统一常量值。 */
    String COMMAND_RELOAD_MCP = "reload-mcp";

    /** 命令定时任务的统一常量值。 */
    String COMMAND_CRON = "cron";

    /** 命令目标的统一常量值。 */
    String COMMAND_GOAL = "goal";

    /** subgoal 命令名。 */
    String COMMAND_SUBGOAL = "subgoal";

    /** 命令RECAP的统一常量值。 */
    String COMMAND_RECAP = "recap";

    /** 命令TRAJECTORY的统一常量值。 */
    String COMMAND_TRAJECTORY = "trajectory";

    /** 命令主动协作的统一常量值。 */
    String COMMAND_PROACTIVE = "proactive";

    /** 命令PLATFORMS的统一常量值。 */
    String COMMAND_PLATFORMS = "platforms";

    /** 命令平台的统一常量值。 */
    String COMMAND_PLATFORM = "platform";

    /** 命令SETHOME的统一常量值。 */
    String COMMAND_SETHOME = "sethome";

    /** 命令配对的统一常量值。 */
    String COMMAND_PAIRING = "pairing";

    /** 命令紧凑的统一常量值。 */
    String COMMAND_COMPACT = "compact";

    /** 命令回滚的统一常量值。 */
    String COMMAND_ROLLBACK = "rollback";

    /** 命令推理的统一常量值。 */
    String COMMAND_REASONING = "reasoning";

    /** 命令HELP的统一常量值。 */
    String COMMAND_HELP = "help";

    /** 命令APPROVE的统一常量值。 */
    String COMMAND_APPROVE = "approve";

    /** 命令DENY的统一常量值。 */
    String COMMAND_DENY = "deny";

    /** 命令YOLO的统一常量值，切换会话级跳过危险命令审批。 */
    String COMMAND_YOLO = "yolo";

    /** 命令CANCEL的统一常量值。 */
    String COMMAND_CANCEL = "cancel";

    /** 命令CONFIRM的统一常量值。 */
    String COMMAND_CONFIRM = "confirm";

    /** 斜杠命令NEW的统一常量值。 */
    String SLASH_NEW = COMMAND_PREFIX + COMMAND_NEW;

    /** 斜杠命令RESET的统一常量值。 */
    String SLASH_RESET = COMMAND_PREFIX + COMMAND_RESET;

    /** 斜杠命令重试的统一常量值。 */
    String SLASH_RETRY = COMMAND_PREFIX + COMMAND_RETRY;

    /** 斜杠命令UNDO的统一常量值。 */
    String SLASH_UNDO = COMMAND_PREFIX + COMMAND_UNDO;

    /** 斜杠命令BRANCH的统一常量值。 */
    String SLASH_BRANCH = COMMAND_PREFIX + COMMAND_BRANCH;

    /** 斜杠命令RESUME的统一常量值。 */
    String SLASH_RESUME = COMMAND_PREFIX + COMMAND_RESUME;

    /** 斜杠命令SESSIONS的统一常量值。 */
    String SLASH_SESSIONS = COMMAND_PREFIX + COMMAND_SESSIONS;

    /** 斜杠命令WHOAMI的统一常量值。 */
    String SLASH_WHOAMI = COMMAND_PREFIX + COMMAND_WHOAMI;

    /** 斜杠命令 Profile 状态的统一常量值。 */
    String SLASH_PROFILE = COMMAND_PREFIX + COMMAND_PROFILE;

    /** 斜杠命令COMMANDS的统一常量值。 */
    String SLASH_COMMANDS = COMMAND_PREFIX + COMMAND_COMMANDS;

    /** 斜杠命令洞察的统一常量值。 */
    String SLASH_INSIGHTS = COMMAND_PREFIX + COMMAND_INSIGHTS;

    /** 斜杠命令标题的统一常量值。 */
    String SLASH_TITLE = COMMAND_PREFIX + COMMAND_TITLE;

    /** 斜杠命令状态的统一常量值。 */
    String SLASH_STATUS = COMMAND_PREFIX + COMMAND_STATUS;

    /** 斜杠命令用量的统一常量值。 */
    String SLASH_USAGE = COMMAND_PREFIX + COMMAND_USAGE;

    /** 斜杠命令BUSY的统一常量值。 */
    String SLASH_BUSY = COMMAND_PREFIX + COMMAND_BUSY;

    /** 斜杠命令队列的统一常量值。 */
    String SLASH_QUEUE = COMMAND_PREFIX + COMMAND_QUEUE;

    /** 斜杠命令STEER的统一常量值。 */
    String SLASH_STEER = COMMAND_PREFIX + COMMAND_STEER;

    /** 斜杠命令重启的统一常量值。 */
    String SLASH_RESTART = COMMAND_PREFIX + COMMAND_RESTART;

    /** 斜杠命令STOP的统一常量值。 */
    String SLASH_STOP = COMMAND_PREFIX + COMMAND_STOP;

    /** 斜杠命令版本的统一常量值。 */
    String SLASH_VERSION = COMMAND_PREFIX + COMMAND_VERSION;

    /** 斜杠命令更新的统一常量值。 */
    /** 斜杠命令模型的统一常量值。 */
    String SLASH_MODEL = COMMAND_PREFIX + COMMAND_MODEL;

    /** 斜杠命令FAST的统一常量值。 */
    String SLASH_FAST = COMMAND_PREFIX + COMMAND_FAST;

    /** 斜杠命令工具的统一常量值。 */
    String SLASH_TOOLS = COMMAND_PREFIX + COMMAND_TOOLS;

    /** 斜杠命令主动协作的统一常量值。 */
    String SLASH_PROACTIVE = COMMAND_PREFIX + COMMAND_PROACTIVE;

    /** 斜杠命令TOOLSETS的统一常量值。 */
    String SLASH_TOOLSETS = COMMAND_PREFIX + COMMAND_TOOLSETS;

    /** 斜杠命令浏览器的统一常量值。 */
    String SLASH_BROWSER = COMMAND_PREFIX + COMMAND_BROWSER;

    /** 斜杠命令DEBUG的统一常量值。 */
    String SLASH_DEBUG = COMMAND_PREFIX + COMMAND_DEBUG;

    /** 斜杠命令技能的统一常量值。 */
    String SLASH_SKILLS = COMMAND_PREFIX + COMMAND_SKILLS;

    /** 斜杠命令技能维护的统一常量值。 */
    String SLASH_CURATOR = COMMAND_PREFIX + COMMAND_CURATOR;

    /** 斜杠命令RELOAD技能的统一常量值。 */
    String SLASH_RELOAD_SKILLS = COMMAND_PREFIX + COMMAND_RELOAD_SKILLS;

    /** 斜杠命令RELOADMCP的统一常量值。 */
    String SLASH_RELOAD_MCP = COMMAND_PREFIX + COMMAND_RELOAD_MCP;

    /** 斜杠命令定时任务的统一常量值。 */
    String SLASH_CRON = COMMAND_PREFIX + COMMAND_CRON;

    /** 斜杠命令目标的统一常量值。 */
    String SLASH_GOAL = COMMAND_PREFIX + COMMAND_GOAL;

    /** subgoal 斜杠命令。 */
    String SLASH_SUBGOAL = COMMAND_PREFIX + COMMAND_SUBGOAL;

    /** 斜杠命令RECAP的统一常量值。 */
    String SLASH_RECAP = COMMAND_PREFIX + COMMAND_RECAP;

    /** 斜杠命令TRAJECTORY的统一常量值。 */
    String SLASH_TRAJECTORY = COMMAND_PREFIX + COMMAND_TRAJECTORY;

    /** 斜杠命令PLATFORMS的统一常量值。 */
    String SLASH_PLATFORMS = COMMAND_PREFIX + COMMAND_PLATFORMS;

    /** 斜杠命令平台的统一常量值。 */
    String SLASH_PLATFORM = COMMAND_PREFIX + COMMAND_PLATFORM;

    /** 斜杠命令SETHOME的统一常量值。 */
    String SLASH_SETHOME = COMMAND_PREFIX + COMMAND_SETHOME;

    /** 斜杠命令配对的统一常量值。 */
    String SLASH_PAIRING = COMMAND_PREFIX + COMMAND_PAIRING;

    /** 斜杠命令紧凑的统一常量值。 */
    String SLASH_COMPACT = COMMAND_PREFIX + COMMAND_COMPACT;

    /** 斜杠命令回滚的统一常量值。 */
    String SLASH_ROLLBACK = COMMAND_PREFIX + COMMAND_ROLLBACK;

    /** 斜杠命令推理的统一常量值。 */
    String SLASH_REASONING = COMMAND_PREFIX + COMMAND_REASONING;

    /** 斜杠命令HELP的统一常量值。 */
    String SLASH_HELP = COMMAND_PREFIX + COMMAND_HELP;

    /** 斜杠命令Agent的统一常量值。 */

    /** 斜杠命令APPROVE的统一常量值。 */
    String SLASH_APPROVE = COMMAND_PREFIX + COMMAND_APPROVE;

    /** 斜杠命令DENY的统一常量值。 */
    String SLASH_DENY = COMMAND_PREFIX + COMMAND_DENY;

    /** 斜杠命令CANCEL的统一常量值。 */
    String SLASH_CANCEL = COMMAND_PREFIX + COMMAND_CANCEL;

    /** 斜杠命令CONFIRM的统一常量值。 */
    String SLASH_CONFIRM = COMMAND_PREFIX + COMMAND_CONFIRM;
}
