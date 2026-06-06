package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 承载提供方展示Grouping相关状态和辅助逻辑。 */
public final class ProviderDisplayGrouping {
    /** 创建提供方展示Grouping实例。 */
    private ProviderDisplayGrouping() {}

    /**
     * 执行提供方展示相关逻辑。
     *
     * @param providerKey 提供方键标识或键值。
     * @param provider 模型或能力提供方。
     * @return 返回提供方展示结果。
     */
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

    /**
     * 执行群组相关逻辑。
     *
     * @param items items 参数。
     * @return 返回群组结果。
     */
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

    /**
     * 执行flattenRows相关逻辑。
     *
     * @param rows rows 参数。
     * @return 返回flatten Rows结果。
     */
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

    /**
     * 执行dedupe相关逻辑。
     *
     * @param items items 参数。
     * @return 返回dedupe结果。
     */
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

    /** 承载提供方展示相关状态和辅助逻辑。 */
    public static class ProviderDisplay {
        /** 记录提供方展示中的提供方键。 */
        private String providerKey;

        /** 记录提供方展示中的label。 */
        private String label;

        /** 记录提供方展示中的群组标识。 */
        private String groupId;

        /** 记录提供方展示中的群组Label。 */
        private String groupLabel;

        /** 记录提供方展示中的群组描述。 */
        private String groupDescription;

        /** 记录提供方展示中的展示描述。 */
        private String displayDescription;

        /**
         * 读取提供方键。
         *
         * @return 返回读取到的提供方键。
         */
        public String getProviderKey() {
            return providerKey;
        }

        /**
         * 读取Label。
         *
         * @return 返回读取到的Label。
         */
        public String getLabel() {
            return label;
        }

        /**
         * 读取群组标识。
         *
         * @return 返回读取到的群组标识。
         */
        public String getGroupId() {
            return groupId;
        }

        /**
         * 读取群组Label。
         *
         * @return 返回读取到的群组Label。
         */
        public String getGroupLabel() {
            return groupLabel;
        }

        /**
         * 读取群组Description。
         *
         * @return 返回读取到的群组Description。
         */
        public String getGroupDescription() {
            return groupDescription;
        }

        /**
         * 读取展示Description。
         *
         * @return 返回读取到的展示Description。
         */
        public String getDisplayDescription() {
            return displayDescription;
        }
    }

    /** 承载Item相关状态和辅助逻辑。 */
    public static class Item {
        /** 记录Item中的提供方键。 */
        private final String providerKey;

        /** 记录Item中的label。 */
        private final String label;

        /** 记录Item中的群组标识。 */
        private final String groupId;

        /** 记录Item中的群组Label。 */
        private final String groupLabel;

        /** 记录Item中的群组描述。 */
        private final String groupDescription;

        /** 记录Item中的展示描述。 */
        private final String displayDescription;

        /** 记录Item中的展示Order。 */
        private final int displayOrder;

        /** 记录Item中的载荷。 */
        private final Object payload;

        /**
         * 创建Item实例，并注入运行所需依赖。
         *
         * @param providerKey 提供方键标识或键值。
         * @param label label 参数。
         * @param groupId group标识。
         * @param groupLabel groupLabel 参数。
         * @param groupDescription groupDescription 参数。
         * @param displayDescription 展示Description参数。
         * @param payload 待签名或解析的载荷内容。
         */
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

        /**
         * 创建Item实例，并注入运行所需依赖。
         *
         * @param providerKey 提供方键标识或键值。
         * @param label label 参数。
         * @param groupId group标识。
         * @param groupLabel groupLabel 参数。
         * @param groupDescription groupDescription 参数。
         * @param displayDescription 展示Description参数。
         * @param displayOrder 展示Order参数。
         * @param payload 待签名或解析的载荷内容。
         */
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

        /**
         * 读取提供方键。
         *
         * @return 返回读取到的提供方键。
         */
        public String getProviderKey() {
            return providerKey;
        }

        /**
         * 读取Label。
         *
         * @return 返回读取到的Label。
         */
        public String getLabel() {
            return label;
        }

        /**
         * 读取群组标识。
         *
         * @return 返回读取到的群组标识。
         */
        public String getGroupId() {
            return groupId;
        }

        /**
         * 读取群组Label。
         *
         * @return 返回读取到的群组Label。
         */
        public String getGroupLabel() {
            return groupLabel;
        }

        /**
         * 读取群组Description。
         *
         * @return 返回读取到的群组Description。
         */
        public String getGroupDescription() {
            return groupDescription;
        }

        /**
         * 读取展示Description。
         *
         * @return 返回读取到的展示Description。
         */
        public String getDisplayDescription() {
            return displayDescription;
        }

        /**
         * 读取展示Order。
         *
         * @return 返回读取到的展示Order。
         */
        public int getDisplayOrder() {
            return displayOrder;
        }

        /**
         * 读取Payload。
         *
         * @return 返回读取到的Payload。
         */
        public Object getPayload() {
            return payload;
        }
    }

    /** 承载Row相关状态和辅助逻辑。 */
    public static class Row {
        /** 记录Row中的kind。 */
        private final String kind;

        /** 记录Row中的群组标识。 */
        private final String groupId;

        /** 记录Row中的label。 */
        private final String label;

        /** 记录Row中的描述。 */
        private final String description;

        /** 保存members集合，维持调用顺序或去重语义。 */
        private final List<Item> members;

        /**
         * 创建Row实例，并注入运行所需依赖。
         *
         * @param kind kind 参数。
         * @param groupId group标识。
         * @param label label 参数。
         * @param description 描述参数。
         * @param members members 参数。
         */
        private Row(
                String kind, String groupId, String label, String description, List<Item> members) {
            this.kind = kind;
            this.groupId = groupId;
            this.label = label;
            this.description = description;
            this.members = members == null ? new ArrayList<Item>() : new ArrayList<Item>(members);
        }

        /**
         * 执行single相关逻辑。
         *
         * @param item item 参数。
         * @return 返回single结果。
         */
        static Row single(Item item) {
            List<Item> members = new ArrayList<Item>();
            members.add(item);
            return new Row("single", "", item.getLabel(), item.getDisplayDescription(), members);
        }

        /**
         * 执行群组相关逻辑。
         *
         * @param groupId group标识。
         * @param label label 参数。
         * @param description 描述参数。
         * @param members members 参数。
         * @return 返回群组结果。
         */
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

        /**
         * 读取Kind。
         *
         * @return 返回读取到的Kind。
         */
        public String getKind() {
            return kind;
        }

        /**
         * 读取群组标识。
         *
         * @return 返回读取到的群组标识。
         */
        public String getGroupId() {
            return groupId;
        }

        /**
         * 读取Label。
         *
         * @return 返回读取到的Label。
         */
        public String getLabel() {
            return label;
        }

        /**
         * 读取Description。
         *
         * @return 返回读取到的Description。
         */
        public String getDescription() {
            return description;
        }

        /**
         * 读取Members。
         *
         * @return 返回读取到的Members。
         */
        public List<Item> getMembers() {
            return new ArrayList<Item>(members);
        }
    }
}
