package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Display-only folding for provider/model picker surfaces. */
public final class ProviderDisplayGrouping {
    private ProviderDisplayGrouping() {}

    public static ProviderDisplay providerDisplay(
            String providerKey, AppConfig.ProviderConfig provider) {
        ProviderDisplay display = new ProviderDisplay();
        display.providerKey = StrUtil.nullToEmpty(providerKey).trim();
        display.label =
                StrUtil.blankToDefault(
                        provider == null ? "" : provider.getName(), display.providerKey);
        if (provider != null) {
            display.groupId = StrUtil.nullToEmpty(provider.getGroupId()).trim();
            display.groupLabel = StrUtil.nullToEmpty(provider.getGroupLabel()).trim();
            display.groupDescription = StrUtil.nullToEmpty(provider.getGroupDescription()).trim();
            display.displayDescription =
                    StrUtil.nullToEmpty(provider.getDisplayDescription()).trim();
        }
        if (StrUtil.isNotBlank(display.groupId)) {
            display.groupId = display.groupId.toLowerCase();
        }
        if (StrUtil.isBlank(display.groupLabel)) {
            display.groupLabel = display.label;
        }
        return display;
    }

    public static List<Row> group(List<Item> items) {
        List<Item> deduped = dedupe(items);
        Map<String, List<Item>> membersByGroup = new LinkedHashMap<String, List<Item>>();
        for (Item item : deduped) {
            if (item == null || StrUtil.isBlank(item.getGroupId())) {
                continue;
            }
            List<Item> groupItems = membersByGroup.get(item.getGroupId());
            if (groupItems == null) {
                groupItems = new ArrayList<Item>();
                membersByGroup.put(item.getGroupId(), groupItems);
            }
            groupItems.add(item);
        }

        List<Row> rows = new ArrayList<Row>();
        List<String> emittedGroups = new ArrayList<String>();
        for (Item item : deduped) {
            if (item == null) {
                continue;
            }
            String groupId = item.getGroupId();
            if (StrUtil.isBlank(groupId)) {
                rows.add(Row.single(item));
                continue;
            }
            if (emittedGroups.contains(groupId)) {
                continue;
            }
            emittedGroups.add(groupId);
            List<Item> members = membersByGroup.get(groupId);
            if (members == null || members.size() <= 1) {
                rows.add(Row.single(item));
            } else {
                rows.add(
                        Row.group(
                                groupId,
                                item.getGroupLabel(),
                                item.getGroupDescription(),
                                members));
            }
        }
        return rows;
    }

    public static List<Item> flattenRows(List<Row> rows) {
        List<Item> result = new ArrayList<Item>();
        if (rows == null) {
            return result;
        }
        for (Row row : rows) {
            if (row == null) {
                continue;
            }
            result.addAll(row.getMembers());
        }
        return result;
    }

    private static List<Item> dedupe(List<Item> items) {
        List<Item> result = new ArrayList<Item>();
        List<String> seen = new ArrayList<String>();
        if (items == null) {
            return result;
        }
        for (Item item : items) {
            if (item == null || StrUtil.isBlank(item.getProviderKey())) {
                continue;
            }
            String key = item.getProviderKey().toLowerCase();
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            result.add(item);
        }
        return result;
    }

    public static class ProviderDisplay {
        private String providerKey;
        private String label;
        private String groupId;
        private String groupLabel;
        private String groupDescription;
        private String displayDescription;

        public String getProviderKey() {
            return providerKey;
        }

        public String getLabel() {
            return label;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getGroupLabel() {
            return groupLabel;
        }

        public String getGroupDescription() {
            return groupDescription;
        }

        public String getDisplayDescription() {
            return displayDescription;
        }
    }

    public static class Item {
        private final String providerKey;
        private final String label;
        private final String groupId;
        private final String groupLabel;
        private final String groupDescription;
        private final String displayDescription;
        private final int displayOrder;
        private final Object payload;

        public Item(
                String providerKey,
                String label,
                String groupId,
                String groupLabel,
                String groupDescription,
                String displayDescription,
                Object payload) {
            this(
                    providerKey,
                    label,
                    groupId,
                    groupLabel,
                    groupDescription,
                    displayDescription,
                    Integer.MAX_VALUE,
                    payload);
        }

        public Item(
                String providerKey,
                String label,
                String groupId,
                String groupLabel,
                String groupDescription,
                String displayDescription,
                int displayOrder,
                Object payload) {
            this.providerKey = StrUtil.nullToEmpty(providerKey).trim().toLowerCase();
            this.label = StrUtil.nullToEmpty(label).trim();
            this.groupId = StrUtil.nullToEmpty(groupId).trim().toLowerCase();
            this.groupLabel = StrUtil.nullToEmpty(groupLabel).trim();
            this.groupDescription = StrUtil.nullToEmpty(groupDescription).trim();
            this.displayDescription = StrUtil.nullToEmpty(displayDescription).trim();
            this.displayOrder = displayOrder;
            this.payload = payload;
        }

        public String getProviderKey() {
            return providerKey;
        }

        public String getLabel() {
            return label;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getGroupLabel() {
            return groupLabel;
        }

        public String getGroupDescription() {
            return groupDescription;
        }

        public String getDisplayDescription() {
            return displayDescription;
        }

        public int getDisplayOrder() {
            return displayOrder;
        }

        public Object getPayload() {
            return payload;
        }
    }

    public static class Row {
        private final String kind;
        private final String groupId;
        private final String label;
        private final String description;
        private final List<Item> members;

        private Row(
                String kind, String groupId, String label, String description, List<Item> members) {
            this.kind = kind;
            this.groupId = groupId;
            this.label = label;
            this.description = description;
            this.members = members == null ? new ArrayList<Item>() : new ArrayList<Item>(members);
        }

        static Row single(Item item) {
            List<Item> members = new ArrayList<Item>();
            members.add(item);
            return new Row("single", "", item.getLabel(), item.getDisplayDescription(), members);
        }

        static Row group(String groupId, String label, String description, List<Item> members) {
            List<Item> sorted = new ArrayList<Item>(members);
            Collections.sort(
                    sorted,
                    (a, b) -> {
                        int compared = Integer.compare(a.getDisplayOrder(), b.getDisplayOrder());
                        if (compared != 0) {
                            return compared;
                        }
                        return a.getProviderKey().compareTo(b.getProviderKey());
                    });
            return new Row("group", groupId, label, description, sorted);
        }

        public String getKind() {
            return kind;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }

        public List<Item> getMembers() {
            return new ArrayList<Item>(members);
        }
    }
}
