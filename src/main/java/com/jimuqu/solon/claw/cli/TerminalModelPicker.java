package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.LlmProviderService;
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
        List<ModelChoice> choices = choices();
        if (choices.isEmpty()) {
            return "当前没有可选择的模型。";
        }
        String currentId = currentId();
        StringBuilder buffer = new StringBuilder("模型选择：\n");
        for (int i = 0; i < choices.size(); i++) {
            ModelChoice choice = choices.get(i);
            buffer.append(i + 1)
                    .append(". ")
                    .append(choice.id())
                    .append(" - ")
                    .append(StrUtil.blankToDefault(choice.label, choice.providerKey));
            if (choice.id().equals(currentId)) {
                buffer.append(" (当前)");
            }
            buffer.append('\n');
        }
        buffer.append("使用：/model pick <编号>，或直接 /model <provider:model>");
        return buffer.toString();
    }

    List<ModelChoice> choices() {
        List<ModelChoice> result = new ArrayList<ModelChoice>();
        if (appConfig == null || llmProviderService == null) {
            return result;
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
        return result;
    }

    private void addResolved(List<ModelChoice> result, LlmProviderService.ResolvedProvider resolved) {
        if (resolved == null || StrUtil.isBlank(resolved.getModel())) {
            return;
        }
        ModelChoice choice =
                new ModelChoice(resolved.getProviderKey(), resolved.getModel(), resolved.getLabel());
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
        LlmProviderService.ResolvedProvider resolved = safeResolve(defaultProviderKey(), defaultModel());
        if (resolved == null) {
            return "";
        }
        return new ModelChoice(resolved.getProviderKey(), resolved.getModel(), resolved.getLabel()).id();
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

        ModelChoice(String providerKey, String model, String label) {
            this.providerKey = StrUtil.nullToEmpty(providerKey).trim().toLowerCase();
            this.model = StrUtil.nullToEmpty(model).trim();
            this.label = StrUtil.nullToEmpty(label).trim();
        }

        String id() {
            return StrUtil.isBlank(providerKey) ? model : providerKey + ":" + model;
        }
    }
}
