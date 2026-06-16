package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimePolicy;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SkillDescriptor;
import com.jimuqu.solon.claw.core.model.SkillView;
import com.jimuqu.solon.claw.core.service.SkillCatalogService;
import com.jimuqu.solon.claw.core.service.SkillImportService;
import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.SkillSetupState;
import com.jimuqu.solon.claw.skillhub.support.SkillFrontmatterSupport;
import com.jimuqu.solon.claw.skillhub.support.SkillHubStateStore;
import com.jimuqu.solon.claw.skillhub.support.SkillIgnoreSupport;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.SkillConstants;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;

/** 提供本地技能相关业务能力，封装调用方不需要感知的运行细节。 */
public class LocalSkillService implements SkillCatalogService {
    /** 技能名允许字符。 */
    private static final String VALID_NAME_PATTERN = "^[a-z0-9][a-z0-9._-]*$";

    /** SKILL.md 中允许安全替换的模板变量。 */
    private static final Pattern SKILL_TEMPLATE_VARIABLE_PATTERN =
            Pattern.compile("\\$\\{(SOLONCLAW_SKILL_DIR|SOLONCLAW_SESSION_ID)\\}");

    /** inline shell 片段，形如 !`date +%s`。 */
    private static final Pattern INLINE_SHELL_PATTERN = Pattern.compile("!`([^`\\n]+)`");

    /** 内联终端最大输出的统一常量值。 */
    private static final int INLINE_SHELL_MAX_OUTPUT = 4000;

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 技能可见性偏好存储。 */
    private final SqlitePreferenceStore preferenceStore;

    /** 自动导入服务。 */
    private final SkillImportService skillImportService;

    /** Hub 状态存储。 */
    private final SkillHubStateStore hubStateStore;

    /** 技能目录解析器。 */
    private final SkillDirectoryResolver skillDirectoryResolver;

    /** 构造本地技能服务。 */
    public LocalSkillService(AppConfig appConfig, SqlitePreferenceStore preferenceStore) {
        this(appConfig, preferenceStore, null, null);
    }

    /**
     * 创建本地技能服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param skillImportService 技能Import服务依赖。
     * @param hubStateStore 技能中心状态存储依赖。
     */
    public LocalSkillService(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SkillImportService skillImportService,
            SkillHubStateStore hubStateStore) {
        this.appConfig = appConfig;
        this.preferenceStore = preferenceStore;
        this.skillImportService = skillImportService;
        this.skillDirectoryResolver = new SkillDirectoryResolver(appConfig);
        this.hubStateStore =
                hubStateStore == null
                        ? new SkillHubStateStore(
                                FileUtil.file(appConfig.getRuntime().getSkillsDir()))
                        : hubStateStore;
        FileUtil.mkdir(appConfig.getRuntime().getSkillsDir());
    }

    /**
     * 列出技能Names。
     *
     * @return 返回技能Names列表。
     */
    public List<String> listSkillNames() {
        try {
            processPendingImportsQuietly();
            List<SkillDescriptor> skills = listSkills(null);
            List<String> names = new ArrayList<String>();
            for (SkillDescriptor descriptor : skills) {
                names.add(descriptor.canonicalName());
            }
            return names;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 执行inspect相关逻辑。
     *
     * @param skillName 技能名称参数。
     * @return 返回inspect结果。
     */
    public String inspect(String skillName) {
        try {
            processPendingImportsQuietly();
            SkillView skillView = viewSkill(skillName, null);
            return skillView.getContent();
        } catch (Exception e) {
            return "Skill not found: " + skillName;
        }
    }

    /** 将技能显式设为可见。 */
    public void enable(String sourceKey, String skillName) throws Exception {
        setVisible(sourceKey, skillName, true);
    }

    /** 将技能显式设为隐藏。 */
    public void disable(String sourceKey, String skillName) throws Exception {
        setVisible(sourceKey, skillName, false);
    }

    /** 当前实现中技能默认可见，只有显式 disable 才隐藏。 */
    public boolean isEnabled(String sourceKey, String skillName) throws Exception {
        return isVisible(sourceKey, skillName);
    }

    /**
     * 列出技能。
     *
     * @param category 分类参数。
     * @return 返回技能列表。
     */
    @Override
    public List<SkillDescriptor> listSkills(String category) throws Exception {
        processPendingImportsQuietly();
        return filterCategory(listConfiguredSkills(null), category);
    }

    /**
     * 列出技能。
     *
     * @param category 分类参数。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回技能列表。
     */
    public List<SkillDescriptor> listSkills(String category, AgentRuntimeScope agentScope)
            throws Exception {
        processPendingImportsQuietly();
        List<SkillDescriptor> skills = listConfiguredSkills(null);
        if (agentScope != null
                && !agentScope.isDefaultAgentName()
                && StrUtil.isNotBlank(agentScope.getSkillsDir())) {
            addUniqueSkills(
                    skills,
                    listSkillsFromRoot(
                            FileUtil.file(agentScope.getSkillsDir()),
                            null,
                            "local",
                            "agent-created"));
        }
        skills.sort(skillComparator());
        skills = filterAgentSkills(skills, agentScope);
        return filterCategory(skills, category);
    }

    /**
     * 列出技能From根用户。
     *
     * @param root root 参数。
     * @param category 分类参数。
     * @return 返回技能From根用户列表。
     */
    private List<SkillDescriptor> listSkillsFromRoot(File root, String category) throws Exception {
        return listSkillsFromRoot(root, category, "local", "agent-created");
    }

    /**
     * 列出技能From根用户。
     *
     * @param root root 参数。
     * @param category 分类参数。
     * @param source 来源参数。
     * @param trustLevel trustLevel 参数。
     * @return 返回技能From根用户列表。
     */
    private List<SkillDescriptor> listSkillsFromRoot(
            File root, String category, String source, String trustLevel) throws Exception {
        if (!root.exists()) {
            return Collections.emptyList();
        }

        List<SkillDescriptor> skills = new ArrayList<SkillDescriptor>();
        collectSkills(root, skills, source, trustLevel);
        skills.sort(skillComparator());

        if (StrUtil.isBlank(category)) {
            return skills;
        }

        List<SkillDescriptor> filtered = new ArrayList<SkillDescriptor>();
        for (SkillDescriptor descriptor : skills) {
            if (category.equals(descriptor.getCategory())) {
                filtered.add(descriptor);
            }
        }
        return filtered;
    }

    /**
     * 列出Configured技能。
     *
     * @param category 分类参数。
     * @return 返回Configured技能列表。
     */
    private List<SkillDescriptor> listConfiguredSkills(String category) throws Exception {
        List<SkillDescriptor> skills = new ArrayList<SkillDescriptor>();
        addUniqueSkills(
                skills,
                listSkillsFromRoot(
                        skillDirectoryResolver.localSkillsDir(), null, "local", "agent-created"));
        for (File externalDir : skillDirectoryResolver.externalSkillsDirs()) {
            addUniqueSkills(skills, listSkillsFromRoot(externalDir, null, "external", "external"));
        }
        skills.sort(skillComparator());
        return filterCategory(skills, category);
    }

    /**
     * 追加Unique技能。
     *
     * @param target target 参数。
     * @param incoming 入站消息参数。
     */
    private void addUniqueSkills(List<SkillDescriptor> target, List<SkillDescriptor> incoming) {
        for (SkillDescriptor descriptor : incoming) {
            boolean exists = false;
            for (SkillDescriptor current : target) {
                if (current.canonicalName().equals(descriptor.canonicalName())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                target.add(descriptor);
            }
        }
    }

    /**
     * 执行过滤器Category相关逻辑。
     *
     * @param skills 技能参数。
     * @param category 分类参数。
     * @return 返回filter Category结果。
     */
    private List<SkillDescriptor> filterCategory(List<SkillDescriptor> skills, String category) {
        if (StrUtil.isBlank(category)) {
            return skills;
        }
        List<SkillDescriptor> filtered = new ArrayList<SkillDescriptor>();
        for (SkillDescriptor descriptor : skills) {
            if (category.equals(descriptor.getCategory())) {
                filtered.add(descriptor);
            }
        }
        return filtered;
    }

    /**
     * 执行技能Comparator相关逻辑。
     *
     * @return 返回技能Comparator结果。
     */
    private Comparator<SkillDescriptor> skillComparator() {
        return new Comparator<SkillDescriptor>() {
            /**
             * 比较两个对象的排序位置。
             *
             * @param left 左侧比较对象。
             * @param right 右侧比较对象。
             * @return 返回compare结果。
             */
            @Override
            public int compare(SkillDescriptor left, SkillDescriptor right) {
                String leftCategory = StrUtil.nullToDefault(left.getCategory(), "");
                String rightCategory = StrUtil.nullToDefault(right.getCategory(), "");
                int result = leftCategory.compareTo(rightCategory);
                if (result != 0) {
                    return result;
                }
                return left.getName().compareTo(right.getName());
            }
        };
    }

    /**
     * 读取技能内容并组装展示视图。
     *
     * @param nameOrPath 技能名称或技能文件路径。
     * @param filePath 目标文件相对路径或绝对路径。
     * @return 返回视图技能结果。
     */
    @Override
    public SkillView viewSkill(String nameOrPath, String filePath) throws Exception {
        return viewSkill(nameOrPath, filePath, null);
    }

    /**
     * 读取技能内容并组装展示视图。
     *
     * @param nameOrPath 技能名称或技能文件路径。
     * @param filePath 目标文件相对路径或绝对路径。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回视图技能结果。
     */
    public SkillView viewSkill(String nameOrPath, String filePath, AgentRuntimeScope agentScope)
            throws Exception {
        return viewSkill(nameOrPath, filePath, agentScope, null);
    }

    /**
     * 读取技能内容并组装展示视图。
     *
     * @param nameOrPath 技能名称或技能文件路径。
     * @param filePath 目标文件相对路径或绝对路径。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param sessionId 当前会话标识。
     * @return 返回视图技能结果。
     */
    public SkillView viewSkill(
            String nameOrPath, String filePath, AgentRuntimeScope agentScope, String sessionId)
            throws Exception {
        return loadSkillView(nameOrPath, filePath, agentScope, sessionId, true);
    }

    /**
     * 加载技能视图。
     *
     * @param nameOrPath 技能名称或技能文件路径。
     * @param filePath 目标文件相对路径或绝对路径。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param sessionId 当前会话标识。
     * @param preprocess 是否执行技能内容预处理。
     * @return 返回技能视图。
     */
    private SkillView loadSkillView(
            String nameOrPath,
            String filePath,
            AgentRuntimeScope agentScope,
            String sessionId,
            boolean preprocess)
            throws Exception {
        processPendingImportsQuietly();
        SkillDescriptor descriptor = findDescriptor(nameOrPath, agentScope);
        if (descriptor == null) {
            throw new IllegalStateException("Skill not found: " + nameOrPath);
        }

        File target = resolveSkillFile(descriptor, filePath);
        if (!target.exists()) {
            throw new IllegalStateException(
                    "Skill file not found: " + safeSkillFilePath(descriptor, target));
        }

        String content = FileUtil.readUtf8String(target);
        if (preprocess && shouldPreprocessSkillContent(filePath)) {
            content = preprocessSkillContent(content, descriptor, sessionId);
        }

        SkillView view = new SkillView();
        view.setDescriptor(descriptor);
        view.setFilePath(filePath);
        view.setContent(content);
        view.setLinkedFiles(new ArrayList<String>(descriptor.getLinkedFiles()));
        return view;
    }

    /**
     * 判断是否需要Preprocess技能Content。
     *
     * @param filePath 目标文件相对路径或绝对路径。
     * @return 如果Preprocess技能Content满足条件则返回 true，否则返回 false。
     */
    private boolean shouldPreprocessSkillContent(String filePath) {
        String normalized = StrUtil.nullToEmpty(filePath).trim().replace('\\', '/');
        return StrUtil.isBlank(normalized)
                || SkillConstants.SKILL_FILE_NAME.equalsIgnoreCase(normalized);
    }

    /**
     * 执行preprocess技能Content相关逻辑。
     *
     * @param content 待处理内容。
     * @param descriptor descriptor 参数。
     * @param sessionId 当前会话标识。
     * @return 返回preprocess技能Content结果。
     */
    private String preprocessSkillContent(
            String content, SkillDescriptor descriptor, String sessionId) {
        if (StrUtil.isEmpty(content)) {
            return content;
        }
        String processed = content;
        if (appConfig.getSkills().isTemplateVars()) {
            processed = substituteTemplateVars(processed, descriptor, sessionId);
        }
        if (appConfig.getSkills().isInlineShell()) {
            processed = expandInlineShell(processed, descriptor);
        }
        return processed;
    }

    /**
     * 执行substituteTemplateVars相关逻辑。
     *
     * @param content 待处理内容。
     * @param descriptor descriptor 参数。
     * @param sessionId 当前会话标识。
     * @return 返回substitute Template Vars结果。
     */
    private String substituteTemplateVars(
            String content, SkillDescriptor descriptor, String sessionId) {
        final String skillDir = canonicalSkillDir(descriptor);
        final String resolvedSessionId = StrUtil.blankToDefault(sessionId, null);
        Matcher matcher = SKILL_TEMPLATE_VARIABLE_PATTERN.matcher(content);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = null;
            if ("SOLONCLAW_SKILL_DIR".equals(token)) {
                replacement = skillDir;
            } else if ("SOLONCLAW_SESSION_ID".equals(token)) {
                replacement = resolvedSessionId;
            }
            matcher.appendReplacement(
                    buffer,
                    replacement == null
                            ? Matcher.quoteReplacement(matcher.group(0))
                            : Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 执行expand内联终端相关逻辑。
     *
     * @param content 待处理内容。
     * @param descriptor descriptor 参数。
     * @return 返回expand Inline Shell结果。
     */
    private String expandInlineShell(String content, SkillDescriptor descriptor) {
        if (!content.contains("!`")) {
            return content;
        }
        Matcher matcher = INLINE_SHELL_PATTERN.matcher(content);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String command = StrUtil.nullToEmpty(matcher.group(1)).trim();
            String replacement = command.length() == 0 ? "" : runInlineShell(command, descriptor);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 运行Inline Shell。
     *
     * @param command 待执行或解析的命令文本。
     * @param descriptor descriptor 参数。
     * @return 返回Inline Shell结果。
     */
    private String runInlineShell(String command, SkillDescriptor descriptor) {
        int timeoutSeconds = Math.max(1, appConfig.getSkills().getInlineShellTimeoutSeconds());
        Process process = null;
        try {
            process =
                    new ProcessBuilder("bash", "-c", command)
                            .directory(FileUtil.file(descriptor.getSkillDir()))
                            .redirectErrorStream(false)
                            .start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "[inline-shell timeout after " + timeoutSeconds + "s: " + command + "]";
            }
            String stdout = streamText(process.getInputStream());
            String stderr = streamText(process.getErrorStream());
            String output = StrUtil.isNotBlank(stdout) ? stdout : stderr;
            if (output.length() > INLINE_SHELL_MAX_OUTPUT) {
                output = output.substring(0, INLINE_SHELL_MAX_OUTPUT) + "...[truncated]";
            }
            return output;
        } catch (Exception e) {
            return "[inline-shell error: "
                    + SecretRedactor.redact(
                            StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                            200)
                    + "]";
        } finally {
            if (process != null) {
                try {
                    process.getInputStream().close();
                } catch (Exception ignored) {
                }
                try {
                    process.getErrorStream().close();
                } catch (Exception ignored) {
                }
                try {
                    process.getOutputStream().close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 执行流文本相关逻辑。
     *
     * @param inputStream 输入流参数。
     * @return 返回stream Text结果。
     */
    private String streamText(java.io.InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        byte[] bytes = IoUtil.readBytes(inputStream);
        return StrUtil.nullToEmpty(new String(bytes, StandardCharsets.UTF_8))
                .replaceAll("\\r?\\n$", "");
    }

    /**
     * 执行规范技能目录相关逻辑。
     *
     * @param descriptor descriptor 参数。
     * @return 返回规范技能Dir结果。
     */
    private String canonicalSkillDir(SkillDescriptor descriptor) {
        try {
            return FileUtil.file(descriptor.getSkillDir()).getCanonicalPath();
        } catch (Exception ignored) {
            return FileUtil.file(descriptor.getSkillDir()).getAbsolutePath();
        }
    }

    /**
     * 执行bump用量相关逻辑。
     *
     * @param nameOrPath 技能名称或技能文件路径。
     * @param kind kind 参数。
     */
    public synchronized void bumpUsage(String nameOrPath, String kind) {
        try {
            SkillDescriptor descriptor = findDescriptor(nameOrPath);
            if (descriptor == null) {
                return;
            }
            File stateFile = FileUtil.file(appConfig.getRuntime().getSkillsDir(), ".curator_state");
            Map<String, Object> state = readMap(stateFile);
            @SuppressWarnings("unchecked")
            Map<String, Object> skills =
                    state.get("skills") instanceof Map
                            ? (Map<String, Object>) state.get("skills")
                            : new LinkedHashMap<String, Object>();
            @SuppressWarnings("unchecked")
            Map<String, Object> record =
                    skills.get(descriptor.canonicalName()) instanceof Map
                            ? (Map<String, Object>) skills.get(descriptor.canonicalName())
                            : new LinkedHashMap<String, Object>();
            String counter =
                    "call".equalsIgnoreCase(StrUtil.nullToEmpty(kind)) ? "callCount" : "loadCount";
            record.put(counter, Long.valueOf(asLong(record.get(counter)) + 1L));
            record.put("lastActivityAt", Long.valueOf(System.currentTimeMillis()));
            skills.put(descriptor.canonicalName(), record);
            state.put("skills", skills);
            FileUtil.mkParentDirs(stateFile);
            FileUtil.writeUtf8String(ONode.serialize(state), stateFile);
        } catch (Exception ignored) {
            // 技能用量计数只服务于后台维护决策，不能影响正常技能加载。
        }
    }

    /**
     * 渲染技能索引提示词。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回render技能Index提示词结果。
     */
    @Override
    public String renderSkillIndexPrompt(String sourceKey) throws Exception {
        return renderSkillIndexPrompt(sourceKey, null);
    }

    /**
     * 渲染技能索引提示词。
     *
     * @param sourceKey 渠道来源键。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回render技能Index提示词结果。
     */
    public String renderSkillIndexPrompt(String sourceKey, AgentRuntimeScope agentScope)
            throws Exception {
        processPendingImportsQuietly();
        List<SkillDescriptor> skills = listSkills(null, agentScope);
        Map<String, List<SkillDescriptor>> grouped =
                new LinkedHashMap<String, List<SkillDescriptor>>();
        for (SkillDescriptor descriptor : skills) {
            if (!isVisible(sourceKey, descriptor.canonicalName())) {
                continue;
            }
            if (!isRuntimeVisible(sourceKey, descriptor, agentScope)) {
                continue;
            }
            String category =
                    StrUtil.blankToDefault(
                            descriptor.getCategory(), SkillConstants.DEFAULT_CATEGORY);
            List<SkillDescriptor> items = grouped.get(category);
            if (items == null) {
                items = new ArrayList<SkillDescriptor>();
                grouped.put(category, items);
            }
            items.add(descriptor);
        }

        if (grouped.isEmpty()) {
            return "";
        }

        StringBuilder buffer = new StringBuilder();
        buffer.append("## Skills (渐进披露)\n");
        buffer.append("在回复前先浏览下列技能索引；如果某个技能明显匹配当前任务，请先用 skill_view(name) 加载全文再执行。\n");
        buffer.append("<available_skills>\n");
        for (Map.Entry<String, List<SkillDescriptor>> entry : grouped.entrySet()) {
            buffer.append("  ").append(entry.getKey()).append(":\n");
            for (SkillDescriptor descriptor : entry.getValue()) {
                buffer.append("    - ")
                        .append(descriptor.canonicalName())
                        .append(": ")
                        .append(descriptorLine(descriptor))
                        .append('\n');
            }
        }
        buffer.append("</available_skills>");
        return buffer.toString();
    }

    /**
     * 判断是否Visible。
     *
     * @param sourceKey 渠道来源键。
     * @param canonicalName canonical名称参数。
     * @return 如果Visible满足条件则返回 true，否则返回 false。
     */
    @Override
    public boolean isVisible(String sourceKey, String canonicalName) throws Exception {
        return preferenceStore.isSkillEnabled(sourceKey, canonicalName);
    }

    /**
     * 写入Visible。
     *
     * @param sourceKey 渠道来源键。
     * @param canonicalName canonical名称参数。
     * @param visible visible 参数。
     */
    @Override
    public void setVisible(String sourceKey, String canonicalName, boolean visible)
            throws Exception {
        preferenceStore.setSkillEnabled(sourceKey, canonicalName, visible);
    }

    /** 创建新技能。 */
    public SkillDescriptor createSkill(String name, String category, String content) {
        validateSkillName(name);
        validateCategory(category);
        validateSkillContent(content);
        File skillDir = resolveSkillDir(name, category);
        if (skillDir.exists()) {
            throw new IllegalStateException(
                    "Skill already exists: " + canonicalName(category, name));
        }
        writeSkillMainFile(
                skillDir, content, canonicalName(normalizeCategory(category), name) + "/SKILL.md");
        return buildDescriptor(skillDir, normalizeCategory(category));
    }

    /** 全量改写技能主文件。 */
    public SkillDescriptor editSkill(String nameOrPath, String content) throws Exception {
        validateSkillContent(content);
        SkillDescriptor descriptor = findDescriptor(nameOrPath);
        if (descriptor == null) {
            throw new IllegalStateException("Skill not found: " + nameOrPath);
        }
        ensureWritable(descriptor);
        writeSkillMainFile(
                FileUtil.file(descriptor.getSkillDir()),
                content,
                descriptor.canonicalName() + "/SKILL.md");
        return buildDescriptor(FileUtil.file(descriptor.getSkillDir()), descriptor.getCategory());
    }

    /** 在技能主文件或支持文件中做定点替换。 */
    public String patchSkill(String nameOrPath, String oldText, String newText, String filePath)
            throws Exception {
        SkillView view = loadSkillView(nameOrPath, filePath, null, null, false);
        if (StrUtil.isBlank(oldText) || !view.getContent().contains(oldText)) {
            throw new IllegalStateException("Patch target not found.");
        }
        ensureWritable(view.getDescriptor());
        if (StrUtil.isNotBlank(filePath)) {
            validateSupportFilePath(filePath, true);
        }
        File target = resolveSkillFile(view.getDescriptor(), filePath);
        writeTextAtomically(
                target,
                view.getContent().replace(oldText, StrUtil.nullToEmpty(newText)),
                safeSkillFilePath(view.getDescriptor(), target));
        return "Patched skill file: " + safeSkillFilePath(view.getDescriptor(), target);
    }

    /** 删除技能目录。 */
    public String deleteSkill(String nameOrPath) throws Exception {
        SkillDescriptor descriptor = findDescriptor(nameOrPath);
        if (descriptor == null) {
            throw new IllegalStateException("Skill not found: " + nameOrPath);
        }
        ensureWritable(descriptor);
        FileUtil.del(FileUtil.file(descriptor.getSkillDir()));
        return "Deleted skill: " + descriptor.canonicalName();
    }

    /** 写入技能支持文件。 */
    public String writeSkillFile(String nameOrPath, String filePath, String fileContent)
            throws Exception {
        SkillDescriptor descriptor = findDescriptor(nameOrPath);
        if (descriptor == null) {
            throw new IllegalStateException("Skill not found: " + nameOrPath);
        }
        ensureWritable(descriptor);
        if ("SKILL.md".equalsIgnoreCase(StrUtil.nullToEmpty(filePath).trim().replace('\\', '/'))) {
            return editSkill(nameOrPath, fileContent).canonicalName();
        }
        validateSupportFilePath(filePath, true);
        File target = resolveSkillFile(descriptor, filePath);
        writeTextAtomically(
                target, StrUtil.nullToEmpty(fileContent), safeSkillFilePath(descriptor, target));
        return "Wrote skill file: " + safeSkillFilePath(descriptor, target);
    }

    /** 删除技能支持文件。 */
    public String removeSkillFile(String nameOrPath, String filePath) throws Exception {
        SkillDescriptor descriptor = findDescriptor(nameOrPath);
        if (descriptor == null) {
            throw new IllegalStateException("Skill not found: " + nameOrPath);
        }
        ensureWritable(descriptor);
        validateSupportFilePath(filePath, true);
        File target = resolveSkillFile(descriptor, filePath);
        if (!target.exists()) {
            throw new IllegalStateException(
                    "Skill file not found: " + safeSkillFilePath(descriptor, target));
        }
        FileUtil.del(target);
        return "Removed skill file: " + safeSkillFilePath(descriptor, target);
    }

    /** 预测新技能主文件路径。 */
    public File resolveSkillMainFile(String name, String category) {
        validateSkillName(name);
        validateCategory(category);
        return FileUtil.file(resolveSkillDir(name, category), SkillConstants.SKILL_FILE_NAME);
    }

    /** 递归扫描根目录与单层分类目录中的技能。 */
    private void collectSkills(
            File root, List<SkillDescriptor> output, String source, String trustLevel) {
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }

        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }

            File directSkill = FileUtil.file(child, SkillConstants.SKILL_FILE_NAME);
            if (directSkill.exists()) {
                output.add(buildDescriptor(child, null, source, trustLevel));
                continue;
            }

            File[] nestedChildren = child.listFiles();
            if (nestedChildren == null) {
                continue;
            }
            for (File nested : nestedChildren) {
                if (nested.isDirectory()
                        && FileUtil.file(nested, SkillConstants.SKILL_FILE_NAME).exists()) {
                    output.add(buildDescriptor(nested, child.getName(), source, trustLevel));
                }
            }
        }
    }

    /** 构建技能元数据。 */
    private SkillDescriptor buildDescriptor(File skillDir, String category) {
        return buildDescriptor(skillDir, category, "local", "agent-created");
    }

    /**
     * 构建描述符。
     *
     * @param skillDir 文件或目录路径参数。
     * @param category 分类参数。
     * @param defaultSource 默认来源参数。
     * @param defaultTrustLevel 默认TrustLevel参数。
     * @return 返回创建好的描述符。
     */
    private SkillDescriptor buildDescriptor(
            File skillDir, String category, String defaultSource, String defaultTrustLevel) {
        File skillFile = FileUtil.file(skillDir, SkillConstants.SKILL_FILE_NAME);
        String content = FileUtil.readUtf8String(skillFile);
        Map<String, Object> frontmatter = SkillFrontmatterSupport.parseFrontmatter(content);
        SkillDescriptor descriptor = new SkillDescriptor();
        descriptor.setName(SkillFrontmatterSupport.resolveName(frontmatter, skillDir.getName()));
        descriptor.setCategory(category);
        descriptor.setSkillDir(skillDir.getAbsolutePath());
        descriptor.setDescription(
                SkillFrontmatterSupport.resolveDescription(
                        frontmatter, extractDescription(skillDir.getName(), content)));
        descriptor.setLinkedFiles(scanLinkedFiles(skillDir));
        descriptor.setTags(new ArrayList<String>(SkillFrontmatterSupport.resolveTags(frontmatter)));
        descriptor.setPlatforms(
                new ArrayList<String>(
                        SkillFrontmatterSupport.parseStringList(frontmatter.get("platforms"))));
        descriptor.setMetadata(new LinkedHashMap<String, Object>(frontmatter));
        Object credentialFiles = frontmatter.get("required_credential_files");
        SkillCredentialFileService.CredentialFilePlan credentialFilePlan =
                new SkillCredentialFileService(appConfig).plan(credentialFiles);
        if (!credentialFilePlan.getMounts().isEmpty()
                || !credentialFilePlan.getMissing().isEmpty()
                || !credentialFilePlan.getRejected().isEmpty()) {
            descriptor.getMetadata().put("credential_files", credentialFilePlan.toMetadata());
        }
        descriptor.setSetupState(SkillFrontmatterSupport.resolveSetupState(frontmatter).name());

        HubInstallRecord hubRecord = findHubRecord(category, skillDir.getName());
        if (hubRecord != null) {
            descriptor.setSource(hubRecord.getSource());
            descriptor.setIdentifier(hubRecord.getIdentifier());
            descriptor.setTrustLevel(hubRecord.getTrustLevel());
            descriptor.getMetadata().put("hub", hubRecord.getMetadata());
        } else {
            descriptor.setSource(defaultSource);
            descriptor.setIdentifier(descriptor.canonicalName());
            descriptor.setTrustLevel(defaultTrustLevel);
            if ("external".equals(defaultSource)) {
                descriptor.getMetadata().put("external", Boolean.TRUE);
            }
        }
        return descriptor;
    }

    /** 通过规范名或路径定位技能。 */
    private SkillDescriptor findDescriptor(String nameOrPath) throws Exception {
        return findDescriptor(nameOrPath, null);
    }

    /**
     * 查找描述符。
     *
     * @param nameOrPath 技能名称或技能文件路径。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回描述符结果。
     */
    private SkillDescriptor findDescriptor(String nameOrPath, AgentRuntimeScope agentScope)
            throws Exception {
        for (SkillDescriptor descriptor : listSkills(null, agentScope)) {
            if (descriptor.canonicalName().equals(nameOrPath)
                    || descriptor.getName().equals(nameOrPath)) {
                return descriptor;
            }
        }
        return null;
    }

    /**
     * 执行过滤器Agent技能相关逻辑。
     *
     * @param skills 技能参数。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回filter Agent技能结果。
     */
    private List<SkillDescriptor> filterAgentSkills(
            List<SkillDescriptor> skills, AgentRuntimeScope agentScope) {
        if (AgentRuntimePolicy.resolveAllowedSkills(agentScope).isEmpty()) {
            return skills;
        }
        List<SkillDescriptor> filtered = new ArrayList<SkillDescriptor>();
        for (SkillDescriptor descriptor : skills) {
            if (AgentRuntimePolicy.isSkillAllowed(agentScope, descriptor)) {
                filtered.add(descriptor);
            }
        }
        return filtered;
    }

    /** 从 SKILL.md 中提取描述；若无 frontmatter，则回退到首行正文。 */
    private String extractDescription(String fallbackName, String content) {
        if (StrUtil.isBlank(content)) {
            return fallbackName;
        }

        if (content.startsWith("---")) {
            String[] lines = content.split("\\R");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if ("---".equals(line)) {
                    break;
                }
                if (line.startsWith("description:")) {
                    return line.substring("description:".length())
                            .trim()
                            .replace("\"", "")
                            .replace("'", "");
                }
            }
        }

        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.length() == 0 || trimmed.startsWith("#")) {
                continue;
            }
            return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
        }
        return fallbackName;
    }

    /** 扫描技能支持文件列表。 */
    private List<String> scanLinkedFiles(File skillDir) {
        List<String> linkedFiles = new ArrayList<String>();
        addRelativeFiles(skillDir, SkillConstants.REFERENCES_DIR, linkedFiles);
        addRelativeFiles(skillDir, SkillConstants.TEMPLATES_DIR, linkedFiles);
        addRelativeFiles(skillDir, SkillConstants.SCRIPTS_DIR, linkedFiles);
        addRelativeFiles(skillDir, SkillConstants.ASSETS_DIR, linkedFiles);
        linkedFiles.sort(String::compareTo);
        return linkedFiles;
    }

    /** 扫描指定支持目录内文件。 */
    private void addRelativeFiles(File skillDir, String childDirName, List<String> output) {
        File childDir = FileUtil.file(skillDir, childDirName);
        if (!childDir.exists() || !childDir.isDirectory()) {
            return;
        }

        List<File> files = SkillIgnoreSupport.includedFiles(skillDir);
        for (File file : files) {
            String relative = SkillIgnoreSupport.relativePath(skillDir, file);
            if (relative.startsWith(childDirName + "/") && !relative.startsWith(".hub")) {
                output.add(relative);
            }
        }
    }

    /**
     * 确保Writable。
     *
     * @param descriptor descriptor 参数。
     */
    @SuppressWarnings("unchecked")
    private void ensureWritable(SkillDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        Map<String, Object> metadata = descriptor.getMetadata();
        boolean pinned = false;
        boolean readOnly = false;
        if (metadata != null) {
            pinned = asBoolean(metadata.get("pinned"));
            readOnly = asBoolean(metadata.get("readonly")) || asBoolean(metadata.get("readOnly"));
            Object curator = metadata.get("curator");
            if (curator instanceof Map) {
                pinned = pinned || asBoolean(((Map<String, Object>) curator).get("pinned"));
                readOnly = readOnly || asBoolean(((Map<String, Object>) curator).get("readonly"));
            }
        }
        if (pinned || readOnly || !"agent-created".equals(descriptor.getTrustLevel())) {
            throw new IllegalStateException(
                    "Skill is pinned/read-only and cannot be modified: "
                            + descriptor.canonicalName());
        }
    }

    /**
     * 执行as布尔值相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回as Boolean结果。
     */
    private boolean asBoolean(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    /**
     * 读取Map。
     *
     * @param file 文件或目录路径参数。
     * @return 返回读取到的Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(File file) {
        if (file == null || !file.isFile()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object parsed = ONode.deserialize(FileUtil.readUtf8String(file), Object.class);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<String, Object>();
    }

    /**
     * 执行as长整型相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回as Long结果。
     */
    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** 解析技能目录。 */
    private File resolveSkillDir(String name, String category) {
        String normalizedCategory = normalizeCategory(category);
        if (StrUtil.isBlank(normalizedCategory)) {
            return FileUtil.file(appConfig.getRuntime().getSkillsDir(), name);
        }
        return FileUtil.file(appConfig.getRuntime().getSkillsDir(), normalizedCategory, name);
    }

    /** 规范化分类值。 */
    private String normalizeCategory(String category) {
        if (StrUtil.isBlank(category)
                || SkillConstants.DEFAULT_CATEGORY.equalsIgnoreCase(category)) {
            return null;
        }
        return category.trim();
    }

    /** 写技能主文件并创建默认目录结构。 */
    private void writeSkillMainFile(File skillDir, String content, String displayPath) {
        FileUtil.mkdir(skillDir);
        FileUtil.mkdir(FileUtil.file(skillDir, SkillConstants.REFERENCES_DIR));
        FileUtil.mkdir(FileUtil.file(skillDir, SkillConstants.TEMPLATES_DIR));
        FileUtil.mkdir(FileUtil.file(skillDir, SkillConstants.SCRIPTS_DIR));
        FileUtil.mkdir(FileUtil.file(skillDir, SkillConstants.ASSETS_DIR));
        writeTextAtomically(
                FileUtil.file(skillDir, SkillConstants.SKILL_FILE_NAME),
                StrUtil.nullToEmpty(content),
                displayPath);
    }

    /** 解析技能支持文件路径。 */
    private File resolveSkillFile(SkillDescriptor descriptor, String filePath) throws Exception {
        if (StrUtil.isNotBlank(filePath)) {
            validateSupportFilePath(filePath);
        }

        File skillDir = FileUtil.file(descriptor.getSkillDir()).getCanonicalFile();
        File candidate =
                StrUtil.isBlank(filePath)
                        ? FileUtil.file(skillDir, SkillConstants.SKILL_FILE_NAME)
                        : FileUtil.file(skillDir, filePath);
        File target = candidate.getCanonicalFile();
        String skillRoot = skillDir.getAbsolutePath() + File.separator;
        if (!target.getAbsolutePath().equals(skillDir.getAbsolutePath())
                && !target.getAbsolutePath().startsWith(skillRoot)) {
            throw new IllegalStateException(
                    "Skill file path is outside skill directory: " + filePath);
        }
        return target;
    }

    /**
     * 生成安全展示用的技能文件路径。
     *
     * @param descriptor descriptor 参数。
     * @param target target 参数。
     * @return 返回safe技能文件路径。
     */
    private String safeSkillFilePath(SkillDescriptor descriptor, File target) {
        String value = target == null ? "" : target.getName();
        try {
            File skillDir = FileUtil.file(descriptor.getSkillDir()).getCanonicalFile();
            File canonical = target.getCanonicalFile();
            String root = skillDir.getAbsolutePath() + File.separator;
            if (canonical.getAbsolutePath().equals(skillDir.getAbsolutePath())) {
                value = descriptor.canonicalName();
            } else if (canonical.getAbsolutePath().startsWith(root)) {
                String relative =
                        canonical
                                .getAbsolutePath()
                                .substring(root.length())
                                .replace(File.separatorChar, '/');
                value = descriptor.canonicalName() + "/" + relative;
            }
        } catch (Exception ignored) {
        }
        return SecretRedactor.redact(value, 400);
    }

    /** 生成规范名。 */
    private String canonicalName(String category, String name) {
        return StrUtil.isBlank(category) ? name : category + "/" + name;
    }

    /** 校验技能名。 */
    private void validateSkillName(String name) {
        if (StrUtil.isBlank(name) || !name.matches(VALID_NAME_PATTERN)) {
            throw new IllegalStateException("Invalid skill name: " + name);
        }
    }

    /** 校验分类名。 */
    private void validateCategory(String category) {
        if (StrUtil.isBlank(category)) {
            return;
        }
        if (category.contains("/") || category.contains("\\")) {
            throw new IllegalStateException("Category must be a single directory name.");
        }
        if (!category.matches(VALID_NAME_PATTERN)) {
            throw new IllegalStateException("Invalid category: " + category);
        }
    }

    /** 校验技能主文件内容。 */
    private void validateSkillContent(String content) {
        if (StrUtil.isBlank(content)) {
            throw new IllegalStateException("Skill content cannot be empty.");
        }
        if (!content.startsWith("---")) {
            throw new IllegalStateException("Skill content must start with YAML frontmatter.");
        }
    }

    /** 校验支持文件相对路径。 */
    private void validateSupportFilePath(String filePath) {
        validateSupportFilePath(filePath, false);
    }

    /**
     * 校验辅助文件路径。
     *
     * @param filePath 目标文件相对路径或绝对路径。
     * @param writeLike 写入Like参数。
     */
    private void validateSupportFilePath(String filePath, boolean writeLike) {
        if (StrUtil.isBlank(filePath) || filePath.contains("..")) {
            throw new IllegalStateException("Invalid skill file path: " + filePath);
        }
        SecurityPolicyService.FileVerdict verdict =
                new SecurityPolicyService(appConfig).checkPath(filePath, writeLike);
        if (!verdict.isAllowed()) {
            throw new IllegalStateException(
                    "Skill file path blocked by security policy: "
                            + SecretRedactor.redact(verdict.getPath(), 400)
                            + " - "
                            + verdict.getMessage());
        }
    }

    /**
     * 查找中心记录。
     *
     * @param category 分类参数。
     * @param name 名称参数。
     * @return 返回中心记录结果。
     */
    private HubInstallRecord findHubRecord(String category, String name) {
        String installPath = StrUtil.isBlank(category) ? name : category + "/" + name;
        for (HubInstallRecord record : hubStateStore.listInstalled()) {
            if (installPath.equals(record.getInstallPath())) {
                return record;
            }
        }
        return null;
    }

    /** 执行待恢复ImportsQuietly相关逻辑。 */
    private void processPendingImportsQuietly() {
        if (skillImportService == null) {
            return;
        }
        try {
            skillImportService.processPendingImports(false);
        } catch (Exception ignored) {
            // 自动导入失败不能影响正常技能使用。
        }
    }

    /**
     * 判断是否运行时Visible。
     *
     * @param sourceKey 渠道来源键。
     * @param descriptor descriptor 参数。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 如果运行时Visible满足条件则返回 true，否则返回 false。
     */
    private boolean isRuntimeVisible(
            String sourceKey, SkillDescriptor descriptor, AgentRuntimeScope agentScope) {
        if (SkillSetupState.UNSUPPORTED.name().equals(descriptor.getSetupState())) {
            return false;
        }
        if (!checkRequiresTools(
                sourceKey,
                SkillFrontmatterSupport.parseStringList(
                        descriptor.getMetadata().get("requires_tools")),
                agentScope)) {
            return false;
        }
        if (!checkRequiresToolsets(
                sourceKey,
                SkillFrontmatterSupport.parseStringList(
                        descriptor.getMetadata().get("requires_toolsets")),
                agentScope)) {
            return false;
        }
        return true;
    }

    /**
     * 检查Requires工具。
     *
     * @param sourceKey 渠道来源键。
     * @param tools tools 参数。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回Requires工具结果。
     */
    private boolean checkRequiresTools(
            String sourceKey, List<String> tools, AgentRuntimeScope agentScope) {
        if (tools.isEmpty()) {
            return true;
        }
        for (String tool : tools) {
            if (!isToolEnabled(sourceKey, tool, agentScope)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查Requires Toolsets。
     *
     * @param sourceKey 渠道来源键。
     * @param toolsets toolsets 参数。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回Requires Toolsets结果。
     */
    private boolean checkRequiresToolsets(
            String sourceKey, List<String> toolsets, AgentRuntimeScope agentScope) {
        if (toolsets.isEmpty()) {
            return true;
        }
        for (String toolset : toolsets) {
            if (!isAnyToolEnabled(sourceKey, toolNamesForToolset(toolset), agentScope)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查兜底工具。
     *
     * @param sourceKey 渠道来源键。
     * @param tools tools 参数。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回兜底工具结果。
     */
    private boolean checkFallbackTools(
            String sourceKey, List<String> tools, AgentRuntimeScope agentScope) {
        if (tools.isEmpty()) {
            return true;
        }
        for (String tool : tools) {
            if (isToolEnabled(sourceKey, tool, agentScope)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查兜底Toolsets。
     *
     * @param sourceKey 渠道来源键。
     * @param toolsets toolsets 参数。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回兜底Toolsets结果。
     */
    private boolean checkFallbackToolsets(
            String sourceKey, List<String> toolsets, AgentRuntimeScope agentScope) {
        if (toolsets.isEmpty()) {
            return true;
        }
        for (String toolset : toolsets) {
            if (isAnyToolEnabled(sourceKey, toolNamesForToolset(toolset), agentScope)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断是否Any工具启用。
     *
     * @param sourceKey 渠道来源键。
     * @param tools tools 参数。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 如果Any工具启用满足条件则返回 true，否则返回 false。
     */
    private boolean isAnyToolEnabled(
            String sourceKey, List<String> tools, AgentRuntimeScope agentScope) {
        if (tools.isEmpty()) {
            return false;
        }
        for (String tool : tools) {
            if (isToolEnabled(sourceKey, tool, agentScope)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否工具启用。
     *
     * @param sourceKey 渠道来源键。
     * @param toolName 工具名称。
     * @return 如果工具启用满足条件则返回 true，否则返回 false。
     */
    private boolean isToolEnabled(String sourceKey, String toolName) {
        return isToolEnabled(sourceKey, toolName, null);
    }

    /**
     * 判断是否工具启用。
     *
     * @param sourceKey 渠道来源键。
     * @param toolName 工具名称。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 如果工具启用满足条件则返回 true，否则返回 false。
     */
    private boolean isToolEnabled(String sourceKey, String toolName, AgentRuntimeScope agentScope) {
        if (!AgentRuntimePolicy.isToolAllowed(agentScope, toolName)) {
            return false;
        }
        try {
            return preferenceStore.isToolEnabled(sourceKey, toolName);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 执行工具NamesFor工具集相关逻辑。
     *
     * @param toolset 工具集参数。
     * @return 返回工具Names For Toolset结果。
     */
    private List<String> toolNamesForToolset(String toolset) {
        if ("web".equalsIgnoreCase(toolset)) {
            return java.util.Arrays.asList(
                    ToolNameConstants.WEBSEARCH,
                    ToolNameConstants.WEBFETCH,
                    ToolNameConstants.CODESEARCH);
        }
        if ("gateway".equalsIgnoreCase(toolset) || "tool_gateway".equalsIgnoreCase(toolset)) {
            return java.util.Collections.singletonList(ToolNameConstants.TOOL_GATEWAY);
        }
        if ("terminal".equalsIgnoreCase(toolset)) {
            return java.util.Arrays.asList(
                    ToolNameConstants.EXECUTE_SHELL,
                    ToolNameConstants.PROCESS,
                    ToolNameConstants.EXECUTE_CODE,
                    ToolNameConstants.EXECUTE_PYTHON,
                    ToolNameConstants.EXECUTE_JS,
                    ToolNameConstants.GET_CURRENT_TIME,
                    ToolNameConstants.FILE_READ,
                    ToolNameConstants.FILE_WRITE,
                    ToolNameConstants.READ_FILE,
                    ToolNameConstants.WRITE_FILE,
                    ToolNameConstants.SEARCH_FILES,
                    ToolNameConstants.FILE_LIST,
                    ToolNameConstants.FILE_DELETE,
                    ToolNameConstants.PATCH);
        }
        if ("skills".equalsIgnoreCase(toolset)) {
            return java.util.Arrays.asList(
                    ToolNameConstants.SKILLS_LIST,
                    ToolNameConstants.SKILL_VIEW,
                    ToolNameConstants.SKILL_MANAGE);
        }
        if ("memory".equalsIgnoreCase(toolset)) {
            return java.util.Arrays.asList(
                    ToolNameConstants.MEMORY, ToolNameConstants.SESSION_SEARCH);
        }
        if ("cron".equalsIgnoreCase(toolset)) {
            return java.util.Collections.singletonList(ToolNameConstants.CRONJOB);
        }
        if ("messaging".equalsIgnoreCase(toolset)) {
            return java.util.Collections.singletonList(ToolNameConstants.SEND_MESSAGE);
        }
        if ("todo".equalsIgnoreCase(toolset)) {
            return java.util.Collections.singletonList(ToolNameConstants.TODO);
        }
        if ("delegate".equalsIgnoreCase(toolset)) {
            return java.util.Collections.singletonList(ToolNameConstants.DELEGATE_TASK);
        }
        if ("approval".equalsIgnoreCase(toolset)) {
            return java.util.Collections.emptyList();
        }
        if ("config".equalsIgnoreCase(toolset)) {
            return java.util.Arrays.asList(
                    ToolNameConstants.CONFIG_GET,
                    ToolNameConstants.CONFIG_SET,
                    ToolNameConstants.CONFIG_SET_SECRET,
                    ToolNameConstants.CONFIG_REFRESH);
        }
        return java.util.Collections.emptyList();
    }

    /**
     * 执行descriptor行相关逻辑。
     *
     * @param descriptor descriptor 参数。
     * @return 返回descriptor Line结果。
     */
    private String descriptorLine(SkillDescriptor descriptor) {
        StringBuilder buffer =
                new StringBuilder(StrUtil.nullToDefault(descriptor.getDescription(), ""));
        if (SkillSetupState.SETUP_NEEDED.name().equals(descriptor.getSetupState())) {
            buffer.append(" [setup_needed]");
        }
        if (StrUtil.isNotBlank(descriptor.getSource()) && !"local".equals(descriptor.getSource())) {
            buffer.append(" [").append(descriptor.getSource()).append("]");
        }
        return buffer.toString().trim();
    }

    /** 以原子替换方式写文本，降低并发写和中断写导致的半成品风险。 */
    private void writeTextAtomically(File target, String content, String displayPath) {
        try {
            FileUtil.mkParentDirs(target);
            File tempFile =
                    FileUtil.file(
                            target.getParentFile(), target.getName() + ".tmp-" + System.nanoTime());
            FileUtil.writeUtf8String(StrUtil.nullToEmpty(content), tempFile);
            try {
                Files.move(
                        tempFile.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                if (tempFile.exists()) {
                    FileUtil.del(tempFile);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to write skill file: "
                            + SecretRedactor.redact(
                                    StrUtil.blankToDefault(displayPath, target.getName()), 400),
                    e);
        }
    }
}
