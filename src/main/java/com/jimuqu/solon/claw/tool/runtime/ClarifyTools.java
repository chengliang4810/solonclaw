package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.util.ArrayList;
import java.util.Arrays;
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
    @ToolMapping(name = "clarify", description = "Ask a clarification question with fixed options.")
    public Map<String, Object> clarify(
            @Param(name = "question", description = "要澄清的问题", required = true) String question,
            @Param(name = "options", description = "候选选项", required = true) String[] options) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tool", ToolNameConstants.CLARIFY);
        result.put("question", question);
        result.put("options", options == null ? Arrays.<String>asList() : Arrays.asList(options));
        return result;
    }

    @Override
    public Collection<FunctionTool> getTools() {
        List<FunctionTool> tools = new ArrayList<FunctionTool>();
        FunctionToolDesc tool = new FunctionToolDesc(ToolNameConstants.CLARIFY);
        tool.description("Ask a clarification question with fixed options.");
        tool.doHandle(
                args -> {
                    String question = args == null ? null : stringArg(args.get("question"));
                    String[] options = arrayArg(args == null ? null : args.get("options"));
                    return clarify(question, options);
                });
        tools.add(tool);
        return tools;
    }

    private String stringArg(Object value) {
        return value == null ? null : String.valueOf(value);
    }

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
                values.add(item == null ? null : String.valueOf(item));
            }
            return values.toArray(new String[0]);
        }
        return new String[] {String.valueOf(value)};
    }
}
