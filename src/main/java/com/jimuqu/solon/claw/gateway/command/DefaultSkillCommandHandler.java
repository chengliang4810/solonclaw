package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.ScanResult;
import com.jimuqu.solon.claw.skillhub.model.SkillBrowseResult;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.model.TapRecord;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;
import com.jimuqu.solon.claw.web.DashboardCuratorService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 处理技能、技能来源库和技能后台维护命令，减少默认命令服务的单类职责。 */
final class DefaultSkillCommandHandler {
    /** 本地技能服务，用于本地技能列表、启停、检查与重载。 */
    private final LocalSkillService localSkillService;

    /** 技能中心服务，用于浏览、搜索、安装和维护 hub 技能。 */
    private final SkillHubService skillHubService;

    /** 技能维护服务，用于 dashboard curator 命令的状态和手动运行。 */
    private final DashboardCuratorService dashboardCuratorService;

    /**
     * 创建技能命令处理器。
     *
     * @param localSkillService 本地技能服务。
     * @param skillHubService 技能中心服务。
     * @param dashboardCuratorService 技能后台维护服务。
     */
    DefaultSkillCommandHandler(
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            DashboardCuratorService dashboardCuratorService) {
        this.localSkillService = localSkillService;
        this.skillHubService = skillHubService;
        this.dashboardCuratorService = dashboardCuratorService;
    }

    /** 执行技能命令相关逻辑。 */
    GatewayReply handleSkills(GatewayMessage message, String args) throws Exception {
        SlashCommandLine.ActionTail parsed =
                SlashCommandLine.parseActionTail(args, GatewayCommandConstants.ACTION_LIST);
        String action = parsed.getAction();
        String target = parsed.getTail();

        if (GatewayCommandConstants.ACTION_LIST.equalsIgnoreCase(action)) {
            return GatewayReply.ok("技能列表：" + localSkillService.listSkillNames());
        }
        if (GatewayCommandConstants.ACTION_BROWSE.equalsIgnoreCase(action)) {
            return GatewayReply.ok(
                    formatBrowse(
                            skillHubService.browse(
                                    parseOption(target, "--source", "all"),
                                    parseIntOption(target, "--page", 1),
                                    parseIntOption(target, "--size", 20))));
        }
        if (GatewayCommandConstants.ACTION_SEARCH.equalsIgnoreCase(action)) {
            String query = stripOptions(target, "--source", "--limit");
            return GatewayReply.ok(
                    formatSearch(
                            skillHubService.search(
                                    query,
                                    parseOption(target, "--source", "all"),
                                    parseIntOption(target, "--limit", 10))));
        }
        if (GatewayCommandConstants.ACTION_INSTALL.equalsIgnoreCase(action)) {
            if (StrUtil.isBlank(target)) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_SKILLS
                                + " install <identifier> [--category <name>] [--force]");
            }
            String identifier = SlashCommandLine.firstToken(target);
            String category = parseOption(target, "--category", null);
            boolean force = hasFlag(target, "--force");
            HubInstallRecord record = skillHubService.install(identifier, category, force);
            return GatewayReply.ok(
                    "已安装技能：" + record.getInstallPath() + " (" + record.getSource() + ")");
        }
        if (GatewayCommandConstants.ACTION_CHECK.equalsIgnoreCase(action)) {
            return GatewayReply.ok(
                    formatHubInstallRecords(
                            skillHubService.check(StrUtil.blankToDefault(target, null))));
        }
        if (GatewayCommandConstants.ACTION_UPDATE.equalsIgnoreCase(action)) {
            return GatewayReply.ok(
                    formatHubInstallRecords(
                            skillHubService.update(
                                    stripOptions(target, "--force"), hasFlag(target, "--force"))));
        }
        if (GatewayCommandConstants.ACTION_AUDIT.equalsIgnoreCase(action)) {
            return GatewayReply.ok(
                    formatAudit(skillHubService.audit(StrUtil.blankToDefault(target, null))));
        }
        if (GatewayCommandConstants.ACTION_UNINSTALL.equalsIgnoreCase(action)) {
            if (StrUtil.isBlank(target)) {
                return GatewayReply.error(
                        "用法：" + GatewayCommandConstants.SLASH_SKILLS + " uninstall <name>");
            }
            return GatewayReply.ok(skillHubService.uninstall(SlashCommandLine.firstToken(target)));
        }
        if (GatewayCommandConstants.ACTION_TAP.equalsIgnoreCase(action)) {
            return GatewayReply.ok(handleTap(target));
        }
        if (GatewayCommandConstants.ACTION_ENABLE.equalsIgnoreCase(action)) {
            localSkillService.enable(message.sourceKey(), target);
            return GatewayReply.ok("已启用技能：" + target);
        }
        if (GatewayCommandConstants.ACTION_DISABLE.equalsIgnoreCase(action)) {
            localSkillService.disable(message.sourceKey(), target);
            return GatewayReply.ok("已禁用技能：" + target);
        }
        if (GatewayCommandConstants.ACTION_INSPECT.equalsIgnoreCase(action)) {
            return GatewayReply.ok(localSkillService.inspect(target));
        }
        if (GatewayCommandConstants.ACTION_RELOAD.equalsIgnoreCase(action)) {
            return handleReloadSkills();
        }

        return GatewayReply.error(
                "用法："
                        + GatewayCommandConstants.SLASH_SKILLS
                        + " [list|browse|search|install|inspect|check|update|audit|uninstall|tap|enable|disable|reload] ...");
    }

    /**
     * 执行Reload技能相关逻辑。
     *
     * @return 返回Reload技能结果。
     */
    GatewayReply handleReloadSkills() throws Exception {
        List<String> names = new ArrayList<String>(localSkillService.listSkillNames());
        Collections.sort(names);
        StringBuilder buffer = new StringBuilder();
        buffer.append("已重新加载本地技能，共 ").append(names.size()).append(" 个");
        if (!names.isEmpty()) {
            buffer.append("：").append(String.join(", ", names));
        }
        GatewayReply reply = GatewayReply.ok(buffer.toString());
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_RELOAD_SKILLS);
        reply.getRuntimeMetadata().put("skill_count", Integer.valueOf(names.size()));
        return reply;
    }

    /**
     * 执行技能维护相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回技能维护结果。
     */
    GatewayReply handleCurator(String args) throws Exception {
        if (dashboardCuratorService == null) {
            return GatewayReply.error("技能后台维护命令当前运行时未启用。");
        }
        SlashCommandLine.ActionTail parsed = SlashCommandLine.parseActionTail(args, "status");
        String action = parsed.getAction();
        String tail = parsed.getTail();
        GatewayReply reply;
        if ("status".equals(action)) {
            reply = GatewayReply.ok(formatCuratorStatus(dashboardCuratorService.status()));
        } else if (GatewayCommandConstants.ACTION_LIST.equals(action)) {
            reply =
                    GatewayReply.ok(
                            formatCuratorReports(
                                    dashboardCuratorService.list(parsePositiveInt(tail, 20))));
        } else if ("improvements".equals(action)) {
            reply =
                    GatewayReply.ok(
                            formatCuratorImprovements(
                                    dashboardCuratorService.improvements(
                                            parsePositiveInt(tail, 20))));
        } else if (GatewayCommandConstants.ACTION_RUN.equals(action)) {
            boolean force =
                    StrUtil.isBlank(tail)
                            || "force".equalsIgnoreCase(tail)
                            || "--force".equalsIgnoreCase(tail);
            reply = GatewayReply.ok(formatCuratorRun(dashboardCuratorService.run(force)));
        } else if (GatewayCommandConstants.ACTION_PAUSE.equals(action)) {
            reply =
                    GatewayReply.ok(
                            "技能后台维护已暂停。\n" + formatCuratorStatus(dashboardCuratorService.pause()));
        } else if (GatewayCommandConstants.ACTION_RESUME.equals(action)) {
            reply =
                    GatewayReply.ok(
                            "技能后台维护已恢复。\n" + formatCuratorStatus(dashboardCuratorService.resume()));
        } else {
            return GatewayReply.error(curatorUsage());
        }
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_CURATOR);
        reply.getRuntimeMetadata().put("action", action);
        return reply;
    }

    /**
     * 执行来源库相关逻辑。
     *
     * @param target target 参数。
     * @return 返回Tap结果。
     */
    private String handleTap(String target) throws Exception {
        String action = SlashCommandLine.firstToken(target);
        if (StrUtil.isBlank(action)
                || GatewayCommandConstants.ACTION_LIST.equalsIgnoreCase(action)) {
            List<TapRecord> taps = skillHubService.listTaps();
            if (taps.isEmpty()) {
                return "当前没有自定义 taps。";
            }
            StringBuilder buffer = new StringBuilder();
            for (TapRecord tap : taps) {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                buffer.append(tap.getRepo())
                        .append(" path=")
                        .append(StrUtil.blankToDefault(tap.getPath(), ""));
            }
            return buffer.toString();
        }
        if (GatewayCommandConstants.ACTION_ADD.equalsIgnoreCase(action)) {
            String[] parts = target.split("\\s+");
            if (parts.length < 2) {
                throw new IllegalStateException("用法：/skills tap add <owner/repo> [path]");
            }
            return skillHubService.addTap(parts[1], parts.length > 2 ? parts[2] : null);
        }
        if (GatewayCommandConstants.ACTION_REMOVE.equalsIgnoreCase(action)
                || GatewayCommandConstants.ACTION_DELETE.equalsIgnoreCase(action)) {
            String[] parts = target.split("\\s+");
            if (parts.length < 2) {
                throw new IllegalStateException("用法：/skills tap remove <owner/repo>");
            }
            return skillHubService.removeTap(parts[1]);
        }
        throw new IllegalStateException("Unsupported tap action: " + action);
    }

    /**
     * 格式化Browse。
     *
     * @param result 结果响应或执行结果。
     * @return 返回Browse结果。
     */
    private String formatBrowse(SkillBrowseResult result) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("skills hub browse page ")
                .append(result.getPage())
                .append("/")
                .append(
                        Math.max(
                                1,
                                (result.getTotal() + result.getPageSize() - 1)
                                        / result.getPageSize()))
                .append('\n');
        for (SkillMeta item : result.getItems()) {
            buffer.append("- ")
                    .append(item.getName())
                    .append(" [")
                    .append(item.getSource())
                    .append("/")
                    .append(item.getTrustLevel())
                    .append("]: ")
                    .append(item.getDescription())
                    .append('\n');
        }
        return buffer.toString().trim();
    }

    /**
     * 格式化搜索。
     *
     * @param result 结果响应或执行结果。
     * @return 返回搜索结果。
     */
    private String formatSearch(SkillBrowseResult result) {
        StringBuilder buffer = new StringBuilder();
        for (SkillMeta item : result.getItems()) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append("- ")
                    .append(item.getName())
                    .append(" [")
                    .append(item.getSource())
                    .append("/")
                    .append(item.getTrustLevel())
                    .append("]")
                    .append(" -> ")
                    .append(item.getIdentifier());
        }
        return buffer.length() == 0 ? "未找到匹配技能。" : buffer.toString();
    }

    /**
     * 格式化中心Install Records。
     *
     * @param records records 参数。
     * @return 返回中心Install Records结果。
     */
    private String formatHubInstallRecords(List<HubInstallRecord> records) {
        if (records == null || records.isEmpty()) {
            return "没有技能变更。";
        }
        StringBuilder buffer = new StringBuilder();
        for (HubInstallRecord record : records) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append("- ")
                    .append(record.getName())
                    .append(" [")
                    .append(record.getSource())
                    .append("/")
                    .append(record.getTrustLevel())
                    .append("]")
                    .append(" path=")
                    .append(record.getInstallPath());
            Object status = record.getMetadata().get("status");
            if (status != null) {
                buffer.append(" status=").append(status);
            }
        }
        return buffer.toString();
    }

    /**
     * 格式化审计。
     *
     * @param results results响应或执行结果。
     * @return 返回审计结果。
     */
    private String formatAudit(List<ScanResult> results) {
        if (results == null || results.isEmpty()) {
            return "没有可审计的 hub 技能。";
        }
        StringBuilder buffer = new StringBuilder();
        for (ScanResult result : results) {
            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(result.getSkillName())
                    .append(" -> ")
                    .append(result.getVerdict())
                    .append('\n');
            buffer.append(result.getSummary());
        }
        return buffer.toString();
    }

    /**
     * 格式化技能维护状态。
     *
     * @param status 状态参数。
     * @return 返回技能维护状态。
     */
    private String formatCuratorStatus(Map<String, Object> status) throws Exception {
        int reports = countList(dashboardCuratorService.list(20).get("reports"));
        int improvements = countList(dashboardCuratorService.improvements(20).get("improvements"));
        StringBuilder buffer = new StringBuilder();
        buffer.append("技能后台维护状态：\n");
        buffer.append("curator_enabled=").append(bool(status.get("enabled"))).append('\n');
        buffer.append("paused=").append(bool(status.get("paused"))).append('\n');
        buffer.append("last_run_at=")
                .append(formatNullableTimestamp(status.get("lastRunAt")))
                .append('\n');
        buffer.append("tracked_skills=").append(status.get("trackedSkills")).append('\n');
        buffer.append("reports=").append(reports).append('\n');
        buffer.append("improvements=").append(improvements).append('\n');
        buffer.append("interval_hours=").append(status.get("intervalHours")).append('\n');
        buffer.append("stale_after_days=").append(status.get("staleAfterDays")).append('\n');
        buffer.append("archive_after_days=").append(status.get("archiveAfterDays")).append('\n');
        buffer.append(curatorUsage());
        return buffer.toString();
    }

    /**
     * 格式化技能维护运行。
     *
     * @param report report 参数。
     * @return 返回技能维护运行结果。
     */
    @SuppressWarnings("unchecked")
    private String formatCuratorRun(Map<String, Object> report) {
        List<Map<String, Object>> items =
                report.get("items") instanceof List
                        ? (List<Map<String, Object>>) report.get("items")
                        : Collections.<Map<String, Object>>emptyList();
        StringBuilder buffer = new StringBuilder();
        buffer.append("技能维护运行 status=")
                .append(StrUtil.blankToDefault(String.valueOf(report.get("status")), "unknown"))
                .append(" items=")
                .append(items.size())
                .append(" state=")
                .append(StrUtil.blankToDefault(String.valueOf(report.get("stateFile")), "-"));
        appendCuratorItems(buffer, items);
        return buffer.toString();
    }

    /**
     * 格式化技能维护Reports。
     *
     * @param result 结果响应或执行结果。
     * @return 返回技能维护Reports结果。
     */
    @SuppressWarnings("unchecked")
    private String formatCuratorReports(Map<String, Object> result) {
        List<Map<String, Object>> reports =
                result.get("reports") instanceof List
                        ? (List<Map<String, Object>>) result.get("reports")
                        : Collections.<Map<String, Object>>emptyList();
        StringBuilder buffer = new StringBuilder();
        buffer.append("技能维护报告：");
        if (reports.isEmpty()) {
            buffer.append("暂无报告");
            return buffer.toString();
        }
        for (Map<String, Object> report : reports) {
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(String.valueOf(report.get("report_id")), "-"))
                    .append(" status=")
                    .append(StrUtil.blankToDefault(String.valueOf(report.get("status")), "unknown"))
                    .append(" summary=")
                    .append(StrUtil.blankToDefault(String.valueOf(report.get("summary")), "-"))
                    .append(" started=")
                    .append(formatNullableTimestamp(report.get("started_at")));
        }
        return buffer.toString();
    }

    /**
     * 格式化技能维护Improvements。
     *
     * @param result 结果响应或执行结果。
     * @return 返回技能维护Improvements结果。
     */
    @SuppressWarnings("unchecked")
    private String formatCuratorImprovements(Map<String, Object> result) {
        List<Map<String, Object>> improvements =
                result.get("improvements") instanceof List
                        ? (List<Map<String, Object>>) result.get("improvements")
                        : Collections.<Map<String, Object>>emptyList();
        StringBuilder buffer = new StringBuilder();
        buffer.append("技能改进记录：");
        if (improvements.isEmpty()) {
            buffer.append("暂无记录");
            return buffer.toString();
        }
        for (Map<String, Object> item : improvements) {
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(String.valueOf(item.get("skill_name")), "-"))
                    .append(" action=")
                    .append(StrUtil.blankToDefault(String.valueOf(item.get("action")), "-"))
                    .append(" review=")
                    .append(Boolean.TRUE.equals(item.get("needs_review")))
                    .append(" summary=")
                    .append(StrUtil.blankToDefault(String.valueOf(item.get("summary")), "-"));
        }
        return buffer.toString();
    }

    /**
     * 追加技能维护Items。
     *
     * @param buffer buffer 参数。
     * @param items items 参数。
     */
    private void appendCuratorItems(StringBuilder buffer, List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(String.valueOf(item.get("name")), "-"))
                    .append(" status=")
                    .append(StrUtil.blankToDefault(String.valueOf(item.get("status")), "-"))
                    .append(" action=")
                    .append(StrUtil.blankToDefault(String.valueOf(item.get("action")), "-"));
        }
    }

    /**
     * 执行技能维护用量相关逻辑。
     *
     * @return 返回技能维护用量结果。
     */
    private String curatorUsage() {
        return "用法："
                + GatewayCommandConstants.SLASH_CURATOR
                + " [status|list|improvements|run|pause|resume]";
    }

    /**
     * 判断是否存在Flag。
     *
     * @param raw 原始输入值。
     * @param flag flag 参数。
     * @return 如果Flag满足条件则返回 true，否则返回 false。
     */
    private boolean hasFlag(String raw, String flag) {
        return (" " + StrUtil.nullToEmpty(raw) + " ").contains(" " + flag + " ");
    }

    /**
     * 解析Option。
     *
     * @param raw 原始输入值。
     * @param option 选项参数。
     * @param defaultValue 默认值参数。
     * @return 返回解析后的Option。
     */
    private String parseOption(String raw, String option, String defaultValue) {
        String[] parts = StrUtil.nullToEmpty(raw).split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (option.equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return defaultValue;
    }

    /**
     * 解析Int Option。
     *
     * @param raw 原始输入值。
     * @param option 选项参数。
     * @param defaultValue 默认值参数。
     * @return 返回解析后的Int Option。
     */
    private int parseIntOption(String raw, String option, int defaultValue) {
        try {
            return Integer.parseInt(parseOption(raw, option, String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 解析正整数参数。
     *
     * @param raw 原始输入值。
     * @param defaultValue 默认值参数。
     * @return 返回不小于 1 的整数。
     */
    private int parsePositiveInt(String raw, int defaultValue) {
        String text = StrUtil.nullToEmpty(raw).trim();
        if (StrUtil.isBlank(text)) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(text.split("\\s+", 2)[0]));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 剥离Options。
     *
     * @param raw 原始输入值。
     * @param optionNames 选项Names参数。
     * @return 返回strip Options结果。
     */
    private String stripOptions(String raw, String... optionNames) {
        String[] parts = StrUtil.nullToEmpty(raw).split("\\s+");
        List<String> kept = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            boolean skip = false;
            for (String optionName : optionNames) {
                if (optionName.equals(parts[i])) {
                    skip = true;
                    if (i + 1 < parts.length) {
                        i++;
                    }
                    break;
                }
            }
            if (!skip && i < parts.length && StrUtil.isNotBlank(parts[i])) {
                kept.add(parts[i]);
            }
        }
        return String.join(" ", kept).trim();
    }

    /**
     * 执行次数列表相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回次数List结果。
     */
    private int countList(Object value) {
        return value instanceof List ? ((List<?>) value).size() : 0;
    }

    /**
     * 执行bool相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回bool结果。
     */
    private String bool(Object value) {
        return Boolean.TRUE.equals(value) ? "true" : "false";
    }

    /**
     * 格式化Nullable时间戳。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Nullable时间戳结果。
     */
    private String formatNullableTimestamp(Object value) {
        long millis = 0L;
        if (value instanceof Number) {
            millis = ((Number) value).longValue();
        } else {
            try {
                millis = Long.parseLong(String.valueOf(value));
            } catch (Exception e) {
                millis = 0L;
            }
        }
        return millis <= 0L ? "-" : DateUtil.formatDateTime(new java.util.Date(millis));
    }
}
