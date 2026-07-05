package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 为安全策略摘要提供样例列表，避免主策略服务继续膨胀。 */
final class SecurityPolicySummarySupport {
    /** 禁止创建无状态摘要辅助实例。 */
    private SecurityPolicySummarySupport() {}

    /**
     * 返回 URL 类工具参数键样例，用于诊断摘要展示安全策略覆盖范围。
     *
     * @return URL 参数键样例。
     */
    static List<String> toolArgsUrlKeySamples() {
        return Arrays.asList(
                "url", "uri", "href", "endpoint", "base_url", "callback_url", "proxy", "*_url");
    }

    /**
     * 返回路径类工具参数键样例，用于诊断摘要展示文件策略覆盖范围。
     *
     * @return 路径参数键样例。
     */
    static List<String> toolArgsPathKeySamples() {
        return Arrays.asList(
                "path",
                "paths",
                "file",
                "filename",
                "file_path",
                "dir",
                "cwd",
                "workdir",
                "directory",
                "output_file",
                "destination",
                "*_path");
    }

    /**
     * 返回写入意图词样例，用于说明哪些参数会触发写入类路径检查。
     *
     * @return 写入意图样例。
     */
    static List<String> toolArgsWriteIntentSamples() {
        return Arrays.asList(
                "write", "append", "delete", "remove", "move", "rename", "create", "patch");
    }

    /**
     * 返回补丁意图样例，用于诊断补丁类路径提取规则。
     *
     * @return 补丁意图样例。
     */
    static List<String> toolArgsPatchIntentSamples() {
        return Collections.singletonList("patch");
    }

    /**
     * 返回补丁文本参数键样例，用于说明哪些字段会解析补丁路径。
     *
     * @return 补丁文本键样例。
     */
    static List<String> toolArgsPatchTextKeySamples() {
        return Arrays.asList("patch", "diff", "content", "input");
    }

    /**
     * 返回写入类工具名样例，用于诊断摘要展示工具级写入判定。
     *
     * @return 写入类工具样例。
     */
    static List<String> toolArgsWriteLikeToolSamples() {
        return Arrays.asList(
                "file_write",
                "write_file",
                "file_delete",
                "delete_file",
                "remove_file",
                "file_append",
                "file_move",
                "file_rename",
                "file_mkdir",
                ToolNameConstants.PATCH);
    }

    /**
     * 截取非空样例，保持诊断输出短小且不改变原始文本。
     *
     * @param values 原始样例集合。
     * @param max 最大返回数量。
     * @return 非空样例列表。
     */
    static List<String> sample(List<String> values, int max) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        int limit = Math.max(0, max);
        for (String value : values) {
            if (result.size() >= limit) {
                break;
            }
            if (StrUtil.isNotBlank(value)) {
                result.add(value);
            }
        }
        return result;
    }

    /**
     * 截取并脱敏非空样例，避免诊断摘要泄露用户配置的敏感路径。
     *
     * @param values 原始样例集合。
     * @param max 最大返回数量。
     * @return 已脱敏样例列表。
     */
    static List<String> redactSample(List<String> values, int max) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        int limit = Math.max(0, max);
        for (String value : values) {
            if (result.size() >= limit) {
                break;
            }
            if (StrUtil.isNotBlank(value)) {
                result.add(SecretRedactor.redact(value, 400));
            }
        }
        return result;
    }
}
