package com.jimuqu.solon.claw.context;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.MemoryTurnContext;
import com.jimuqu.solon.claw.core.service.MemoryManager;
import com.jimuqu.solon.claw.core.service.MemoryProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认记忆管理器。 */
public class DefaultMemoryManager implements MemoryManager {
    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryManager.class);
    private static final String BUILTIN_PROVIDER_NAME = "builtin";

    private final List<MemoryProvider> providers;

    public DefaultMemoryManager(List<MemoryProvider> providers) {
        this.providers = normalizeProviders(providers);
    }

    /** 内建记忆始终优先，外部 provider 最多保留一个，避免上下文与同步链路膨胀。 */
    private List<MemoryProvider> normalizeProviders(List<MemoryProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            return Collections.emptyList();
        }

        List<MemoryProvider> normalized = new ArrayList<MemoryProvider>();
        MemoryProvider externalProvider = null;
        boolean builtinAdded = false;
        for (MemoryProvider provider : providers) {
            if (provider == null) {
                continue;
            }
            if (isBuiltin(provider)) {
                if (!builtinAdded) {
                    normalized.add(0, provider);
                    builtinAdded = true;
                }
                continue;
            }
            if (externalProvider == null) {
                externalProvider = provider;
                normalized.add(provider);
            } else {
                log.warn(
                        "Rejected memory provider '{}' because external provider '{}' is already registered; only one external memory provider is allowed.",
                        provider.name(),
                        externalProvider.name());
            }
        }
        return normalized;
    }

    private boolean isBuiltin(MemoryProvider provider) {
        return BUILTIN_PROVIDER_NAME.equals(provider.name());
    }

    @Override
    public String buildSystemPrompt(String sourceKey) throws Exception {
        StringBuilder buffer = new StringBuilder();
        for (MemoryProvider provider : providers) {
            String block = provider.systemPromptBlock(sourceKey);
            if (StrUtil.isBlank(block)) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(MemoryContextBoundary.ensureContextBlock(block));
        }
        return buffer.toString();
    }

    @Override
    public String prefetch(String sourceKey, String userMessage) throws Exception {
        StringBuilder buffer = new StringBuilder();
        for (MemoryProvider provider : providers) {
            String block = provider.prefetch(sourceKey, userMessage);
            if (StrUtil.isBlank(block)) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(MemoryContextBoundary.ensureContextBlock(block));
        }
        return buffer.toString();
    }

    @Override
    public void syncTurn(String sourceKey, String userMessage, String assistantMessage)
            throws Exception {
        syncTurn(
                MemoryTurnContext.builder()
                        .sourceKey(sourceKey)
                        .userMessage(userMessage)
                        .assistantMessage(assistantMessage)
                        .build());
    }

    @Override
    public void syncTurn(MemoryTurnContext context) throws Exception {
        for (MemoryProvider provider : providers) {
            provider.syncTurn(context);
        }
    }
}
