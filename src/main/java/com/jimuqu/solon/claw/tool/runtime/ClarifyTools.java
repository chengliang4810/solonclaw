package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.annotation.Param;

/** 澄清工具。 */
public class ClarifyTools implements ToolProvider {
    /** 单次最多展示四个预定义选项，第五项由终端 UI 作为“其他”输入入口补齐。 */
    private static final int MAX_CHOICES = 4;

    /** clarify 工具的精确输入 schema，choices 可省略且不接受其他候选字段名。 */
    private static final String CLARIFY_INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"question\":{\"type\":\"string\",\"description\":\"The question itself. Put selectable answers only in choices.\"},"
                    + "\"choices\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":4,"
                    + "\"description\":\"Up to 4 predefined answers. Omit for an open-ended question.\"}},"
                    + "\"required\":[\"question\"]}";

    /** 请求协调器，用于把工具调用桥接到当前终端 UI 会话并等待回答。 */
    private final ClarifyRequestCoordinator coordinator;

    /** 创建使用运行时共享协调器的 clarify 工具。 */
    public ClarifyTools() {
        this(ClarifyRequestCoordinator.shared());
    }

    /** 创建可注入协调器的 clarify 工具，便于隔离专项测试。 */
    public ClarifyTools(ClarifyRequestCoordinator coordinator) {
        this.coordinator = coordinator == null ? ClarifyRequestCoordinator.shared() : coordinator;
    }

    /**
     * 向当前交互式会话发出澄清问题并等待用户回答。
     *
     * @param question 必填问题文本。
     * @param choices 最多四项候选；省略时为开放问题。
     * @return 返回问题、候选项和用户回答，交互上下文不可用时返回 error。
     */
    @ToolMapping(
            name = "clarify",
            description =
                    "Ask the user a question when clarification, feedback, or a decision is required. Provide up to 4 choices, or omit choices for an open-ended answer.")
    public Map<String, Object> clarify(
            @Param(name = "question", description = "要澄清的问题", required = true) String question,
            @Param(name = "choices", description = "最多四项候选；开放问题可省略", required = false)
                    String[] choices) {
        List<String> normalizedChoices = normalizeChoices(choices);
        String normalizedQuestion = StrUtil.nullToEmpty(question).trim();
        if (StrUtil.isBlank(normalizedQuestion)) {
            return error("Question text is required.");
        }

        AgentRunContext context = AgentRunContext.current();
        if (context == null || StrUtil.isBlank(context.getSessionId())) {
            return error("Clarify tool is not available in this execution context.");
        }

        String answer;
        try {
            answer =
                    coordinator.request(
                            context.getSessionId(), normalizedQuestion, normalizedChoices);
        } catch (Exception e) {
            return error("Failed to get user input: " + safeMessage(e));
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("question", normalizedQuestion);
        result.put("choices_offered", normalizedChoices.isEmpty() ? null : normalizedChoices);
        result.put("user_response", StrUtil.nullToEmpty(answer).trim());
        return result;
    }

    /**
     * 读取工具。
     *
     * @return 返回读取到的工具。
     */
    @Override
    public Collection<FunctionTool> getTools() {
        List<FunctionTool> tools = new ArrayList<FunctionTool>();
        FunctionToolDesc tool = new FunctionToolDesc(ToolNameConstants.CLARIFY);
        tool.description(
                "Ask the user a question when clarification, feedback, or a decision is required. Provide up to 4 choices, or omit choices for an open-ended answer.");
        tool.inputSchema(CLARIFY_INPUT_SCHEMA);
        tool.doHandle(
                args -> {
                    String question = args == null ? null : stringArg(args.get("question"));
                    Object rawChoices = args == null ? null : args.get("choices");
                    if (rawChoices != null
                            && !(rawChoices instanceof Collection)
                            && !(rawChoices instanceof String[])) {
                        return error("choices must be a list of strings.");
                    }
                    String[] choices = arrayArg(rawChoices);
                    return clarify(question, choices);
                });
        tools.add(tool);
        return tools;
    }

    /**
     * 执行stringArg相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回string Arg结果。
     */
    private String stringArg(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 执行arrayArg相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回array Arg结果。
     */
    private String[] arrayArg(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String[]) {
            return (String[]) value;
        }
        if (value instanceof Collection) {
            Collection<?> items = (Collection<?>) value;
            List<String> values = new ArrayList<String>(items.size());
            for (Object item : items) {
                String text = flattenChoice(item);
                if (StrUtil.isNotBlank(text)) {
                    values.add(text);
                }
            }
            return values.toArray(new String[0]);
        }
        String text = flattenChoice(value);
        return StrUtil.isBlank(text) ? null : new String[] {text};
    }

    /** 规范化候选项、删除空项并截断到四项。 */
    private List<String> normalizeChoices(String[] choices) {
        List<String> normalized = new ArrayList<String>();
        if (choices == null) {
            return normalized;
        }
        for (String choice : choices) {
            String value = StrUtil.nullToEmpty(choice).trim();
            if (StrUtil.isNotBlank(value)) {
                normalized.add(value);
            }
            if (normalized.size() >= MAX_CHOICES) {
                break;
            }
        }
        return normalized;
    }

    /** 将模型偶发产生的对象或嵌套数组选项压平为用户可见文本。 */
    private String flattenChoice(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (String key : new String[] {"label", "description", "text", "title"}) {
                Object candidate = map.get(key);
                if (candidate instanceof String && StrUtil.isNotBlank((String) candidate)) {
                    return ((String) candidate).trim();
                }
            }
            return "";
        }
        if (value instanceof Collection) {
            StringBuilder result = new StringBuilder();
            for (Object item : (Collection<?>) value) {
                String text = flattenChoice(item);
                if (StrUtil.isBlank(text)) {
                    continue;
                }
                if (result.length() > 0) {
                    result.append(' ');
                }
                result.append(text);
            }
            return result.toString().trim();
        }
        return String.valueOf(value).trim();
    }

    /** 构造工具约定的错误结果。 */
    private Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("error", StrUtil.blankToDefault(message, "clarify request failed"));
        return result;
    }

    /** 构造不包含堆栈和上下文正文的异常摘要。 */
    private String safeMessage(Throwable error) {
        if (error == null) {
            return "request failed";
        }
        return StrUtil.blankToDefault(error.getMessage(), error.getClass().getSimpleName());
    }
}
