package com.jimuqu.solon.claw.proactive;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.MemoryContextBoundary;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecision;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;

/** 主动协作文案生成服务，负责把已批准候选转成安全、简短、请求许可的外发文本。 */
public class ProactiveMessageComposer {
    /** 模型润色系统提示词，禁止把主动联系写成已经执行或即将执行。 */
    private static final String LLM_POLISH_SYSTEM_PROMPT =
            "你是主动协作文案润色器。只能把候选改写成一条简短中文消息。"
                    + "必须询问用户是否需要协作，不能声称已经执行、不能承诺会执行命令或修改文件，"
                    + "不能包含密钥、内部记忆标签或旧项目关键词。";

    /** 默认许可问题，确保出站文本总是等待用户确认。 */
    private static final String DEFAULT_PERMISSION_QUESTION = "要不要我先看一下并给出处理建议？";

    /** 敏感键值后紧跟中文标点时先插入边界，避免脱敏吞掉后续许可问题。 */
    private static final Pattern SENSITIVE_VALUE_WITH_CN_PUNCT =
            Pattern.compile(
                    "(?i)\\b(api[_-]?key|apikey|token|secret|password|authorization|access[_-]?token|"
                            + "refresh[_-]?token|bearer[_-]?token|client[_-]?secret|private[_-]?key)"
                            + "(=)([^\\s,;&=\\\"#'}，。？！；]+)([，。？！；])");

    /** 可选模型润色客户端；为空时只使用确定性模板。 */
    private final LlmPolishClient llmPolishClient;

    /** 创建仅使用确定性模板的文案生成服务。 */
    public ProactiveMessageComposer() {
        this(null);
    }

    /**
     * 创建支持模型润色的文案生成服务。
     *
     * @param llmPolishClient 模型润色客户端，可为空。
     */
    public ProactiveMessageComposer(LlmPolishClient llmPolishClient) {
        this.llmPolishClient = llmPolishClient;
    }

    /**
     * 生成主动协作外发文本；非 SEND 决策不会产生消息。
     *
     * @param context 当前 tick 上下文。
     * @param decision 已落地的主动协作决策。
     * @return 返回最终可投递文本，无法生成时返回空串。
     * @throws Exception 模型润色调用失败时抛出异常。
     */
    public String compose(ProactiveTickContext context, ProactiveDecision decision) throws Exception {
        if (decision == null || !"SEND".equalsIgnoreCase(StrUtil.nullToEmpty(decision.getDecision()))) {
            return "";
        }
        ProactiveCandidateRecord candidate = decision.getCandidate();
        if (candidate == null) {
            return "";
        }
        String fallback = fallbackMessage(context, decision, candidate);
        String message = fallback;
        if (llmPolishEnabled(context) && llmPolishClient != null) {
            String polished = llmPolishClient.polish(context, decision, fallback);
            if (StrUtil.isNotBlank(polished)) {
                String safePolished = finalizeMessage(context, polished);
                if (!hasUnsafeExecutionClaim(safePolished)) {
                    message = safePolished;
                }
            }
        }
        return finalizeMessage(context, message);
    }

    /**
     * 生成确定性回退文案，包含前缀、标题、原因和许可式下一步。
     *
     * @param context 当前 tick 上下文。
     * @param decision 主动协作决策。
     * @param candidate 主动协作候选。
     * @return 返回模板文案。
     */
    private String fallbackMessage(
            ProactiveTickContext context, ProactiveDecision decision, ProactiveCandidateRecord candidate) {
        String prefix = previewPrefix(context);
        String title = visible(candidate.getTitle(), 60);
        String reason = firstNonBlank(candidate.getSummary(), candidate.getReason(), decision.getReason());
        String offer = safeOffer(candidate.getActionOffer());
        StringBuilder builder = new StringBuilder();
        builder.append(prefix).append("：");
        if (StrUtil.isNotBlank(title)) {
            builder.append("我发现「").append(title).append("」");
        } else {
            builder.append("我发现一个可能值得跟进的事项");
        }
        if (StrUtil.isNotBlank(reason)) {
            builder.append("，").append(trimTerminalPunctuation(visible(reason, 120)));
        }
        builder.append("。");
        if (StrUtil.isBlank(offer)) {
            builder.append(DEFAULT_PERMISSION_QUESTION);
        } else {
            builder.append("要不要我").append(trimTerminalPunctuation(offer)).append("？");
        }
        return builder.toString();
    }

    /**
     * 输出最终文案前做统一清理和许可语气修正。
     *
     * @param context 当前 tick 上下文。
     * @param raw 原始文案。
     * @return 返回可见安全文案。
     */
    private String finalizeMessage(ProactiveTickContext context, String raw) {
        String text = visible(raw, 500);
        text = removeUnsafeActionFragments(text);
        text = ensurePrefix(context, text);
        text = ensurePermissionQuestion(text);
        return visible(text, 500);
    }

    /**
     * 清理普通可见文本中的记忆标签、密钥和多余空白。
     *
     * @param raw 原始文本。
     * @param maxLength 最大长度。
     * @return 返回清理后的文本。
     */
    private String visible(String raw, int maxLength) {
        String text = MemoryContextBoundary.scrubVisibleText(StrUtil.nullToEmpty(raw));
        text = protectSensitiveValueBoundary(text);
        text = SecretRedactor.redact(text, maxLength);
        text = StrUtil.nullToEmpty(text).replace('\r', '\n');
        text = text.replaceAll("[\\t ]+", " ").replaceAll("\\n{2,}", "\n").trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    /**
     * 给敏感键值和中文句读之间补空格，保留后续“要不要”等许可问题。
     *
     * @param text 原始文本。
     * @return 返回带安全边界的文本。
     */
    private String protectSensitiveValueBoundary(String text) {
        return SENSITIVE_VALUE_WITH_CN_PUNCT.matcher(StrUtil.nullToEmpty(text)).replaceAll("$1$2$3 $4");
    }

    /**
     * 确保文案使用主动协作前缀，避免模型把来源上下文改写丢失。
     *
     * @param context 当前 tick 上下文。
     * @param text 原始文案。
     * @return 返回带前缀文案。
     */
    private String ensurePrefix(ProactiveTickContext context, String text) {
        String prefix = previewPrefix(context);
        if (StrUtil.startWith(text, prefix + "：") || StrUtil.startWith(text, prefix + ":")) {
            return text;
        }
        return prefix + "：" + stripKnownPrefix(text);
    }

    /**
     * 确保文本以许可式问题收束。
     *
     * @param text 原始文本。
     * @return 返回许可式文本。
     */
    private String ensurePermissionQuestion(String text) {
        if (StrUtil.containsAny(text, "要不要", "是否需要", "需不需要", "可以吗", "方便我")) {
            return text.endsWith("？") || text.endsWith("?") ? text : trimTerminalPunctuation(text) + "？";
        }
        return trimTerminalPunctuation(text) + "。" + DEFAULT_PERMISSION_QUESTION;
    }

    /**
     * 清理候选建议动作中的高风险执行表述，只保留“查看和给建议”的协作意图。
     *
     * @param offer 原始建议动作。
     * @return 返回安全建议动作。
     */
    private String safeOffer(String offer) {
        String text = visible(offer, 80);
        if (StrUtil.isBlank(text) || hasUnsafeExecutionClaim(text)) {
            return "先看一下并给出处理建议";
        }
        text = removeUnsafeActionFragments(text);
        if (StrUtil.isBlank(text)) {
            return "先看一下并给出处理建议";
        }
        return text;
    }

    /**
     * 移除常见的已执行或直接执行片段。
     *
     * @param text 原始文本。
     * @return 返回移除风险片段后的文本。
     */
    private String removeUnsafeActionFragments(String text) {
        String value = StrUtil.nullToEmpty(text);
        value = value.replace("我已经帮你", "我可以帮你");
        value = value.replace("我已经", "我可以");
        value = value.replace("已经帮你", "可以帮你");
        value = value.replace("我会执行", "我可以在你确认后协助");
        value = value.replace("让我执行", "让我在你确认后协助");
        value = value.replace("直接运行", "先评估");
        value = value.replace("直接执行", "先评估");
        return value.trim();
    }

    /**
     * 判断文本是否包含不适合作为主动外发文案的执行承诺。
     *
     * @param text 待检查文本。
     * @return 包含风险表述返回 true。
     */
    private boolean hasUnsafeExecutionClaim(String text) {
        String value = StrUtil.nullToEmpty(text);
        return StrUtil.containsAny(
                value,
                "我已经",
                "已经帮你",
                "我会执行",
                "让我执行",
                "直接运行",
                "直接执行",
                "自动修改",
                "自动提交",
                "自动推送");
    }

    /**
     * 去掉已知前缀，便于统一补回当前配置前缀。
     *
     * @param text 原始文本。
     * @return 返回去前缀文本。
     */
    private String stripKnownPrefix(String text) {
        String value = StrUtil.nullToEmpty(text).trim();
        if (value.startsWith("主动协作：")) {
            return value.substring("主动协作：".length()).trim();
        }
        if (value.startsWith("主动协作:")) {
            return value.substring("主动协作:".length()).trim();
        }
        return value;
    }

    /**
     * 读取主动协作预览前缀。
     *
     * @param context 当前 tick 上下文。
     * @return 返回前缀。
     */
    private String previewPrefix(ProactiveTickContext context) {
        AppConfig.ProactiveConfig proactive =
                context == null || context.getConfig() == null
                        ? null
                        : context.getConfig().getProactive();
        return visible(
                proactive == null
                        ? "主动协作"
                        : StrUtil.blankToDefault(proactive.getDeliveryPreviewPrefix(), "主动协作"),
                20);
    }

    /**
     * 判断是否启用模型润色。
     *
     * @param context 当前 tick 上下文。
     * @return 启用返回 true。
     */
    private boolean llmPolishEnabled(ProactiveTickContext context) {
        AppConfig.ProactiveConfig proactive =
                context == null || context.getConfig() == null
                        ? null
                        : context.getConfig().getProactive();
        return proactive != null && proactive.isLlmPolishEnabled();
    }

    /**
     * 返回第一个非空文本。
     *
     * @param values 候选文本。
     * @return 返回非空文本或空串。
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    /**
     * 去掉句尾标点，方便重新组合。
     *
     * @param text 原始文本。
     * @return 返回无句尾标点文本。
     */
    private String trimTerminalPunctuation(String text) {
        String value = StrUtil.nullToEmpty(text).trim();
        while (StrUtil.endWithAny(value, "。", "？", "?", "！", "!", "；", ";", "，", ",")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    /** 模型润色客户端，隔离服务主逻辑和具体 LLM 网关。 */
    public interface LlmPolishClient {
        /**
         * 请求模型润色主动协作文案。
         *
         * @param context 当前 tick 上下文。
         * @param decision 主动协作决策。
         * @param fallback 确定性回退文案。
         * @return 返回模型润色文本。
         * @throws Exception 模型调用失败时抛出异常。
         */
        String polish(ProactiveTickContext context, ProactiveDecision decision, String fallback)
                throws Exception;
    }

    /** 基于现有大模型网关的文案润色客户端。 */
    public static class GatewayLlmPolishClient implements LlmPolishClient {
        /** 现有大模型网关。 */
        private final LlmGateway llmGateway;

        /**
         * 创建大模型润色适配器。
         *
         * @param llmGateway 大模型网关。
         */
        public GatewayLlmPolishClient(LlmGateway llmGateway) {
            this.llmGateway = llmGateway;
        }

        @Override
        public String polish(ProactiveTickContext context, ProactiveDecision decision, String fallback)
                throws Exception {
            if (llmGateway == null) {
                return fallback;
            }
            SessionRecord synthetic = new SessionRecord();
            synthetic.setSessionId("proactive-compose-" + IdSupport.newId());
            synthetic.setNdjson("");
            LlmResult result =
                    llmGateway.chat(
                            synthetic,
                            LLM_POLISH_SYSTEM_PROMPT,
                            polishPrompt(decision, fallback),
                            Collections.emptyList());
            return MessageSupport.assistantText(
                    result == null ? null : result.getAssistantMessage());
        }

        /**
         * 构造模型润色提示词，只提供经过裁剪的候选摘要和确定性回退文本。
         *
         * @param decision 主动协作决策。
         * @param fallback 确定性文案。
         * @return 返回模型输入。
         */
        private String polishPrompt(ProactiveDecision decision, String fallback) {
            ProactiveCandidateRecord candidate = decision == null ? null : decision.getCandidate();
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("fallback", fallback);
            payload.put("messageIntent", decision == null ? "" : decision.getMessageIntent());
            payload.put("topic", candidate == null ? "" : candidate.getTopic());
            payload.put("title", candidate == null ? "" : candidate.getTitle());
            payload.put("summary", candidate == null ? "" : candidate.getSummary());
            payload.put("reason", candidate == null ? "" : candidate.getReason());
            payload.put("actionOffer", candidate == null ? "" : candidate.getActionOffer());
            return "请把下面主动协作候选润色成一条中文短消息。只输出最终消息文本。\n"
                    + ONode.serialize(payload);
        }

    }
}
