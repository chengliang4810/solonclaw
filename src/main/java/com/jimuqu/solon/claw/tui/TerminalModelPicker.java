package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.ProviderDisplayGrouping;
import java.util.ArrayList;
import java.util.List;

/** 承载终端模型Picker相关状态和辅助逻辑。 */
public class TerminalModelPicker {
    /** 注入应用配置，用于终端模型Picker。 */
    private final AppConfig appConfig;

    /** 注入大模型提供方服务，用于调用对应业务能力。 */
    private final LlmProviderService llmProviderService;

    /**
     * 创建终端模型Picker实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param llmProviderService LLM提供方Service标识或键值。
     */
    public TerminalModelPicker(AppConfig appConfig, LlmProviderService llmProviderService) {
        this.appConfig = appConfig;
        this.llmProviderService = llmProviderService;
    }

    /**
     * 判断是否Picker命令。
     *
     * @param input 输入参数。
     * @return 如果Picker命令满足条件则返回 true，否则返回 false。
     */
    public boolean isPickerCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim().toLowerCase();
        return "/models".equals(value)
                || "/model pick".equals(value)
                || value.startsWith("/model pick ");
    }

    /**
     * 解析命令。
     *
     * @param input 输入参数。
     * @return 返回解析后的命令。
     */
    public String resolveCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        String lower = value.toLowerCase();
        if (!lower.startsWith("/model pick ")) {
            return "";
        }
        String indexText = value.substring("/model pick ".length()).trim();
        int index;
        try {
            index = Integer.parseInt(indexText);
        } catch (NumberFormatException e) {
            return "";
        }
        List<ModelChoice> choices = choices();
        if (index < 1 || index > choices.size()) {
            return "";
        }
        return "/model " + choices.get(index - 1).id();
    }

    /**
     * 执行render相关逻辑。
     *
     * @return 返回render结果。
     */
    public String render() {
        List<ProviderDisplayGrouping.Row> rows = groupedRows();
        List<ModelChoice> choices = flattenRows(rows);
        if (choices.isEmpty()) {
            return "当前没有可选择的模型。";
        }
        String currentId = currentId();
        StringBuilder buffer = new StringBuilder("模型选择：\n");
        int index = 1;
        for (ProviderDisplayGrouping.Row row : rows) {
            if ("group".equals(row.getKind())) {
                buffer.append(index)
                        .append(". ")
                        .append(StrUtil.blankToDefault(row.getLabel(), row.getGroupId()))
                        .append(" - ")
                        .append(row.getDescription())
                        .append('\n');
                for (ProviderDisplayGrouping.Item item : row.getMembers()) {
                    ModelChoice choice = (ModelChoice) item.getPayload();
                    appendChoice(buffer, index, choice, currentId, true);
                    index++;
                }
            } else {
                ModelChoice choice = (ModelChoice) row.getMembers().get(0).getPayload();
                appendChoice(buffer, index, choice, currentId, false);
                index++;
            }
        }
        buffer.append("使用：/model pick <编号>，或直接 /model <provider:model>");
        return buffer.toString();
    }

    /**
     * 追加Choice。
     *
     * @param buffer buffer 参数。
     * @param index 索引参数。
     * @param choice choice 参数。
     * @param currentId current标识。
     * @param grouped grouped 参数。
     */
    private void appendChoice(
            StringBuilder buffer,
            int index,
            ModelChoice choice,
            String currentId,
            boolean grouped) {
        if (grouped) {
            buffer.append("   ");
        }
        buffer.append(index)
                .append(". ")
                .append(choice.id())
                .append(" - ")
                .append(choice.displayText());
        if (choice.id().equals(currentId)) {
            buffer.append(" (当前)");
        }
        buffer.append('\n');
    }

    /**
     * 执行choices相关逻辑。
     *
     * @return 返回choices结果。
     */
    List<ModelChoice> choices() {
        return flattenRows(groupedRows());
    }

    /**
     * 执行flattenRows相关逻辑。
     *
     * @param rows rows 参数。
     * @return 返回flatten Rows结果。
     */
    private List<ModelChoice> flattenRows(List<ProviderDisplayGrouping.Row> rows) {
        List<ModelChoice> result = new ArrayList<ModelChoice>();
        for (ProviderDisplayGrouping.Item item : ProviderDisplayGrouping.flattenRows(rows)) {
            result.add((ModelChoice) item.getPayload());
        }
        return result;
    }

    /**
     * 执行groupedRows相关逻辑。
     *
     * @return 返回grouped Rows结果。
     */
    private List<ProviderDisplayGrouping.Row> groupedRows() {
        List<ModelChoice> result = new ArrayList<ModelChoice>();
        if (appConfig == null || llmProviderService == null) {
            return ProviderDisplayGrouping.group(new ArrayList<ProviderDisplayGrouping.Item>());
        }
        addResolved(result, safeResolve(defaultProviderKey(), defaultModel()));
        if (appConfig.getFallbackProviders() != null) {
            for (AppConfig.FallbackProviderConfig fallback : appConfig.getFallbackProviders()) {
                if (fallback == null || StrUtil.isBlank(fallback.getProvider())) {
                    continue;
                }
                addResolved(result, safeResolve(fallback.getProvider(), fallback.getModel()));
            }
        }
        List<ProviderDisplayGrouping.Item> items = new ArrayList<ProviderDisplayGrouping.Item>();
        for (ModelChoice choice : result) {
            items.add(choice.groupingItem(appConfig));
        }
        return ProviderDisplayGrouping.group(items);
    }

    /**
     * 追加Resolved。
     *
     * @param result 结果响应或执行结果。
     * @param resolved resolved 参数。
     */
    private void addResolved(
            List<ModelChoice> result, LlmProviderService.ResolvedProvider resolved) {
        if (resolved == null || StrUtil.isBlank(resolved.getModel())) {
            return;
        }
        ProviderDisplayGrouping.ProviderDisplay display =
                ProviderDisplayGrouping.providerDisplay(
                        resolved.getProviderKey(),
                        appConfig == null
                                ? null
                                : appConfig.getProviders().get(resolved.getProviderKey()));
        ModelChoice choice =
                new ModelChoice(
                        resolved.getProviderKey(),
                        resolved.getModel(),
                        resolved.getLabel(),
                        display.getDisplayDescription(),
                        display.getGroupId(),
                        display.getGroupLabel(),
                        display.getGroupDescription());
        for (ModelChoice existing : result) {
            if (existing.id().equals(choice.id())) {
                return;
            }
        }
        result.add(choice);
    }

    /**
     * 生成安全展示用的Resolve。
     *
     * @param provider 模型或能力提供方。
     * @param model 模型名称。
     * @return 返回safe Resolve结果。
     */
    private LlmProviderService.ResolvedProvider safeResolve(String provider, String model) {
        try {
            return llmProviderService.resolveProvider(provider, model);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行当前标识相关逻辑。
     *
     * @return 返回当前标识。
     */
    private String currentId() {
        LlmProviderService.ResolvedProvider resolved =
                safeResolve(defaultProviderKey(), defaultModel());
        if (resolved == null) {
            return "";
        }
        return new ModelChoice(resolved.getProviderKey(), resolved.getModel(), resolved.getLabel())
                .id();
    }

    /**
     * 执行默认提供方键相关逻辑。
     *
     * @return 返回默认提供方键结果。
     */
    private String defaultProviderKey() {
        if (appConfig == null) {
            return "";
        }
        String provider = "";
        if (appConfig.getModel() != null) {
            provider = StrUtil.nullToEmpty(appConfig.getModel().getProviderKey()).trim();
        }
        if (StrUtil.isBlank(provider) && appConfig.getLlm() != null) {
            provider = StrUtil.nullToEmpty(appConfig.getLlm().getProvider()).trim();
        }
        return provider;
    }

    /**
     * 执行默认模型相关逻辑。
     *
     * @return 返回默认模型结果。
     */
    private String defaultModel() {
        if (appConfig == null) {
            return "";
        }
        String model = "";
        if (appConfig.getModel() != null) {
            model = StrUtil.nullToEmpty(appConfig.getModel().getDefault()).trim();
        }
        if (StrUtil.isBlank(model) && appConfig.getLlm() != null) {
            model = StrUtil.nullToEmpty(appConfig.getLlm().getModel()).trim();
        }
        return model;
    }

    /** 承载模型Choice相关状态和辅助逻辑。 */
    static class ModelChoice {
        /** 记录模型Choice中的提供方键。 */
        private final String providerKey;

        /** 记录模型Choice中的模型。 */
        private final String model;

        /** 记录模型Choice中的label。 */
        private final String label;

        /** 记录模型Choice中的展示描述。 */
        private final String displayDescription;

        /** 记录模型Choice中的群组标识。 */
        private final String groupId;

        /** 记录模型Choice中的群组Label。 */
        private final String groupLabel;

        /** 记录模型Choice中的群组描述。 */
        private final String groupDescription;

        /**
         * 创建模型Choice实例，并注入运行所需依赖。
         *
         * @param providerKey 提供方键标识或键值。
         * @param model 模型名称。
         * @param label label 参数。
         */
        ModelChoice(String providerKey, String model, String label) {
            this(providerKey, model, label, "", "", "", "");
        }

        /**
         * 创建模型Choice实例，并注入运行所需依赖。
         *
         * @param providerKey 提供方键标识或键值。
         * @param model 模型名称。
         * @param label label 参数。
         * @param displayDescription 展示Description参数。
         * @param groupId group标识。
         * @param groupLabel groupLabel 参数。
         * @param groupDescription groupDescription 参数。
         */
        ModelChoice(
                String providerKey,
                String model,
                String label,
                String displayDescription,
                String groupId,
                String groupLabel,
                String groupDescription) {
            this.providerKey = StrUtil.nullToEmpty(providerKey).trim().toLowerCase();
            this.model = StrUtil.nullToEmpty(model).trim();
            this.label = StrUtil.nullToEmpty(label).trim();
            this.displayDescription = StrUtil.nullToEmpty(displayDescription).trim();
            this.groupId = StrUtil.nullToEmpty(groupId).trim();
            this.groupLabel = StrUtil.nullToEmpty(groupLabel).trim();
            this.groupDescription = StrUtil.nullToEmpty(groupDescription).trim();
        }

        /**
         * 执行标识相关逻辑。
         *
         * @return 返回标识。
         */
        String id() {
            return StrUtil.isBlank(providerKey) ? model : providerKey + ":" + model;
        }

        /**
         * 执行展示文本相关逻辑。
         *
         * @return 返回展示Text结果。
         */
        String displayText() {
            return StrUtil.blankToDefault(
                    displayDescription, StrUtil.blankToDefault(label, providerKey));
        }

        /**
         * 执行groupingItem相关逻辑。
         *
         * @param appConfig 应用运行配置。
         * @return 返回grouping Item结果。
         */
        ProviderDisplayGrouping.Item groupingItem(AppConfig appConfig) {
            ProviderDisplayGrouping.ProviderDisplay display =
                    ProviderDisplayGrouping.providerDisplay(
                            providerKey,
                            appConfig == null ? null : appConfig.getProviders().get(providerKey));
            return new ProviderDisplayGrouping.Item(
                    providerKey,
                    StrUtil.blankToDefault(label, display.getLabel()),
                    StrUtil.blankToDefault(groupId, display.getGroupId()),
                    StrUtil.blankToDefault(groupLabel, display.getGroupLabel()),
                    StrUtil.blankToDefault(groupDescription, display.getGroupDescription()),
                    StrUtil.blankToDefault(displayDescription, display.getDisplayDescription()),
                    this);
        }
    }
}
