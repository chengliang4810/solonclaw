package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.ProviderDisplayGrouping;
import java.util.ArrayList;
import java.util.List;

/** Local terminal model picker built on top of the normal /model command. */
public class TerminalModelPicker {
    private final AppConfig appConfig;
    private final LlmProviderService llmProviderService;

    public TerminalModelPicker(AppConfig appConfig, LlmProviderService llmProviderService) {
        this.appConfig = appConfig;
        this.llmProviderService = llmProviderService;
    }

    public boolean isPickerCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim().toLowerCase();
        return "/models".equals(value)
                || "/model pick".equals(value)
                || value.startsWith("/model pick ");
    }

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

    List<ModelChoice> choices() {
        return flattenRows(groupedRows());
    }

    private List<ModelChoice> flattenRows(List<ProviderDisplayGrouping.Row> rows) {
        List<ModelChoice> result = new ArrayList<ModelChoice>();
        for (ProviderDisplayGrouping.Item item : ProviderDisplayGrouping.flattenRows(rows)) {
            result.add((ModelChoice) item.getPayload());
        }
        return result;
    }

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

    private LlmProviderService.ResolvedProvider safeResolve(String provider, String model) {
        try {
            return llmProviderService.resolveProvider(provider, model);
        } catch (Exception e) {
            return null;
        }
    }

    private String currentId() {
        LlmProviderService.ResolvedProvider resolved =
                safeResolve(defaultProviderKey(), defaultModel());
        if (resolved == null) {
            return "";
        }
        return new ModelChoice(resolved.getProviderKey(), resolved.getModel(), resolved.getLabel())
                .id();
    }

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

    static class ModelChoice {
        private final String providerKey;
        private final String model;
        private final String label;
        private final String displayDescription;
        private final String groupId;
        private final String groupLabel;
        private final String groupDescription;

        ModelChoice(String providerKey, String model, String label) {
            this(providerKey, model, label, "", "", "", "");
        }

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

        String id() {
            return StrUtil.isBlank(providerKey) ? model : providerKey + ":" + model;
        }

        String displayText() {
            return StrUtil.blankToDefault(
                    displayDescription, StrUtil.blankToDefault(label, providerKey));
        }

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
