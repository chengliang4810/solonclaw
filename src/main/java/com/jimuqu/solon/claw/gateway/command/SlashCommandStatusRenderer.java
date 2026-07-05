package com.jimuqu.solon.claw.gateway.command;

import static com.jimuqu.solon.claw.gateway.command.CommandValueSupport.formatTimestamp;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import java.util.Map;

/** 渲染 slash 命令状态文本，避免命令调度服务继续维护大段展示模板。 */
final class SlashCommandStatusRenderer {
    /** 工具类不允许实例化。 */
    private SlashCommandStatusRenderer() {}

    /**
     * 渲染会话用量状态。
     *
     * @param session 当前会话记录。
     * @param runtimeSettingsService 运行时模型解析服务。
     * @return 返回用量状态文本。
     */
    static String usage(SessionRecord session, RuntimeSettingsService runtimeSettingsService) {
        RuntimeSettingsService.ResolvedModel resolved =
                runtimeSettingsService.resolveEffectiveModel(session);
        StringBuilder buffer = new StringBuilder();
        buffer.append("session=").append(session.getSessionId()).append('\n');
        buffer.append("branch=").append(session.getBranchName()).append('\n');
        buffer.append("agent=")
                .append(StrUtil.blankToDefault(session.getActiveAgentName(), "default"))
                .append('\n');
        buffer.append("effective_provider=")
                .append(StrUtil.blankToDefault(resolved.getProvider(), "default"))
                .append('\n');
        buffer.append("effective_model=")
                .append(StrUtil.blankToDefault(resolved.getModel(), "default"))
                .append('\n');
        buffer.append("last_provider=")
                .append(StrUtil.blankToDefault(session.getLastResolvedProvider(), ""))
                .append('\n');
        buffer.append("last_model=")
                .append(StrUtil.blankToDefault(session.getLastResolvedModel(), ""))
                .append('\n');
        buffer.append("last_input_tokens=").append(session.getLastInputTokens()).append('\n');
        buffer.append("last_output_tokens=").append(session.getLastOutputTokens()).append('\n');
        buffer.append("last_reasoning_tokens=")
                .append(session.getLastReasoningTokens())
                .append('\n');
        buffer.append("last_cache_read_tokens=")
                .append(session.getLastCacheReadTokens())
                .append('\n');
        buffer.append("last_cache_write_tokens=")
                .append(session.getLastCacheWriteTokens())
                .append('\n');
        buffer.append("last_total_tokens=").append(session.getLastTotalTokens()).append('\n');
        buffer.append("cumulative_input_tokens=")
                .append(session.getCumulativeInputTokens())
                .append('\n');
        buffer.append("cumulative_output_tokens=")
                .append(session.getCumulativeOutputTokens())
                .append('\n');
        buffer.append("cumulative_reasoning_tokens=")
                .append(session.getCumulativeReasoningTokens())
                .append('\n');
        buffer.append("cumulative_cache_read_tokens=")
                .append(session.getCumulativeCacheReadTokens())
                .append('\n');
        buffer.append("cumulative_cache_write_tokens=")
                .append(session.getCumulativeCacheWriteTokens())
                .append('\n');
        buffer.append("cumulative_total_tokens=")
                .append(session.getCumulativeTotalTokens())
                .append('\n');
        buffer.append("last_usage_at=")
                .append(
                        session.getLastUsageAt() > 0
                                ? formatTimestamp(session.getLastUsageAt())
                                : "");
        return buffer.toString();
    }

    /**
     * 渲染 checkpoint 状态。
     *
     * @param checkpointService checkpoint 服务。
     * @param sourceKey 渠道来源键。
     * @return 返回 checkpoint 状态文本。
     */
    static String checkpointStatus(CheckpointService checkpointService, String sourceKey)
            throws Exception {
        Map<String, Object> status = checkpointService.status(sourceKey);
        return "checkpoint_count="
                + status.get("checkpoint_count")
                + "\nmissing_dirs="
                + status.get("missing_dirs")
                + "\ntotal_size="
                + SlashCommandTextSupport.formatBytes(asLong(status.get("total_size_bytes")))
                + "\nmax_checkpoints_per_source="
                + status.get("max_checkpoints_per_source")
                + "\nmax_file_size_mb="
                + status.get("max_file_size_mb")
                + "\nlatest_created="
                + formatTimestamp(asLong(status.get("latest_created_at")));
    }

    /**
     * 渲染 checkpoint 清理结果。
     *
     * @param checkpointService checkpoint 服务。
     * @param sourceKey 渠道来源键。
     * @return 返回清理结果文本。
     */
    static String checkpointPrune(CheckpointService checkpointService, String sourceKey)
            throws Exception {
        Map<String, Object> result = checkpointService.prune(sourceKey);
        return "已清理 checkpoint store。"
                + "\ndeleted_missing="
                + result.get("deleted_missing")
                + "\ndeleted_overflow="
                + result.get("deleted_overflow")
                + "\nbytes_freed="
                + SlashCommandTextSupport.formatBytes(asLong(result.get("bytes_freed")))
                + "\nremaining="
                + result.get("checkpoint_count");
    }

    /**
     * 渲染 checkpoint 删除结果。
     *
     * @param checkpointService checkpoint 服务。
     * @param sourceKey 渠道来源键。
     * @return 返回删除结果文本。
     */
    static String checkpointClear(CheckpointService checkpointService, String sourceKey)
            throws Exception {
        Map<String, Object> result = checkpointService.clear(sourceKey);
        return "已删除当前来源的全部 checkpoint。"
                + "\ndeleted="
                + result.get("deleted")
                + "\nbytes_freed="
                + SlashCommandTextSupport.formatBytes(asLong(result.get("bytes_freed")))
                + "\nremaining="
                + result.get("checkpoint_count");
    }

    /**
     * 将对象转换为长整型。
     *
     * @param value 原始值。
     * @return 转换失败时返回 0。
     */
    private static long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }
}
