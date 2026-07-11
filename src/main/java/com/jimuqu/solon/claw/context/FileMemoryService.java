package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.MemorySnapshot;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.support.constants.MemoryConstants;
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 基于文件系统的长期记忆服务。 */
public class FileMemoryService implements MemoryService {
    /** 明显属于短期任务状态的关键词。 */
    private static final String[] TRANSIENT_PATTERNS =
            new String[] {"本会话", "临时", "rollback", "checkpoint", "sessionId", "session_id"};

    /** 需要在没有长期语义时拦截的弱短期状态关键词。 */
    private static final String[] WEAK_TRANSIENT_PATTERNS = new String[] {"TODO", "todo"};

    /** 明确表达长期记忆价值的中文前缀。 */
    private static final String[] LONG_TERM_PREFIXES =
            new String[] {"长期偏好", "长期记忆", "用户偏好", "项目约定", "环境细节", "常见纠正", "工具怪癖"};

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 构造文件记忆服务。 */
    public FileMemoryService(AppConfig appConfig) {
        this.appConfig = appConfig;
        FileUtil.mkdir(appConfig.getRuntime().getHome());
        FileUtil.mkdir(appConfig.getRuntime().getContextDir());
        FileUtil.mkdir(memoryDir());
    }

    /**
     * 加载Snapshot。
     *
     * @return 返回Snapshot结果。
     */
    @Override
    public MemorySnapshot loadSnapshot() throws Exception {
        MemorySnapshot snapshot = new MemorySnapshot();
        snapshot.setMemoryText(read(MemoryConstants.TARGET_MEMORY));
        snapshot.setUserText(read(MemoryConstants.TARGET_USER));
        snapshot.setDailyMemoryText(readTodayMemory());
        return snapshot;
    }

    /**
     * 执行read相关逻辑。
     *
     * @param target target 参数。
     * @return 返回read结果。
     */
    @Override
    public String read(String target) throws Exception {
        File file = fileForTarget(target);
        if (!file.exists()) {
            return "";
        }
        return FileUtil.readUtf8String(file).trim();
    }

    /**
     * 执行add相关逻辑。
     *
     * @param target target 参数。
     * @param content 待处理内容。
     * @return 返回add结果。
     */
    @Override
    public synchronized String add(String target, String content) throws Exception {
        if (StrUtil.isBlank(content)) {
            return "记忆内容不能为空。";
        }

        if (MemoryConstants.TARGET_TODAY.equalsIgnoreCase(target)) {
            return appendTodayEntry(content);
        }

        String normalized = normalizeEntry(content);
        if (shouldReject(normalized)) {
            return "该内容更像临时任务状态或内部上下文，不会写入长期记忆。";
        }

        List<String> entries = readEntries(target);
        if (!containsEntry(entries, normalized)) {
            entries.add(normalized);
            writeEntries(target, entries);
        }
        return "已写入 " + normalizeTarget(target) + "。";
    }

    /**
     * 执行replace相关逻辑。
     *
     * @param target target 参数。
     * @param oldText old文本参数。
     * @param newContent newContent 参数。
     * @return 返回replace结果。
     */
    @Override
    public synchronized String replace(String target, String oldText, String newContent)
            throws Exception {
        if (StrUtil.isBlank(oldText) || StrUtil.isBlank(newContent)) {
            return "replace 需要 oldText 和 newContent。";
        }

        String normalizedNew = normalizeEntry(newContent);
        if (shouldReject(normalizedNew)) {
            return "该内容更像临时任务状态或内部上下文，不会写入长期记忆。";
        }

        List<String> entries = readEntries(target);
        boolean replaced = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).contains(oldText.trim())) {
                entries.set(i, normalizedNew);
                replaced = true;
                break;
            }
        }

        if (!replaced) {
            return "未找到可替换的记忆条目。";
        }

        writeEntries(target, entries);
        return "已更新 " + normalizeTarget(target) + "。";
    }

    /**
     * 执行remove相关逻辑。
     *
     * @param target target 参数。
     * @param matchText match文本参数。
     * @return 返回remove结果。
     */
    @Override
    public synchronized String remove(String target, String matchText) throws Exception {
        if (StrUtil.isBlank(matchText)) {
            return "remove 需要 matchText。";
        }

        List<String> entries = readEntries(target);
        boolean removed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).contains(matchText.trim())) {
                entries.remove(i);
                removed = true;
            }
        }

        if (!removed) {
            return "未找到可删除的记忆条目。";
        }

        writeEntries(target, entries);
        return "已删除 " + normalizeTarget(target) + " 中的匹配条目。";
    }

    /** 读取记忆条目列表。 */
    private List<String> readEntries(String target) throws Exception {
        String raw = read(target);
        List<String> entries = new ArrayList<String>();
        if (StrUtil.isBlank(raw)) {
            return entries;
        }

        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                trimmed = trimmed.substring(2).trim();
            }
            if (trimmed.length() > 0) {
                entries.add(normalizeEntry(trimmed));
            }
        }
        return entries;
    }

    /** 按统一格式写回记忆条目。 */
    private void writeEntries(String target, List<String> entries) {
        StringBuilder buffer = new StringBuilder();
        for (String entry : entries) {
            if (buffer.length() > 0) {
                buffer.append(System.lineSeparator());
            }
            buffer.append("- ").append(entry);
        }
        FileUtil.writeUtf8String(buffer.toString(), fileForTarget(target));
    }

    /** 解析目标文件。 */
    private File fileForTarget(String target) {
        if (MemoryConstants.TARGET_USER.equalsIgnoreCase(target)) {
            return FileUtil.file(appConfig.getRuntime().getHome(), MemoryConstants.USER_FILE_NAME);
        }
        if (MemoryConstants.TARGET_TODAY.equalsIgnoreCase(target)) {
            return todayMemoryFile();
        }
        return FileUtil.file(appConfig.getRuntime().getHome(), MemoryConstants.MEMORY_FILE_NAME);
    }

    /** 统一目标名输出。 */
    private String normalizeTarget(String target) {
        return MemoryConstants.TARGET_USER.equalsIgnoreCase(target)
                ? MemoryConstants.TARGET_USER
                : MemoryConstants.TARGET_MEMORY;
    }

    /** 统一归一化记忆条目。 */
    private String normalizeEntry(String content) {
        return StrUtil.nullToEmpty(content)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** 判断内容是否像短期状态。 */
    private boolean shouldReject(String content) {
        if (StrUtil.isBlank(content)) {
            return true;
        }
        if (content.length() > 300) {
            return true;
        }
        if (MemoryContextBoundary.containsFence(content)
                || StrUtil.containsIgnoreCase(
                        content, "System note: The following is recalled memory context")) {
            return true;
        }
        for (String pattern : TRANSIENT_PATTERNS) {
            if (StrUtil.containsIgnoreCase(content, pattern)) {
                return true;
            }
        }
        if (hasExplicitLongTermPrefix(content)) {
            return false;
        }
        for (String pattern : WEAK_TRANSIENT_PATTERNS) {
            if (StrUtil.containsIgnoreCase(content, pattern)) {
                return true;
            }
        }
        return false;
    }

    /** 判断内容是否用稳定前缀明确表达长期记忆价值。 */
    private boolean hasExplicitLongTermPrefix(String content) {
        String normalized = StrUtil.nullToEmpty(content).trim();
        for (String prefix : LONG_TERM_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 判断是否已有相同或更泛化的条目。 */
    private boolean containsEntry(List<String> entries, String candidate) {
        for (String entry : entries) {
            if (entry.equals(candidate) || entry.contains(candidate) || candidate.contains(entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取Today记忆。
     *
     * @return 返回读取到的Today记忆。
     */
    private String readTodayMemory() {
        File file = todayMemoryFile();
        if (!file.exists()) {
            return "";
        }
        return FileUtil.readUtf8String(file).trim();
    }

    /**
     * 追加Today Entry。
     *
     * @param content 待处理内容。
     * @return 返回Today Entry结果。
     */
    private String appendTodayEntry(String content) {
        String normalized = normalizeDailyEntry(content);
        if (StrUtil.isBlank(normalized)) {
            return "今日记忆内容不能为空。";
        }

        File file = todayMemoryFile();
        if (!file.exists() || StrUtil.isBlank(FileUtil.readUtf8String(file))) {
            FileUtil.writeUtf8String(
                    "# "
                            + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                            + System.lineSeparator()
                            + System.lineSeparator(),
                    file);
        }

        String existing = FileUtil.readUtf8String(file);
        if (existing.contains(normalized)) {
            return "今日记忆已存在。";
        }

        StringBuilder entry = new StringBuilder();
        if (!existing.endsWith(System.lineSeparator())) {
            entry.append(System.lineSeparator());
        }
        entry.append("- ").append(normalized).append(System.lineSeparator());
        FileUtil.appendUtf8String(entry.toString(), file);
        return "已写入 " + MemoryConstants.TARGET_TODAY + "。";
    }

    /**
     * 规范化Daily Entry。
     *
     * @param content 待处理内容。
     * @return 返回Daily Entry结果。
     */
    private String normalizeDailyEntry(String content) {
        String normalized = normalizeEntry(content);
        if (normalized.length() > 500) {
            return normalized.substring(0, 500).trim() + "...";
        }
        return normalized;
    }

    /**
     * 执行记忆目录相关逻辑。
     *
     * @return 返回记忆Dir结果。
     */
    private File memoryDir() {
        return FileUtil.file(
                appConfig.getRuntime().getHome(), MemoryConstants.DAILY_MEMORY_DIR_NAME);
    }

    /**
     * 执行today记忆文件相关逻辑。
     *
     * @return 返回today记忆文件结果。
     */
    private File todayMemoryFile() {
        return FileUtil.file(
                memoryDir(), LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md");
    }
}
