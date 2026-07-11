package com.jimuqu.solon.claw.tool.runtime;

import java.lang.reflect.Type;
import java.util.Map;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCallResultConverter;
import org.noear.solon.ai.chat.tool.ToolResult;

/** 提供Sanitized函数工具能力，供 Agent 运行时按安全策略调用。 */
public final class SanitizedFunctionTool implements FunctionTool {
    /** 记录Sanitized函数中的委托。 */
    private final FunctionTool delegate;

    /** 记录Sanitized函数中的输入结构。 */
    private String inputSchema;

    /** 记录Sanitized函数中的输出结构。 */
    private String outputSchema;

    /**
     * 创建Sanitized函数工具实例，并注入运行所需依赖。
     *
     * @param delegate 委派参数。
     */
    private SanitizedFunctionTool(FunctionTool delegate) {
        this.delegate = delegate;
    }

    /**
     * 执行wrap相关逻辑。
     *
     * @param delegate 委派参数。
     * @return 返回wrap结果。
     */
    public static FunctionTool wrap(FunctionTool delegate) {
        if (delegate == null || delegate instanceof SanitizedFunctionTool) {
            return delegate;
        }
        return new SanitizedFunctionTool(delegate);
    }

    /**
     * 向浏览器页面中的目标元素输入文本。
     *
     * @return 返回类型结果。
     */
    @Override
    public String type() {
        return delegate.type();
    }

    /**
     * 执行名称相关逻辑。
     *
     * @return 返回名称结果。
     */
    @Override
    public String name() {
        return delegate.name();
    }

    /**
     * 执行标题相关逻辑。
     *
     * @return 返回标题结果。
     */
    @Override
    public String title() {
        return delegate.title();
    }

    /**
     * 执行description相关逻辑。
     *
     * @return 返回description结果。
     */
    @Override
    public String description() {
        return delegate.description();
    }

    /**
     * 执行descriptionAndMeta相关逻辑。
     *
     * @return 返回description And Meta结果。
     */
    @Override
    public String descriptionAndMeta() {
        return delegate.descriptionAndMeta();
    }

    /**
     * 执行meta相关逻辑。
     *
     * @return 返回meta结果。
     */
    @Override
    public Map<String, Object> meta() {
        return delegate.meta();
    }

    /**
     * 执行metaPut相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    @Override
    public void metaPut(String key, Object value) {
        delegate.metaPut(key, value);
    }

    /**
     * 执行returnDirect相关逻辑。
     *
     * @return 返回return Direct结果。
     */
    @Override
    public boolean returnDirect() {
        return delegate.returnDirect();
    }

    /**
     * 执行输入结构相关逻辑。
     *
     * @return 返回输入结构结果。
     */
    @Override
    public String inputSchema() {
        if (inputSchema == null) {
            inputSchema = SolonClawToolSchemaSanitizer.sanitizeSchemaJson(delegate.inputSchema());
        }
        return inputSchema;
    }

    /**
     * 执行输出结构相关逻辑。
     *
     * @return 返回输出结构结果。
     */
    @Override
    public String outputSchema() {
        if (outputSchema == null) {
            String raw = delegate.outputSchema();
            outputSchema =
                    raw == null || raw.trim().length() == 0
                            ? raw
                            : SolonClawToolSchemaSanitizer.sanitizeSchemaJson(raw);
        }
        return outputSchema;
    }

    /**
     * 执行return类型相关逻辑。
     *
     * @return 返回return类型结果。
     */
    @Override
    public Type returnType() {
        return delegate.returnType();
    }

    /**
     * 执行结果Converter相关逻辑。
     *
     * @return 返回结果Converter结果。
     */
    @Override
    public ToolCallResultConverter resultConverter() {
        return delegate.resultConverter();
    }

    /**
     * 执行handle相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回handle结果。
     */
    @Override
    public Object handle(Map<String, Object> args) throws Throwable {
        ToolResult invalid = validateGatewayToolArgs(args);
        if (invalid != null) {
            return invalid;
        }
        return delegate.handle(args);
    }

    /**
     * 执行回调调用并返回结果。
     *
     * @param args 工具或命令参数。
     * @return 返回call结果。
     */
    @Override
    public ToolResult call(Map<String, Object> args) throws Throwable {
        ToolResult invalid = validateGatewayToolArgs(args);
        if (invalid != null) {
            return invalid;
        }
        return delegate.call(args);
    }

    /**
     * 校验渐进披露网关的内层工具参数，避免非对象参数绕过目标工具的统一边界检查。
     *
     * @param args call_tool 的顶层参数。
     * @return 参数合法或当前不是 call_tool 时返回 null，否则返回可由模型消费的错误结果。
     */
    private ToolResult validateGatewayToolArgs(Map<String, Object> args) {
        if (!"call_tool".equalsIgnoreCase(name())) {
            return null;
        }
        Object toolArgs = args == null ? null : args.get("tool_args");
        if (!(toolArgs instanceof Map)) {
            return ToolResult.error("call_tool.tool_args 必须是 JSON 对象");
        }
        return null;
    }

    /**
     * 执行unwrap相关逻辑。
     *
     * @return 返回unwrap结果。
     */
    public FunctionTool unwrap() {
        return delegate;
    }
}
