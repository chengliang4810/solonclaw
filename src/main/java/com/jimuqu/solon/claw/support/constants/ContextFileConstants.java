package com.jimuqu.solon.claw.support.constants;

import cn.hutool.core.util.StrUtil;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 人格工作区上下文文件常量。 */
public final class ContextFileConstants {
    /** 键AGENTS的统一常量值。 */
    public static final String KEY_AGENTS = "agents";

    /** 键SOUL的统一常量值。 */
    public static final String KEY_SOUL = "soul";

    /** 键身份的统一常量值。 */
    public static final String KEY_IDENTITY = "identity";

    /** 键用户的统一常量值。 */
    public static final String KEY_USER = "user";

    /** 键工具的统一常量值。 */
    public static final String KEY_TOOLS = "tools";

    /** 键心跳的统一常量值。 */
    public static final String KEY_HEARTBEAT = "heartbeat";

    /** 键记忆的统一常量值。 */
    public static final String KEY_MEMORY = "memory";

    /** 键跨会话反思的统一常量值。 */
    public static final String KEY_REFLECTION = "reflection";

    /** 键记忆TODAY的统一常量值。 */
    public static final String KEY_MEMORY_TODAY = "memory_today";

    /** 首次启动引导文件键，仅在新工作区初始化时创建。 */
    public static final String KEY_BOOTSTRAP = "bootstrap";

    /** 主动消息生成规则文件键。 */
    public static final String KEY_PROACTIVE = "proactive";

    /** 主动联系频率分析规则文件键。 */
    public static final String KEY_PROACTIVITY_ANALYSIS = "proactivity_analysis";

    /** 文件AGENTS的统一常量值。 */
    public static final String FILE_AGENTS = "AGENTS.md";

    /** 文件SOUL的统一常量值。 */
    public static final String FILE_SOUL = "SOUL.md";

    /** 文件身份的统一常量值。 */
    public static final String FILE_IDENTITY = "IDENTITY.md";

    /** 文件用户的统一常量值。 */
    public static final String FILE_USER = "USER.md";

    /** 文件工具的统一常量值。 */
    public static final String FILE_TOOLS = "TOOLS.md";

    /** 文件心跳的统一常量值。 */
    public static final String FILE_HEARTBEAT = "HEARTBEAT.md";

    /** 文件记忆的统一常量值。 */
    public static final String FILE_MEMORY = "MEMORY.md";

    /** 跨会话反思派生快照文件名。 */
    public static final String FILE_REFLECTION = "REFLECTION.md";

    /** 首次启动引导文件名。 */
    public static final String FILE_BOOTSTRAP = "BOOTSTRAP.md";

    /** 主动消息生成规则文件名。 */
    public static final String FILE_PROACTIVE = "PROACTIVE.md";

    /** 主动联系频率分析规则文件名。 */
    public static final String FILE_PROACTIVITY_ANALYSIS = "PROACTIVITY_ANALYSIS.md";

    /** 记忆目录的统一常量值。 */
    public static final String MEMORY_DIR = "memory";

    /** FILES根据键的统一常量值。 */
    private static final Map<String, String> FILES_BY_KEY;

    /** ORDEREDKEYS的统一常量值。 */
    private static final List<String> ORDERED_KEYS;

    static {
        LinkedHashMap<String, String> files = new LinkedHashMap<String, String>();
        files.put(KEY_AGENTS, FILE_AGENTS);
        files.put(KEY_SOUL, FILE_SOUL);
        files.put(KEY_IDENTITY, FILE_IDENTITY);
        files.put(KEY_USER, FILE_USER);
        files.put(KEY_TOOLS, FILE_TOOLS);
        files.put(KEY_HEARTBEAT, FILE_HEARTBEAT);
        files.put(KEY_MEMORY, FILE_MEMORY);
        files.put(KEY_BOOTSTRAP, FILE_BOOTSTRAP);
        files.put(KEY_PROACTIVE, FILE_PROACTIVE);
        files.put(KEY_PROACTIVITY_ANALYSIS, FILE_PROACTIVITY_ANALYSIS);
        FILES_BY_KEY = Collections.unmodifiableMap(files);
        ArrayList<String> ordered = new ArrayList<String>(files.keySet());
        ordered.add(KEY_MEMORY_TODAY);
        ORDERED_KEYS = Collections.unmodifiableList(ordered);
    }

    /** 创建上下文文件Constants实例。 */
    private ContextFileConstants() {}

    /** 返回受控文件 key 顺序。 */
    public static List<String> orderedKeys() {
        return ORDERED_KEYS;
    }

    /** 判断是否为受控文件 key。 */
    public static boolean isManagedKey(String key) {
        String normalized = normalizeKey(key);
        return FILES_BY_KEY.containsKey(normalized) || KEY_MEMORY_TODAY.equals(normalized);
    }

    /** 解析 key 对应文件名。 */
    public static String fileName(String key) {
        String normalized = normalizeKey(key);
        if (KEY_MEMORY_TODAY.equals(normalized)) {
            return dailyMemoryRelativePath(LocalDate.now());
        }
        String fileName = FILES_BY_KEY.get(normalized);
        if (fileName == null) {
            throw new IllegalArgumentException("Unsupported context file key: " + key);
        }
        return fileName;
    }

    /** 归一化文件 key。 */
    public static String normalizeKey(String key) {
        return StrUtil.nullToEmpty(key).trim().toLowerCase();
    }

    /**
     * 执行daily记忆Relative路径相关逻辑。
     *
     * @param date date 参数。
     * @return 返回daily记忆Relative路径。
     */
    public static String dailyMemoryRelativePath(LocalDate date) {
        return MEMORY_DIR + "/" + date.format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
    }
}
