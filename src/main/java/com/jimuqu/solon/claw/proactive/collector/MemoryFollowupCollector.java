package com.jimuqu.solon.claw.proactive.collector;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.MemorySnapshot;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.proactive.ProactiveObservationCollector;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 记忆跟进观测采集器，用于从长期记忆中识别需要主动跟进的知识线索。 */
public class MemoryFollowupCollector implements ProactiveObservationCollector {
    /** 采集器稳定名称，用于观测来源、排障和候选生成识别。 */
    public static final String COLLECTOR_NAME = "memory_followup";

    /** 观测载荷中的主动协作类型，后续候选生成会按该类型归类为知识跟进。 */
    private static final String OBSERVATION_TYPE = "knowledge_followup";

    /** 单次 tick 最多输出的记忆跟进观测数量，避免长期记忆过多时造成触达候选爆炸。 */
    private static final int MAX_OBSERVATIONS = 8;

    /** 单条记忆证据最大保留长度，避免把长段落完整写入观测载荷。 */
    private static final int LINE_MAX_LENGTH = 420;

    /** 摘要最大长度，保持后续诊断和候选生成可读。 */
    private static final int SUMMARY_MAX_LENGTH = 240;

    /** 表示该记忆和项目、仓库或具体工作对象相关的关键词。 */
    private static final List<String> WORK_SUBJECT_KEYWORDS =
            Arrays.asList(
                    "project",
                    "repo",
                    "repository",
                    "github",
                    "gitee",
                    "gitlab",
                    "branch",
                    "release",
                    "dependency",
                    "workspace",
                    "项目",
                    "仓库",
                    "代码库",
                    "分支",
                    "发布",
                    "依赖",
                    "工作台",
                    "插件",
                    "功能",
                    "模块",
                    "需求",
                    "任务");

    /** 表示用户期望后续关注、主动提醒或持续协作的关键词。 */
    private static final List<String> FOLLOWUP_KEYWORDS =
            Arrays.asList(
                    "follow up",
                    "follow-up",
                    "watch",
                    "monitor",
                    "remind",
                    "notify",
                    "check in",
                    "keep tracking",
                    "关注",
                    "持续关注",
                    "跟进",
                    "主动提醒",
                    "主动询问",
                    "提醒",
                    "通知",
                    "留意",
                    "关注更新",
                    "下次主动",
                    "继续跟进");

    /** 表示长期职责、周期性工作或固定责任的关键词。 */
    private static final List<String> RESPONSIBILITY_KEYWORDS =
            Arrays.asList(
                    "weekly",
                    "daily",
                    "recurring",
                    "responsible",
                    "owner",
                    "每周",
                    "每日",
                    "定期",
                    "周期",
                    "负责",
                    "职责",
                    "值守",
                    "例行");

    /** 表示具体工作动作或工作产物的关键词，用于识别没有项目名但仍可跟进的协作线索。 */
    private static final List<String> WORK_ACTION_KEYWORDS =
            Arrays.asList(
                    "continue",
                    "verify",
                    "review",
                    "deploy",
                    "merge",
                    "ship",
                    "report",
                    "继续处理",
                    "处理",
                    "协作",
                    "完成",
                    "整理",
                    "排查",
                    "验证",
                    "修复",
                    "实现",
                    "评审",
                    "周报",
                    "计划",
                    "汇报",
                    "上线",
                    "部署",
                    "合并",
                    "提交",
                    "推送",
                    "复盘",
                    "对齐");

    /** 表示用户明确希望机器人主动询问或后续联系的短语，不要求同时出现具体项目名。 */
    private static final List<String> EXPLICIT_FOLLOWUP_KEYWORDS =
            Arrays.asList(
                    "希望你",
                    "主动询问",
                    "是否继续",
                    "下次主动",
                    "隔天",
                    "明天",
                    "后续",
                    "定期问",
                    "再问",
                    "check in",
                    "ask me");

    /** 表示普通表达偏好，不应单独成为主动触达理由的关键词。 */
    private static final List<String> GENERIC_PREFERENCE_KEYWORDS =
            Arrays.asList(
                    "喜欢",
                    "偏好",
                    "简洁",
                    "中文",
                    "先给结论",
                    "语气",
                    "格式",
                    "prefer",
                    "preference",
                    "concise",
                    "language",
                    "tone");

    /** 表示语言、格式、通知样式等表达风格的关键词，命中时通常不是工作跟进理由。 */
    private static final List<String> STYLE_PREFERENCE_KEYWORDS =
            Arrays.asList(
                    "中文",
                    "英文",
                    "简洁",
                    "格式",
                    "语气",
                    "措辞",
                    "回复",
                    "回答",
                    "说明",
                    "通知使用",
                    "通知方式",
                    "language",
                    "tone",
                    "format",
                    "concise",
                    "reply");

    /** 长期记忆服务，用于读取用户、项目和当日记忆快照。 */
    private final MemoryService memoryService;

    /**
     * 创建记忆跟进采集器。
     *
     * @param memoryService 长期记忆服务，允许为空；为空时采集器不产出观测。
     */
    public MemoryFollowupCollector(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 返回记忆跟进采集器的稳定名称。 */
    @Override
    public String name() {
        return COLLECTOR_NAME;
    }

    /** 仅在主动协作总开关启用时运行记忆跟进采集。 */
    @Override
    public boolean enabled(AppConfig config) {
        AppConfig.ProactiveConfig proactive = config == null ? null : config.getProactive();
        return proactive != null && proactive.isEnabled();
    }

    /** 采集长期记忆中显式要求后续关注的知识线索。 */
    @Override
    public List<ProactiveObservation> collect(ProactiveTickContext context) throws Exception {
        List<ProactiveObservation> observations = new ArrayList<ProactiveObservation>();
        if (context == null || !enabled(context.getConfig()) || memoryService == null) {
            return observations;
        }
        MemorySnapshot snapshot = memoryService.loadSnapshot();
        if (snapshot == null) {
            return observations;
        }
        collectSection(
                "memory",
                "MEMORY.md",
                snapshot.getMemoryText(),
                observations);
        collectSection(
                "user",
                "USER.md",
                snapshot.getUserText(),
                observations);
        collectSection(
                "today",
                "TODAY_MEMORY",
                snapshot.getDailyMemoryText(),
                observations);
        return observations;
    }

    /**
     * 从一个记忆分区中逐行提取显式跟进线索。
     *
     * @param section 记忆分区名称。
     * @param sourceRef 面向载荷的来源引用。
     * @param text 分区原始文本。
     * @param observations 当前 tick 的观测列表。
     */
    private void collectSection(
            String section, String sourceRef, String text, List<ProactiveObservation> observations) {
        if (StrUtil.isBlank(text) || observations.size() >= MAX_OBSERVATIONS) {
            return;
        }
        int lineNumber = 0;
        for (String rawLine : text.split("\\R")) {
            lineNumber++;
            if (observations.size() >= MAX_OBSERVATIONS) {
                return;
            }
            String line = normalizeMemoryLine(rawLine);
            if (StrUtil.isBlank(line) || line.startsWith("#")) {
                continue;
            }
            MemoryHint hint = inspectLine(section, sourceRef, line, lineNumber);
            if (hint != null) {
                observations.add(buildObservation(hint));
            }
        }
    }

    /**
     * 规范化记忆行，移除常见列表符号，保持后续关键词判断稳定。
     *
     * @param rawLine 原始记忆行。
     * @return 返回去除列表前缀后的行文本。
     */
    private String normalizeMemoryLine(String rawLine) {
        String line = StrUtil.nullToEmpty(rawLine).trim();
        while (line.startsWith("- ") || line.startsWith("* ")) {
            line = line.substring(2).trim();
        }
        return line;
    }

    /**
     * 判断单条记忆是否具备主动知识跟进价值。
     *
     * @param section 记忆分区名称。
     * @param sourceRef 来源引用。
     * @param line 规范化后的记忆行。
     * @param lineNumber 行号，用于构造稳定来源键。
     * @return 命中跟进语义时返回线索；否则返回 null。
     */
    private MemoryHint inspectLine(String section, String sourceRef, String line, int lineNumber) {
        List<String> labels = new ArrayList<String>();
        boolean subject = containsAny(line, WORK_SUBJECT_KEYWORDS) || containsPathOrRepoRef(line);
        boolean followup = containsAny(line, FOLLOWUP_KEYWORDS);
        boolean responsibility = containsAny(line, RESPONSIBILITY_KEYWORDS);
        boolean workAction = containsAny(line, WORK_ACTION_KEYWORDS);
        boolean explicitFollowup = followup && containsAny(line, EXPLICIT_FOLLOWUP_KEYWORDS);
        boolean workResponsibility = responsibility && workAction;
        if (containsPathOrRepoRef(line)) {
            labels.add("repo_watch");
        } else if (subject) {
            labels.add("ongoing_work");
        }
        if (followup) {
            labels.add("preferred_followup");
        }
        if (responsibility) {
            labels.add("recurring_responsibility");
        }
        if (explicitFollowup && !labels.contains("explicit_followup")) {
            labels.add("explicit_followup");
        }
        if (workResponsibility && !labels.contains("work_responsibility")) {
            labels.add("work_responsibility");
        }

        if (labels.isEmpty()
                || !isExplicitWorkFollowup(
                        subject, followup, responsibility, workAction, explicitFollowup)
                || isGenericPreferenceOnly(line, subject, followup, responsibility, workAction)) {
            return null;
        }

        MemoryHint hint = new MemoryHint();
        hint.section = section;
        hint.sourceRef = sourceRef;
        hint.lineNumber = lineNumber;
        hint.line = line;
        hint.labels = labels;
        hint.confidenceHint = subject && followup ? "high" : "medium";
        return hint;
    }

    /**
     * 判断是否具备显式工作跟进语义，既支持项目/仓库关注，也支持没有项目名的主动询问和周期职责。
     *
     * @param subject 是否命中工作对象。
     * @param followup 是否命中跟进意图。
     * @param responsibility 是否命中职责语义。
     * @param workAction 是否命中具体工作动作或工作产物。
     * @param explicitFollowup 是否命中用户明确要求主动询问的短语。
     * @return 满足显式工作跟进组合时返回 true。
     */
    private boolean isExplicitWorkFollowup(
            boolean subject,
            boolean followup,
            boolean responsibility,
            boolean workAction,
            boolean explicitFollowup) {
        return (subject && (followup || responsibility))
                || (explicitFollowup && (workAction || followup))
                || (responsibility && workAction);
    }

    /**
     * 过滤表达风格偏好，避免“项目通知使用中文”这类偏好被当作主动工作跟进。
     *
     * @param line 记忆行。
     * @param subject 是否命中工作对象。
     * @param followup 是否命中跟进语义。
     * @param responsibility 是否命中职责语义。
     * @param workAction 是否命中具体工作动作或工作产物。
     * @return 仅为普通偏好时返回 true。
     */
    private boolean isGenericPreferenceOnly(
            String line,
            boolean subject,
            boolean followup,
            boolean responsibility,
            boolean workAction) {
        boolean genericPreference = containsAny(line, GENERIC_PREFERENCE_KEYWORDS);
        if (!genericPreference) {
            return false;
        }
        boolean stylePreference = containsAny(line, STYLE_PREFERENCE_KEYWORDS);
        if (stylePreference && !workAction && !responsibility) {
            return true;
        }
        return !subject && !followup && !responsibility && !workAction;
    }

    /**
     * 判断文本是否包含路径、仓库 URL 或常见 git 仓库标识。
     *
     * @param line 记忆行。
     * @return 包含仓库或路径引用时返回 true。
     */
    private boolean containsPathOrRepoRef(String line) {
        String value = StrUtil.nullToEmpty(line);
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("github.com/")
                || lower.contains("gitee.com/")
                || lower.contains("gitlab.com/")
                || lower.contains(".git")
                || lower.contains("/code-projects/")
                || lower.contains("/code-repositories/")
                || lower.contains("\\code-projects\\")
                || lower.contains("\\code-repositories\\");
    }

    /**
     * 构造知识跟进观测，使用 evidence 子载荷保留证据并规避顶层 payload 裁剪。
     *
     * @param hint 记忆线索。
     * @return 返回主动协作观测。
     */
    private ProactiveObservation buildObservation(MemoryHint hint) {
        ProactiveObservation observation = new ProactiveObservation();
        observation.setCollector(COLLECTOR_NAME);
        observation.setSourceKey(sourceKey(hint));
        observation.setStatus("COLLECTED");
        observation.setSummary(summary(hint));
        observation.setPayload(payload(hint));
        return observation;
    }

    /**
     * 构造结构化观测载荷。
     *
     * @param hint 记忆线索。
     * @return 返回适合后续候选生成的载荷。
     */
    private Map<String, Object> payload(MemoryHint hint) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", OBSERVATION_TYPE);
        payload.put("section", hint.section);
        payload.put("sourceRef", hint.sourceRef);
        payload.put("priority", "low");
        payload.put("confidenceHint", hint.confidenceHint);
        payload.put("topic", safe(topic(hint.line), 160));
        payload.put("reasonLabels", new ArrayList<String>(hint.labels));

        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("lines", Arrays.asList(safe(hint.line, LINE_MAX_LENGTH)));
        evidence.put("lineNumber", Integer.valueOf(hint.lineNumber));
        payload.put("evidence", evidence);
        return payload;
    }

    /**
     * 生成摘要文本，保留短主题和命中原因。
     *
     * @param hint 记忆线索。
     * @return 返回已脱敏摘要。
     */
    private String summary(MemoryHint hint) {
        return safe(
                "knowledge_followup: "
                        + topic(hint.line)
                        + "，原因 "
                        + String.join(",", hint.labels),
                SUMMARY_MAX_LENGTH);
    }

    /**
     * 从记忆行中截取短主题。
     *
     * @param line 记忆行。
     * @return 返回短主题文本。
     */
    private String topic(String line) {
        return StrUtil.maxLength(StrUtil.nullToEmpty(line), 80);
    }

    /**
     * 构造稳定来源键，便于后续按记忆来源去重。
     *
     * @param hint 记忆线索。
     * @return 返回稳定来源键。
     */
    private String sourceKey(MemoryHint hint) {
        return COLLECTOR_NAME + ":" + hint.section + ":" + hint.lineNumber;
    }

    /**
     * 判断文本是否包含任一关键词，英文统一小写匹配，中文保持原文匹配。
     *
     * @param text 候选文本。
     * @param keywords 关键词列表。
     * @return 命中任一关键词返回 true。
     */
    private boolean containsAny(String text, List<String> keywords) {
        String value = StrUtil.nullToEmpty(text).toLowerCase(Locale.ROOT);
        if (StrUtil.isBlank(value) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (StrUtil.isNotBlank(keyword)
                    && value.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对载荷和摘要文本做统一脱敏与长度限制。
     *
     * @param value 原始文本。
     * @param maxLength 最大保留长度。
     * @return 返回安全文本。
     */
    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), maxLength);
    }

    /** 单条记忆命中的主动跟进线索。 */
    private static final class MemoryHint {
        /** 记忆分区名称，例如 memory、user 或 today。 */
        private String section;

        /** 来源引用，例如 MEMORY.md、USER.md 或 TODAY_MEMORY。 */
        private String sourceRef;

        /** 记忆行号，用于构造稳定来源键。 */
        private int lineNumber;

        /** 原始记忆行文本。 */
        private String line;

        /** 命中的原因标签。 */
        private List<String> labels = new ArrayList<String>();

        /** 置信度提示，供候选生成阶段参考。 */
        private String confidenceHint;
    }
}
