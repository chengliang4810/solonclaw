package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.HashUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供Todo工具能力，供 Agent 运行时按安全策略调用。 */
@RequiredArgsConstructor
public class TodoTools {
    /** 状态待恢复的统一常量值。 */
    private static final String STATUS_PENDING = "pending";

    /** 状态INPROGRESS的统一常量值。 */
    private static final String STATUS_IN_PROGRESS = "in_progress";

    /** 状态COMPLETED的统一常量值。 */
    private static final String STATUS_COMPLETED = "completed";

    /** 状态CANCELLED的统一常量值。 */
    private static final String STATUS_CANCELLED = "cancelled";

    /** 注入应用配置，用于Todo。 */
    private final AppConfig appConfig;

    /** 记录Todo中的来源键。 */
    private final String sourceKey;

    /**
     * 执行todo相关逻辑。
     *
     * @param todos todos 参数。
     * @param merge merge 参数。
     * @return 返回todo结果。
     */
    @ToolMapping(
            name = "todo",
            description =
                    "Manage the current session task list. Call with no todos to read. Provide todos to create or update items. merge=false replaces the list; merge=true updates by id and appends new items. Each item is {id, content, status} where status is pending, in_progress, completed, or cancelled.")
    public synchronized String todo(
            @Param(
                            name = "todos",
                            description = "Task items to write. Omit to read current list.",
                            required = false)
                    List<TodoItem> todos,
            @Param(
                            name = "merge",
                            description =
                                    "true updates existing items by id and appends new items; false replaces the whole list.",
                            required = false)
                    Boolean merge) {
        List<TodoItem> items = readItems();
        if (todos != null) {
            if (Boolean.TRUE.equals(merge)) {
                items = merge(items, todos);
            } else {
                items = replace(todos);
            }
            writeItems(items);
        }
        return response(items);
    }

    /**
     * 执行replace相关逻辑。
     *
     * @param todos todos 参数。
     * @return 返回replace结果。
     */
    private List<TodoItem> replace(List<TodoItem> todos) {
        List<TodoItem> items = new ArrayList<TodoItem>();
        for (TodoItem item : dedupeById(todos)) {
            items.add(validate(item));
        }
        return items;
    }

    /**
     * 执行merge相关逻辑。
     *
     * @param current current 参数。
     * @param todos todos 参数。
     * @return 返回merge结果。
     */
    private List<TodoItem> merge(List<TodoItem> current, List<TodoItem> todos) {
        Map<String, TodoItem> existing = new LinkedHashMap<String, TodoItem>();
        for (TodoItem item : current) {
            existing.put(item.getId(), copy(item));
        }

        for (TodoItem incoming : dedupeById(todos)) {
            String id = StrUtil.nullToEmpty(incoming == null ? null : incoming.getId()).trim();
            if (StrUtil.isBlank(id)) {
                continue;
            }

            TodoItem currentItem = existing.get(id);
            if (currentItem != null) {
                if (StrUtil.isNotBlank(incoming.getContent())) {
                    currentItem.setContent(incoming.getContent().trim());
                }
                if (StrUtil.isNotBlank(incoming.getStatus())) {
                    currentItem.setStatus(normalizeStatus(incoming.getStatus()));
                }
            } else {
                TodoItem validated = validate(incoming);
                existing.put(validated.getId(), validated);
                current.add(validated);
            }
        }

        List<TodoItem> rebuilt = new ArrayList<TodoItem>();
        Set<String> seen = new LinkedHashSet<String>();
        for (TodoItem item : current) {
            TodoItem selected = existing.get(item.getId());
            if (selected != null && seen.add(selected.getId())) {
                rebuilt.add(selected);
            }
        }
        return rebuilt;
    }

    /**
     * 执行dedupe根据标识相关逻辑。
     *
     * @param todos todos 参数。
     * @return 返回dedupe根据标识。
     */
    private List<TodoItem> dedupeById(List<TodoItem> todos) {
        Map<String, Integer> lastIndex = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < todos.size(); i++) {
            TodoItem item = todos.get(i);
            String id = StrUtil.nullToEmpty(item == null ? null : item.getId()).trim();
            lastIndex.put(StrUtil.blankToDefault(id, "?"), i);
        }

        List<TodoItem> result = new ArrayList<TodoItem>();
        for (int i = 0; i < todos.size(); i++) {
            if (lastIndex.containsValue(i)) {
                result.add(todos.get(i));
            }
        }
        return result;
    }

    /**
     * 执行validate相关逻辑。
     *
     * @param item item 参数。
     * @return 返回validate结果。
     */
    private TodoItem validate(TodoItem item) {
        TodoItem result = new TodoItem();
        String id = StrUtil.nullToEmpty(item == null ? null : item.getId()).trim();
        result.setId(StrUtil.blankToDefault(id, "?"));
        String content = StrUtil.nullToEmpty(item == null ? null : item.getContent()).trim();
        result.setContent(StrUtil.blankToDefault(content, "(no description)"));
        result.setStatus(normalizeStatus(item == null ? null : item.getStatus()));
        return result;
    }

    /**
     * 规范化状态。
     *
     * @param status 状态参数。
     * @return 返回状态。
     */
    private String normalizeStatus(String status) {
        String normalized = StrUtil.nullToEmpty(status).trim().toLowerCase();
        if (STATUS_PENDING.equals(normalized)
                || STATUS_IN_PROGRESS.equals(normalized)
                || STATUS_COMPLETED.equals(normalized)
                || STATUS_CANCELLED.equals(normalized)) {
            return normalized;
        }
        return STATUS_PENDING;
    }

    /**
     * 读取Items。
     *
     * @return 返回读取到的Items。
     */
    private List<TodoItem> readItems() {
        File file = todoFile();
        if (!file.exists()) {
            return new ArrayList<TodoItem>();
        }
        try {
            ONode node = ONode.ofJson(FileUtil.readUtf8String(file));
            if (!node.isArray()) {
                return new ArrayList<TodoItem>();
            }
            List<TodoItem> items = new ArrayList<TodoItem>();
            for (int i = 0; i < node.size(); i++) {
                TodoItem item = new TodoItem();
                item.setId(node.get(i).get("id").getString());
                item.setContent(node.get(i).get("content").getString());
                item.setStatus(node.get(i).get("status").getString());
                items.add(validate(item));
            }
            return items;
        } catch (Exception e) {
            return new ArrayList<TodoItem>();
        }
    }

    /**
     * 写入Items。
     *
     * @param items items 参数。
     */
    private void writeItems(List<TodoItem> items) {
        File file = todoFile();
        FileUtil.mkParentDirs(file);
        FileUtil.writeUtf8String(ONode.serialize(items), file);
    }

    /**
     * 执行响应相关逻辑。
     *
     * @param items items 参数。
     * @return 返回响应结果。
     */
    private String response(List<TodoItem> items) {
        int pending = 0;
        int inProgress = 0;
        int completed = 0;
        int cancelled = 0;
        for (TodoItem item : items) {
            if (STATUS_PENDING.equals(item.getStatus())) {
                pending++;
            } else if (STATUS_IN_PROGRESS.equals(item.getStatus())) {
                inProgress++;
            } else if (STATUS_COMPLETED.equals(item.getStatus())) {
                completed++;
            } else if (STATUS_CANCELLED.equals(item.getStatus())) {
                cancelled++;
            }
        }

        ONode summary =
                new ONode()
                        .set("total", items.size())
                        .set("pending", pending)
                        .set("in_progress", inProgress)
                        .set("completed", completed)
                        .set("cancelled", cancelled);
        return ToolResultEnvelope.ok("Todo list updated")
                .data("todos", ONode.ofBean(redactedItems(items)))
                .data("summary", summary)
                .preview(summary.toJson())
                .toJson();
    }

    /**
     * 执行redactedItems相关逻辑。
     *
     * @param items items 参数。
     * @return 返回redacted Items结果。
     */
    private List<TodoItem> redactedItems(List<TodoItem> items) {
        List<TodoItem> safe = new ArrayList<TodoItem>();
        for (TodoItem item : items) {
            TodoItem copy = copy(item);
            copy.setId(SecretRedactor.redact(copy.getId(), 200));
            copy.setContent(SecretRedactor.redact(copy.getContent(), 1000));
            safe.add(copy);
        }
        return safe;
    }

    /**
     * 执行copy相关逻辑。
     *
     * @param item item 参数。
     * @return 返回copy结果。
     */
    private TodoItem copy(TodoItem item) {
        TodoItem copy = new TodoItem();
        copy.setId(item.getId());
        copy.setContent(item.getContent());
        copy.setStatus(item.getStatus());
        return copy;
    }

    /**
     * 执行todo文件相关逻辑。
     *
     * @return 返回todo文件结果。
     */
    private File todoFile() {
        String name = "todo-" + HashUtil.apHash(StrUtil.nullToEmpty(sourceKey)) + ".json";
        return FileUtil.file(appConfig.getRuntime().getCacheDir(), name);
    }

    /** 承载TodoItem相关状态和辅助逻辑。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class TodoItem {
        /** 记录TodoItem中的标识。 */
        @Param(description = "Unique task id chosen by the agent.")
        private String id;

        /** 记录TodoItem中的content。 */
        @Param(description = "Task description.")
        private String content;

        /** 记录TodoItem中的状态。 */
        @Param(description = "Task status: pending, in_progress, completed, or cancelled.")
        private String status;
    }
}
