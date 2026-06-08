package com.jimuqu.solon.claw.support.constants;

/** 运行时目录与默认值常量。 */
public interface RuntimePathConstants {
    /** 运行时主渠道的统一常量值。 */
    String RUNTIME_HOME = "runtime";

    /** 上下文目录名称的统一常量值。 */
    String CONTEXT_DIR_NAME = "context";

    /** 技能目录名称的统一常量值。 */
    String SKILLS_DIR_NAME = "skills";

    /** 缓存目录名称的统一常量值。 */
    String CACHE_DIR_NAME = "cache";

    /** 数据目录名称的统一常量值。 */
    String DATA_DIR_NAME = "data";

    /** ARTIFACTS目录名称的统一常量值。 */
    String ARTIFACTS_DIR_NAME = "artifacts";

    /** 配置文件名称的统一常量值。 */
    String CONFIG_FILE_NAME = "config.yml";

    /** 配置EXAMPLE文件名称的统一常量值。 */
    String CONFIG_EXAMPLE_FILE_NAME = "config.example.yml";

    /** 状态DB文件名称的统一常量值。 */
    String STATE_DB_FILE_NAME = "state.db";

    /** LOGS目录名称的统一常量值。 */
    String LOGS_DIR_NAME = "logs";

    /** 上下文目录的统一常量值。 */
    String CONTEXT_DIR = RUNTIME_HOME + "/" + CONTEXT_DIR_NAME;

    /** 技能目录的统一常量值。 */
    String SKILLS_DIR = RUNTIME_HOME + "/" + SKILLS_DIR_NAME;

    /** 缓存目录的统一常量值。 */
    String CACHE_DIR = RUNTIME_HOME + "/" + CACHE_DIR_NAME;

    /** ARTIFACTS目录的统一常量值。 */
    String ARTIFACTS_DIR = RUNTIME_HOME + "/" + ARTIFACTS_DIR_NAME;

    /** 状态DB的统一常量值。 */
    String STATE_DB = RUNTIME_HOME + "/" + DATA_DIR_NAME + "/" + STATE_DB_FILE_NAME;

    /** 配置文件的统一常量值。 */
    String CONFIG_FILE = RUNTIME_HOME + "/" + CONFIG_FILE_NAME;

    /** LOGS目录的统一常量值。 */
    String LOGS_DIR = RUNTIME_HOME + "/" + LOGS_DIR_NAME;

    /** 默认提供方键的统一常量值。 */
    String DEFAULT_PROVIDER_KEY = "default";

    /** 默认大模型提供方的统一常量值。 */
    String DEFAULT_LLM_PROVIDER = "openai";

    /** 默认大模型APIURL的统一常量值。 */
    String DEFAULT_LLM_API_URL = "https://api.openai.com";

    /** 默认大模型模型的统一常量值。 */
    String DEFAULT_LLM_MODEL = "gpt-5.4";

    /** 默认推理EFFORT的统一常量值。 */
    String DEFAULT_REASONING_EFFORT = "medium";

    /** 默认上下文窗口token的统一常量值。 */
    int DEFAULT_CONTEXT_WINDOW_TOKENS = 128000;

    /** 默认调度器TICKSECONDS的统一常量值。 */
    int DEFAULT_SCHEDULER_TICK_SECONDS = 60;

    /** 默认最大token的统一常量值。 */
    int DEFAULT_MAX_TOKENS = 4096;

    /** 默认TEMPERATURE的统一常量值。 */
    double DEFAULT_TEMPERATURE = 0.2D;

    /** 默认心跳整型ERVAL最小UTES的统一常量值。 */
    int DEFAULT_HEARTBEAT_INTERVAL_MINUTES = 15;
}
