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
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryManager.class);

    /** BUILTIN提供方名称的统一常量值。 */
    private static final String BUILTIN_PROVIDER_NAME = "builtin";

    /** 保存providers集合，维持调用顺序或去重语义。 */
    private final List<MemoryProvider> providers;

    /**
     * 创建默认记忆管理器实例，并注入运行所需依赖。
     *
     * @param providers 能力提供方列表。
     */
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

    /**
     * 判断是否Builtin。
     *
     * @param provider 模型或能力提供方。
     * @return 如果Builtin满足条件则返回 true，否则返回 false。
     */
    private boolean isBuiltin(MemoryProvider provider) {
        return BUILTIN_PROVIDER_NAME.equals(provider.name());
    }

    /**
     * 构建System提示词。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回创建好的System提示词。
     */
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

    /**
     * 执行prefetch相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param userMessage 用户消息参数。
     * @return 返回prefetch结果。
     */
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

    /**
     * 执行同步Turn相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param userMessage 用户消息参数。
     * @param assistantMessage assistant消息参数。
     */
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

    /**
     * 执行同步Turn相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     */
    @Override
    public void syncTurn(MemoryTurnContext context) throws Exception {
        for (MemoryProvider provider : providers) {
            provider.syncTurn(context);
        }
    }
}
