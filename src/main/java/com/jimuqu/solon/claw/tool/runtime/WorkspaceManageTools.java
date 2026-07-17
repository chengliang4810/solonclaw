package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ContextFileConstants;
import com.jimuqu.solon.claw.web.DashboardWorkspaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供工作区查询和受控文件维护工具，复用 Dashboard 人格工作区服务。 */
public class WorkspaceManageTools {
    /** Dashboard 工作区服务，用于读取或维护受控文件和日记内容。 */
    private final DashboardWorkspaceService workspaceService;

    /**
     * 创建工作区查询工具。
     *
     * @param workspaceService Dashboard 工作区服务。
     */
    public WorkspaceManageTools(DashboardWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * 查询受控工作区文件或日记内容。
     *
     * @param action 操作名称。
     * @param key 工作区文件 key。
     * @param relativePath 日记相对路径。
     * @param content 保存文件时写入的内容。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "workspace_manage",
            description =
                    "Inspect and maintain dashboard workspace files and recoverable daily-memory archives. Actions: files, file, save_file, upsert_note, remove_note, restore_file, diaries, diary, archive_status, archive_run, archive_restore.")
    public String workspaceManage(
            @Param(
                            name = "action",
                            description =
                                    "files, file, save_file, upsert_note, remove_note, restore_file, diaries, diary, archive_status, archive_run, archive_restore")
                    String action,
            @Param(name = "key", required = false, description = "Workspace file key") String key,
            @Param(
                            name = "relativePath",
                            required = false,
                            description = "Diary relative path or note section title")
                    String relativePath,
            @Param(
                            name = "content",
                            required = false,
                            description = "File content or one note entry")
                    String content) {
        try {
            if (workspaceService == null) {
                return ToolResultEnvelope.error("workspace service unavailable").toJson();
            }
            Map<String, Object> result = run(action, key, relativePath, content);
            return ToolResultEnvelope.ok("工作区操作完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行工作区只读查询动作。
     *
     * @param action 操作名称。
     * @param key 工作区文件 key。
     * @param relativePath 日记相对路径。
     * @param content 保存文件时写入的内容。
     * @return 返回 Dashboard 工作区服务结果。
     */
    private Map<String, Object> run(String action, String key, String relativePath, String content)
            throws Exception {
        String normalized = action == null ? "files" : action.trim().toLowerCase(Locale.ROOT);
        if ("file".equals(normalized) || "get_file".equals(normalized)) {
            return workspaceService.getFile(key);
        }
        if ("save_file".equals(normalized) || "save".equals(normalized)) {
            rejectMemoryWrite(key);
            return workspaceService.saveFile(key, content);
        }
        if ("upsert_note".equals(normalized) || "upsert".equals(normalized)) {
            return mutateNote(key, relativePath, content, false);
        }
        if ("remove_note".equals(normalized) || "remove".equals(normalized)) {
            return mutateNote(key, relativePath, content, true);
        }
        if ("restore_file".equals(normalized) || "restore".equals(normalized)) {
            rejectMemoryWrite(key);
            return workspaceService.restoreFile(key);
        }
        if ("diaries".equals(normalized) || "list_diaries".equals(normalized)) {
            return workspaceService.listDiaryFiles();
        }
        if ("diary".equals(normalized) || "get_diary".equals(normalized)) {
            return workspaceService.getDiaryFile(relativePath);
        }
        if ("archive_status".equals(normalized)) {
            return workspaceService.memoryArchiveState();
        }
        if ("archive_run".equals(normalized)) {
            return workspaceService.runMemoryArchive();
        }
        if ("archive_restore".equals(normalized)) {
            return workspaceService.restoreMemoryArchive(relativePath);
        }
        return workspaceService.getFiles();
    }

    /**
     * 在受控工作区笔记中按小节和条目标识原子更新或删除单条内容。
     *
     * @param key 仅允许 tools 或 heartbeat。
     * @param section 可选 Markdown 二级标题。
     * @param content 要更新或删除的单条笔记。
     * @param remove 是否删除匹配条目。
     * @return 返回保存后的工作区文件。
     */
    private Map<String, Object> mutateNote(
            String key, String section, String content, boolean remove) {
        String normalizedKey = ContextFileConstants.normalizeKey(key);
        if (!ContextFileConstants.KEY_TOOLS.equals(normalizedKey)
                && !ContextFileConstants.KEY_HEARTBEAT.equals(normalizedKey)) {
            throw new IllegalArgumentException(
                    "Note mutation only supports TOOLS.md and HEARTBEAT.md.");
        }
        String note = normalizeNote(content);
        String title = normalizeSection(section, normalizedKey);
        if (!remove
                && (!note.equals(SecretRedactor.redact(note, note.length() + 100))
                        || !title.equals(SecretRedactor.redact(title, title.length() + 100)))) {
            throw new IllegalArgumentException("Workspace notes must not contain credentials.");
        }
        Map<String, Object> current = workspaceService.getFile(normalizedKey);
        Object currentContent = current == null ? null : current.get("content");
        String merged =
                mergeMarkdownNote(
                        currentContent == null ? "" : String.valueOf(currentContent),
                        title,
                        note,
                        remove);
        return workspaceService.saveFile(normalizedKey, merged);
    }

    /** 将小节标题规范为单行 Markdown 标题文本。 */
    private String normalizeSection(String section, String key) {
        String value = section == null ? "" : section.replace('\r', ' ').replace('\n', ' ').trim();
        while (value.startsWith("#")) {
            value = value.substring(1).trim();
        }
        if (value.length() == 0) {
            value = ContextFileConstants.KEY_HEARTBEAT.equals(key) ? "任务" : "本地配置";
        }
        if (value.length() > 120) {
            throw new IllegalArgumentException("Workspace note section is too long.");
        }
        return value;
    }

    /** 将单条笔记规范为不带项目符号的一行文本。 */
    private String normalizeNote(String content) {
        String value = content == null ? "" : content.replace('\r', ' ').replace('\n', ' ').trim();
        while (value.startsWith("-") || value.startsWith("*") || value.startsWith("+")) {
            value = value.substring(1).trim();
        }
        if (value.length() == 0) {
            throw new IllegalArgumentException("Workspace note content is required.");
        }
        if (value.length() > 1000) {
            throw new IllegalArgumentException("Workspace note content is too long.");
        }
        return value;
    }

    /**
     * 在 Markdown 二级标题范围内更新、追加或删除条目，保留其他小节和无关内容。
     *
     * @param markdown 当前文件正文。
     * @param section 目标小节。
     * @param note 目标条目。
     * @param remove 是否删除条目。
     * @return 返回合并后的正文。
     */
    private String mergeMarkdownNote(String markdown, String section, String note, boolean remove) {
        String normalized =
                markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines =
                new ArrayList<String>(java.util.Arrays.asList(normalized.split("\n", -1)));
        int sectionStart = -1;
        int sectionEnd = lines.size();
        String heading = "## " + section;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (sectionStart < 0 && heading.equalsIgnoreCase(line)) {
                sectionStart = i;
                continue;
            }
            if (sectionStart >= 0 && line.startsWith("## ")) {
                sectionEnd = i;
                break;
            }
        }
        if (sectionStart < 0) {
            if (remove) {
                return normalized;
            }
            appendSection(lines, heading, note);
            return joinLines(lines);
        }

        String identity = noteIdentity(note);
        for (int i = sectionStart + 1; i < sectionEnd; i++) {
            String existing = normalizeExistingNote(lines.get(i));
            if (existing.length() == 0 || !identity.equalsIgnoreCase(noteIdentity(existing))) {
                continue;
            }
            if (remove) {
                lines.remove(i);
            } else {
                lines.set(i, "- " + note);
            }
            return joinLines(lines);
        }
        if (!remove) {
            lines.add(sectionEnd, "- " + note);
        }
        return joinLines(lines);
    }

    /** 在文件末尾追加新的二级标题和条目。 */
    private void appendSection(List<String> lines, String heading, String note) {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).trim().length() == 0) {
            lines.remove(lines.size() - 1);
        }
        if (!lines.isEmpty()) {
            lines.add("");
        }
        lines.add(heading);
        lines.add("");
        lines.add("- " + note);
        lines.add("");
    }

    /** 去掉已有 Markdown 项目符号，非条目行返回空字符串。 */
    private String normalizeExistingNote(String line) {
        String value = line == null ? "" : line.trim();
        if (!(value.startsWith("- ") || value.startsWith("* ") || value.startsWith("+ "))) {
            return "";
        }
        return value.substring(2).trim();
    }

    /** 使用冒号前的稳定标签作为更新标识；没有标签时以整条文本去重。 */
    private String noteIdentity(String note) {
        int asciiColon = note.indexOf(':');
        int chineseColon = note.indexOf('：');
        int delimiter =
                asciiColon < 0
                        ? chineseColon
                        : chineseColon < 0 ? asciiColon : Math.min(asciiColon, chineseColon);
        return delimiter > 0 && delimiter <= 80 ? note.substring(0, delimiter).trim() : note.trim();
    }

    /** 使用 LF 重新拼接 Markdown 行。 */
    private String joinLines(List<String> lines) {
        return String.join("\n", lines);
    }

    /**
     * 阻断模型通过工作区管理工具直接覆盖长期记忆，保证记忆写入只能经过审批服务。
     *
     * @param key 工作区文件 key。
     */
    private void rejectMemoryWrite(String key) {
        String normalized = ContextFileConstants.normalizeKey(key);
        if (ContextFileConstants.KEY_MEMORY.equals(normalized)
                || ContextFileConstants.KEY_USER.equals(normalized)
                || ContextFileConstants.KEY_MEMORY_TODAY.equals(normalized)) {
            throw new IllegalArgumentException(
                    "Memory workspace files require the memory tool approval flow.");
        }
    }
}
