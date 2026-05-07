package com.jimuqu.solon.claw.support.constants;

/** Shared slash command and action constants. */
public interface GatewayCommandConstants {
    /** Command prefix. */
    String COMMAND_PREFIX = "/";

    /** Common command actions. */
    String ACTION_LIST = "list";

    String ACTION_ENABLE = "enable";
    String ACTION_DISABLE = "disable";
    String ACTION_INSPECT = "inspect";
    String ACTION_RELOAD = "reload";
    String ACTION_CREATE = "create";
    String ACTION_PAUSE = "pause";
    String ACTION_RESUME = "resume";
    String ACTION_DELETE = "delete";
    String ACTION_RUN = "run";
    String ACTION_APPROVE = "approve";
    String ACTION_REVOKE = "revoke";
    String ACTION_PENDING = "pending";
    String ACTION_APPROVED = "approved";
    String ACTION_CLAIM_ADMIN = "claim-admin";
    String ACTION_CLEAR = "clear";
    String ACTION_SEARCH = "search";
    String ACTION_BROWSE = "browse";
    String ACTION_INSTALL = "install";
    String ACTION_CHECK = "check";
    String ACTION_UPDATE = "update";
    String ACTION_AUDIT = "audit";
    String ACTION_UNINSTALL = "uninstall";
    String ACTION_TAP = "tap";
    String ACTION_ADD = "add";
    String ACTION_REMOVE = "remove";

    /** Top-level command names. */
    String COMMAND_NEW = "new";

    String COMMAND_RESET = "reset";
    String COMMAND_RETRY = "retry";
    String COMMAND_UNDO = "undo";
    String COMMAND_BRANCH = "branch";
    String COMMAND_RESUME = "resume";
    String COMMAND_STATUS = "status";
    String COMMAND_USAGE = "usage";
    String COMMAND_BUSY = "busy";
    String COMMAND_QUEUE = "queue";
    String COMMAND_STEER = "steer";
    String COMMAND_RESTART = "restart";
    String COMMAND_STOP = "stop";
    String COMMAND_YOLO = "yolo";
    String COMMAND_PERSONALITY = "personality";
    String COMMAND_VERSION = "version";
    String COMMAND_MODEL = "model";
    String COMMAND_TOOLS = "tools";
    String COMMAND_SKILLS = "skills";
    String COMMAND_RELOAD_MCP = "reload-mcp";
    String COMMAND_CRON = "cron";
    String COMMAND_KANBAN = "kanban";
    String COMMAND_GOAL = "goal";
    String COMMAND_RECAP = "recap";
    String COMMAND_TRAJECTORY = "trajectory";
    String COMMAND_PLATFORMS = "platforms";
    String COMMAND_SETHOME = "sethome";
    String COMMAND_PAIRING = "pairing";
    String COMMAND_COMPRESS = "compress";
    String COMMAND_COMPACT = "compact";
    String COMMAND_ROLLBACK = "rollback";
    String COMMAND_REASONING = "reasoning";
    String COMMAND_HELP = "help";
    String COMMAND_AGENT = "agent";
    String COMMAND_APPROVE = "approve";
    String COMMAND_DENY = "deny";
    String COMMAND_ALWAYS = "always";
    String COMMAND_CANCEL = "cancel";

    /** Full slash command text. */
    String SLASH_NEW = COMMAND_PREFIX + COMMAND_NEW;

    String SLASH_RESET = COMMAND_PREFIX + COMMAND_RESET;
    String SLASH_RETRY = COMMAND_PREFIX + COMMAND_RETRY;
    String SLASH_UNDO = COMMAND_PREFIX + COMMAND_UNDO;
    String SLASH_BRANCH = COMMAND_PREFIX + COMMAND_BRANCH;
    String SLASH_RESUME = COMMAND_PREFIX + COMMAND_RESUME;
    String SLASH_STATUS = COMMAND_PREFIX + COMMAND_STATUS;
    String SLASH_USAGE = COMMAND_PREFIX + COMMAND_USAGE;
    String SLASH_BUSY = COMMAND_PREFIX + COMMAND_BUSY;
    String SLASH_QUEUE = COMMAND_PREFIX + COMMAND_QUEUE;
    String SLASH_STEER = COMMAND_PREFIX + COMMAND_STEER;
    String SLASH_RESTART = COMMAND_PREFIX + COMMAND_RESTART;
    String SLASH_STOP = COMMAND_PREFIX + COMMAND_STOP;
    String SLASH_YOLO = COMMAND_PREFIX + COMMAND_YOLO;
    String SLASH_PERSONALITY = COMMAND_PREFIX + COMMAND_PERSONALITY;
    String SLASH_VERSION = COMMAND_PREFIX + COMMAND_VERSION;
    String SLASH_MODEL = COMMAND_PREFIX + COMMAND_MODEL;
    String SLASH_TOOLS = COMMAND_PREFIX + COMMAND_TOOLS;
    String SLASH_SKILLS = COMMAND_PREFIX + COMMAND_SKILLS;
    String SLASH_RELOAD_MCP = COMMAND_PREFIX + COMMAND_RELOAD_MCP;
    String SLASH_CRON = COMMAND_PREFIX + COMMAND_CRON;
    String SLASH_KANBAN = COMMAND_PREFIX + COMMAND_KANBAN;
    String SLASH_GOAL = COMMAND_PREFIX + COMMAND_GOAL;
    String SLASH_RECAP = COMMAND_PREFIX + COMMAND_RECAP;
    String SLASH_TRAJECTORY = COMMAND_PREFIX + COMMAND_TRAJECTORY;
    String SLASH_PLATFORMS = COMMAND_PREFIX + COMMAND_PLATFORMS;
    String SLASH_SETHOME = COMMAND_PREFIX + COMMAND_SETHOME;
    String SLASH_PAIRING = COMMAND_PREFIX + COMMAND_PAIRING;
    String SLASH_COMPRESS = COMMAND_PREFIX + COMMAND_COMPRESS;
    String SLASH_COMPACT = COMMAND_PREFIX + COMMAND_COMPACT;
    String SLASH_ROLLBACK = COMMAND_PREFIX + COMMAND_ROLLBACK;
    String SLASH_REASONING = COMMAND_PREFIX + COMMAND_REASONING;
    String SLASH_HELP = COMMAND_PREFIX + COMMAND_HELP;
    String SLASH_AGENT = COMMAND_PREFIX + COMMAND_AGENT;
    String SLASH_APPROVE = COMMAND_PREFIX + COMMAND_APPROVE;
    String SLASH_DENY = COMMAND_PREFIX + COMMAND_DENY;
    String SLASH_ALWAYS = COMMAND_PREFIX + COMMAND_ALWAYS;
    String SLASH_CANCEL = COMMAND_PREFIX + COMMAND_CANCEL;
}
