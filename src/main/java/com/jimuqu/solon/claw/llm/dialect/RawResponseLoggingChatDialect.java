package com.jimuqu.solon.claw.llm.dialect;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.dialect.ChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 承载原始响应日志聊天协议方言相关状态和辅助逻辑。 */
public class RawResponseLoggingChatDialect implements ChatDialect {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(RawResponseLoggingChatDialect.class);

    /** 记录原始响应日志聊天协议方言中的委托。 */
    private final ChatDialect delegate;

    /** 记录原始响应日志聊天协议方言中的协议方言名称。 */
    private final String dialectName;

    /** 是否启用解析Responses推理。 */
    private final boolean parseResponsesReasoning;

    /**
     * 创建原始响应日志Chat协议方言实例，并注入运行所需依赖。
     *
     * @param delegate 委派参数。
     * @param dialectName dialect名称参数。
     * @param parseResponsesReasoning 解析Responses推理响应或执行结果。
     */
    public RawResponseLoggingChatDialect(
            ChatDialect delegate, String dialectName, boolean parseResponsesReasoning) {
        this.delegate = delegate;
        this.dialectName = dialectName;
        this.parseResponsesReasoning = parseResponsesReasoning;
    }

    /**
     * 判断是否默认。
     *
     * @return 如果默认满足条件则返回 true，否则返回 false。
     */
    @Override
    public boolean isDefault() {
        return delegate.isDefault();
    }

    /**
     * 执行matched相关逻辑。
     *
     * @param config 当前模块使用的配置对象。
     * @return 返回matched结果。
     */
    @Override
    public boolean matched(ChatConfig config) {
        return delegate.matched(config);
    }

    /**
     * 创建HTTP Utils。
     *
     * @param config 当前模块使用的配置对象。
     * @param isStream is流参数。
     * @return 返回创建好的HTTP Utils。
     */
    @Override
    public HttpUtils createHttpUtils(ChatConfig config, boolean isStream) {
        return delegate.createHttpUtils(config, isStream);
    }

    /**
     * 执行prepare输出结构指令相关逻辑。
     *
     * @param outputSchema 输出Schema参数。
     * @param instructionBuilder 指令构建器参数。
     */
    @Override
    public void prepareOutputSchemaInstruction(
            String outputSchema, StringBuilder instructionBuilder) {
        delegate.prepareOutputSchemaInstruction(outputSchema, instructionBuilder);
    }

    /**
     * 执行prepare输出格式Options相关逻辑。
     *
     * @param options options 参数。
     */
    @Override
    public void prepareOutputFormatOptions(ChatOptions options) {
        delegate.prepareOutputFormatOptions(options);
    }

    /**
     * 构建请求JSON。
     *
     * @param config 当前模块使用的配置对象。
     * @param options options 参数。
     * @param messages messages 参数。
     * @param isStream is流参数。
     * @return 返回创建好的请求JSON。
     */
    @Override
    public ONode buildRequestJson(
            ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return delegate.buildRequestJson(config, options, messages, isStream);
    }

    /**
     * 构建Assistant工具Call消息Node。
     *
     * @param resp resp 参数。
     * @param toolCallBuilders 工具CallBuilders参数。
     * @return 返回创建好的Assistant工具Call消息Node。
     */
    @Override
    public ONode buildAssistantToolCallMessageNode(
            ChatResponseDefault resp, Map<String, ToolCallBuilder> toolCallBuilders) {
        return delegate.buildAssistantToolCallMessageNode(resp, toolCallBuilders);
    }

    /**
     * 构建Assistant消息根据工具Messages。
     *
     * @param toolCallMessage 工具Call消息参数。
     * @param toolMessages 工具Messages参数。
     * @return 返回创建好的Assistant消息根据工具Messages。
     */
    @Override
    public AssistantMessage buildAssistantMessageByToolMessages(
            AssistantMessage toolCallMessage, List<ToolMessage> toolMessages) {
        return delegate.buildAssistantMessageByToolMessages(toolCallMessage, toolMessages);
    }

    /**
     * 解析响应JSON。
     *
     * @param config 当前模块使用的配置对象。
     * @param resp resp 参数。
     * @param respJson respJSON参数。
     * @return 返回解析后的响应JSON。
     */
    @Override
    public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String respJson) {
        try {
            boolean parsed = false;
            if (parseResponsesReasoning) {
                parsed = parseReasoningStreamDelta(resp, respJson);
            }
            return delegate.parseResponseJson(config, resp, respJson) || parsed;
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to parse llm raw response: dialect={}, provider={}, model={}, apiUrl={}, stream={}, bodyLength={}, bodyHexHead={}, body={}, error={}",
                    dialectName,
                    StrUtil.blankToDefault(config.getProvider(), ""),
                    StrUtil.blankToDefault(config.getModel(), ""),
                    SecretRedactor.maskUrl(StrUtil.blankToDefault(config.getApiUrl(), "")),
                    resp != null && resp.isStream(),
                    respJson == null ? 0 : respJson.length(),
                    RawResponseLogSupport.hexHead(respJson),
                    RawResponseLogSupport.preview(respJson),
                    ErrorTextSupport.safeError(e));
            throw e;
        }
    }

    /**
     * 解析Assistant消息。
     *
     * @param resp resp 参数。
     * @param oMessage o消息参数。
     * @return 返回解析后的Assistant消息。
     */
    @Override
    public List<AssistantMessage> parseAssistantMessage(ChatResponseDefault resp, ONode oMessage) {
        return delegate.parseAssistantMessage(resp, oMessage);
    }

    /**
     * 读取委托。
     *
     * @return 返回读取到的委托。
     */
    public ChatDialect getDelegate() {
        return delegate;
    }

    /**
     * 读取协议方言名称。
     *
     * @return 返回读取到的协议方言名称。
     */
    public String getDialectName() {
        return dialectName;
    }

    /**
     * 解析Reasoning Stream Delta。
     *
     * @param resp resp 参数。
     * @param json JSON参数。
     * @return 返回解析后的Reasoning Stream Delta。
     */
    private boolean parseReasoningStreamDelta(ChatResponseDefault resp, String json) {
        if (resp == null || !resp.isStream() || StrUtil.isBlank(json)) {
            return false;
        }
        boolean parsed = false;
        String[] lines = json.split("\n");
        for (String line : lines) {
            String candidate = StrUtil.trim(line);
            if (StrUtil.isBlank(candidate)) {
                continue;
            }
            if (candidate.startsWith("data:")) {
                candidate = StrUtil.trim(candidate.substring(5));
            } else if (candidate.startsWith("event:")) {
                continue;
            }
            if (StrUtil.isBlank(candidate) || "[DONE]".equals(candidate)) {
                continue;
            }
            try {
                ONode node = ONode.ofJson(candidate);
                String type = node.get("type").getString();
                if (StrUtil.isBlank(type) || !type.toLowerCase().contains("reasoning")) {
                    continue;
                }
                String delta =
                        firstText(
                                node.get("delta").getString(),
                                node.get("text").getString(),
                                node.get("summary_text").getString());
                ONode item = node.getOrNull("item");
                if (StrUtil.isBlank(delta) && item != null) {
                    delta =
                            firstText(
                                    item.get("text").getString(),
                                    item.get("summary_text").getString());
                }
                if (StrUtil.isBlank(delta)) {
                    continue;
                }
                resp.reasoningBuilder.append(delta);
                resp.addChoice(
                        new ChatChoice(0, new Date(), null, new AssistantMessage(delta, true)));
                parsed = true;
            } catch (Exception e) {
                log.debug(
                        "Responses 推理流事件解析失败，交由委托方言处理 dialect={}, eventLength={}, error={}",
                        dialectName,
                        candidate.length(),
                        e.getClass().getSimpleName());
            }
        }
        return parsed;
    }

    /**
     * 执行first文本相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回first Text结果。
     */
    private String firstText(String... values) {
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
}
