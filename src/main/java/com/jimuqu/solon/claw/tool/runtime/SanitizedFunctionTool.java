package com.jimuqu.solon.claw.tool.runtime;

import java.lang.reflect.Type;
import java.util.Map;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCallResultConverter;
import org.noear.solon.ai.chat.tool.ToolResult;

/** Wraps a tool so model-facing schemas use the runtime-compatible schema subset. */
public final class SanitizedFunctionTool implements FunctionTool {
    private final FunctionTool delegate;
    private String inputSchema;
    private String outputSchema;

    private SanitizedFunctionTool(FunctionTool delegate) {
        this.delegate = delegate;
    }

    public static FunctionTool wrap(FunctionTool delegate) {
        if (delegate == null || delegate instanceof SanitizedFunctionTool) {
            return delegate;
        }
        return new SanitizedFunctionTool(delegate);
    }

    @Override
    public String type() {
        return delegate.type();
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String title() {
        return delegate.title();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public String descriptionAndMeta() {
        return delegate.descriptionAndMeta();
    }

    @Override
    public Map<String, Object> meta() {
        return delegate.meta();
    }

    @Override
    public void metaPut(String key, Object value) {
        delegate.metaPut(key, value);
    }

    @Override
    public boolean returnDirect() {
        return delegate.returnDirect();
    }

    @Override
    public String inputSchema() {
        if (inputSchema == null) {
            inputSchema = SolonClawToolSchemaSanitizer.sanitizeSchemaJson(delegate.inputSchema());
        }
        return inputSchema;
    }

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

    @Override
    public Type returnType() {
        return delegate.returnType();
    }

    @Override
    public ToolCallResultConverter resultConverter() {
        return delegate.resultConverter();
    }

    @Override
    public Object handle(Map<String, Object> args) throws Throwable {
        return delegate.handle(args);
    }

    @Override
    public ToolResult call(Map<String, Object> args) throws Throwable {
        return delegate.call(args);
    }

    public FunctionTool unwrap() {
        return delegate;
    }
}
