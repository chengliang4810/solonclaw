package com.jimuqu.solon.claw.profile;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.llm.SolonAiLlmGateway;
import com.jimuqu.solon.claw.support.IdSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.noear.snack4.ONode;
import org.noear.solon.core.Props;

/** 根据 Profile 的模型与技能索引生成简短职责说明，不读取会话或记忆内容。 */
public class ProfileDescriptionService {
    /** 发送给模型的技能名称上限。 */
    static final int MAX_SKILLS_FOR_PROMPT = 60;

    /** 自动描述的用户可见最大字符数。 */
    static final int MAX_DESCRIPTION_LENGTH = 280;

    /** Profile 本机元数据文件名。 */
    private static final String METADATA_FILE = ".profile.json";

    /** 技能扫描时忽略的依赖、缓存和版本控制目录。 */
    private static final Set<String> EXCLUDED_SKILL_DIRS =
            unmodifiableSet(
                    ".git",
                    ".github",
                    ".hub",
                    ".archive",
                    ".venv",
                    "venv",
                    "node_modules",
                    "site-packages",
                    "__pycache__",
                    ".tox",
                    ".nox",
                    ".pytest_cache",
                    ".mypy_cache",
                    ".ruff_cache");

    /** 技能包内部仅供按需读取、不能作为独立技能发现的支持目录。 */
    private static final Set<String> SKILL_SUPPORT_DIRS =
            unmodifiableSet("references", "templates", "assets", "scripts");

    /** 约束模型只返回一个 description JSON 字段的系统提示。 */
    private static final String SYSTEM_PROMPT =
            "You describe an agent profile so a task router can decide when to use it.\n"
                    + "Use only the supplied profile name, model, provider, and installed skill"
                    + " names.\n"
                    + "Return exactly one JSON object: {\"description\":\"<plain 1-2 sentence"
                    + " description>\"}.\n"
                    + "Lead with the strongest concrete capability, do not invent capabilities, and"
                    + " keep the description within 280 characters.\n"
                    + "Return JSON only, without code fences, preamble, or closing remarks.";

    /** 可替换的模型调用边界，测试可注入本地假实现而不发网络请求。 */
    private final ModelClient modelClient;

    /** 使用现有 Solon AI 网关创建自动描述服务。 */
    public ProfileDescriptionService() {
        this(new SolonAiModelClient());
    }

    /**
     * 使用指定模型调用边界创建服务。
     *
     * @param modelClient 负责产生模型原始文本的调用器。
     */
    public ProfileDescriptionService(ModelClient modelClient) {
        if (modelClient == null) {
            throw new IllegalArgumentException("Profile description model client is required.");
        }
        this.modelClient = modelClient;
    }

    /**
     * 为单个 Profile 自动生成说明并写回本机元数据。
     *
     * @param profileName Profile 名。
     * @param profileHome Profile 工作区。
     * @param overwrite 是否允许覆盖人工说明。
     * @return 不抛出预期调用错误的生成结果。
     */
    public DescribeOutcome describe(String profileName, Path profileHome, boolean overwrite) {
        String name = StrUtil.nullToEmpty(profileName).trim().toLowerCase(Locale.ROOT);
        if (profileHome == null || !Files.isDirectory(profileHome)) {
            return DescribeOutcome.failure(name, "profile not found");
        }
        try {
            Map<String, Object> metadata = readMetadata(profileHome);
            String existing = text(metadata.get("description"));
            boolean existingAutomatic = Boolean.TRUE.equals(metadata.get("description_auto"));
            if (StrUtil.isNotBlank(existing) && !existingAutomatic && !overwrite) {
                return DescribeOutcome.failure(
                        name,
                        "profile already has a user-authored description (use --overwrite to"
                                + " replace)");
            }

            AppConfig appConfig = loadProfileConfig(profileHome);
            SkillInventory skills = collectSkillInventory(profileHome);
            String raw =
                    modelClient.complete(
                            appConfig,
                            SYSTEM_PROMPT,
                            buildUserPrompt(
                                    name,
                                    appConfig.getLlm().getModel(),
                                    appConfig.getLlm().getProvider(),
                                    skills));
            ParseOutcome parsed = parseDescription(raw);
            if (!parsed.isSuccess()) {
                return DescribeOutcome.failure(name, parsed.getReason());
            }

            metadata.put("name", name);
            metadata.put("description", parsed.getDescription());
            metadata.put("description_auto", Boolean.TRUE);
            if (!metadata.containsKey("aliases")) {
                metadata.put("aliases", new ArrayList<String>());
            }
            writeMetadata(profileHome, metadata);
            return DescribeOutcome.success(name, parsed.getDescription());
        } catch (Exception e) {
            return DescribeOutcome.failure(name, failureReason(e));
        }
    }

    /**
     * 以稳定顺序收集技能总数和最多 60 个均匀样本。
     *
     * @param profileHome Profile 工作区。
     * @return 技能总数与提示词样本。
     * @throws IOException 技能目录遍历失败。
     */
    static SkillInventory collectSkillInventory(Path profileHome) throws IOException {
        Path skillsDir = profileHome.resolve("skills");
        if (!Files.isDirectory(skillsDir)) {
            return new SkillInventory(0, Collections.<String>emptyList());
        }
        List<String> names = new ArrayList<String>();
        Files.walkFileTree(
                skillsDir,
                new SimpleFileVisitor<Path>() {
                    /** 跳过依赖与缓存目录，避免把其内部说明文件计为已安装技能。 */
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(skillsDir)
                                && EXCLUDED_SKILL_DIRS.contains(dir.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    /** 收集真实技能根目录下的 SKILL.md。 */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if ("SKILL.md".equals(file.getFileName().toString())
                                && !isSkillSupportPath(skillsDir, file)) {
                            String name = skillName(skillsDir, file);
                            if (StrUtil.isNotBlank(name)) {
                                names.add(name);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        names.sort(Comparator.naturalOrder());
        if (names.size() <= MAX_SKILLS_FOR_PROMPT) {
            return new SkillInventory(names.size(), names);
        }
        List<String> sampled = new ArrayList<String>(MAX_SKILLS_FOR_PROMPT);
        double step = (double) names.size() / (double) MAX_SKILLS_FOR_PROMPT;
        for (int index = 0; index < MAX_SKILLS_FOR_PROMPT; index++) {
            sampled.add(names.get((int) (index * step)));
        }
        return new SkillInventory(names.size(), sampled);
    }

    /**
     * 解析模型返回的 JSON，无法解析 JSON 时回退首个自然段。
     *
     * @param raw 模型原始文本。
     * @return 成功的职责说明或明确失败原因。
     */
    static ParseOutcome parseDescription(String raw) {
        String stripped = stripFence(StrUtil.nullToEmpty(raw).trim());
        if (stripped.length() == 0) {
            return ParseOutcome.failure("LLM returned an empty response");
        }
        int first = stripped.indexOf('{');
        int last = stripped.lastIndexOf('}');
        if (first >= 0 && last > first) {
            try {
                Object parsed =
                        ONode.deserialize(stripped.substring(first, last + 1), Object.class);
                if (parsed instanceof Map) {
                    Object value = ((Map<?, ?>) parsed).get("description");
                    if (!(value instanceof String) || StrUtil.isBlank((String) value)) {
                        return ParseOutcome.failure("LLM response missing 'description' field");
                    }
                    return ParseOutcome.success(limit(((String) value).trim()));
                }
            } catch (Exception ignored) {
                // 非法 JSON 按对标行为回退首个自然段。
            }
        }
        String[] paragraphs = stripped.split("\\R\\s*\\R", 2);
        String fallback = paragraphs.length == 0 ? "" : paragraphs[0].trim();
        return fallback.length() == 0
                ? ParseOutcome.failure("LLM returned an empty response")
                : ParseOutcome.success(limit(fallback));
    }

    /** 加载目标 Profile 的独立配置快照，不切换当前进程全局解析器。 */
    private AppConfig loadProfileConfig(Path profileHome) {
        Props props = new Props();
        props.loadAddIfAbsent("app.yml");
        props.put("solonclaw.workspace", profileHome.toAbsolutePath().normalize().toString());
        return AppConfig.loadDetached(props);
    }

    /** 生成只含名称、模型、Provider 与技能名称的用户提示。 */
    private String buildUserPrompt(
            String name, String model, String provider, SkillInventory inventory) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Profile name: ").append(name).append('\n');
        prompt.append("Default model: ")
                .append(StrUtil.blankToDefault(model, "(unset)"))
                .append('\n');
        prompt.append("Provider: ")
                .append(StrUtil.blankToDefault(provider, "(unset)"))
                .append('\n');
        prompt.append("Installed skill count: ").append(inventory.getTotalCount()).append('\n');
        prompt.append("Notable skills (up to ").append(MAX_SKILLS_FOR_PROMPT).append("):\n");
        if (inventory.getSampledNames().isEmpty()) {
            prompt.append("  (no skills installed)");
        } else {
            for (String skill : inventory.getSampledNames()) {
                prompt.append("  - ").append(skill).append('\n');
            }
        }
        return prompt.toString().trim();
    }

    /** 判断候选文件是否位于某个真实技能根目录的支持资料目录内。 */
    private static boolean isSkillSupportPath(Path skillsDir, Path file) {
        Path relative = skillsDir.relativize(file);
        for (int index = 0; index < relative.getNameCount() - 1; index++) {
            if (!SKILL_SUPPORT_DIRS.contains(relative.getName(index).toString()) || index == 0) {
                continue;
            }
            Path skillRoot = skillsDir.resolve(relative.subpath(0, index));
            if (Files.isRegularFile(skillRoot.resolve("SKILL.md"))) {
                return true;
            }
        }
        return false;
    }

    /** 把技能说明文件路径转换成 category/skill 或裸技能名。 */
    private static String skillName(Path skillsDir, Path file) {
        Path parent = skillsDir.relativize(file).getParent();
        if (parent == null || parent.getNameCount() == 0) {
            return "";
        }
        if (parent.getNameCount() == 1) {
            return parent.getName(0).toString();
        }
        return parent.getName(0) + "/" + parent.getName(parent.getNameCount() - 1);
    }

    /** 去掉模型常见的首尾 Markdown 代码围栏。 */
    private static String stripFence(String raw) {
        String value = raw;
        if (value.startsWith("```")) {
            int firstLineEnd = value.indexOf('\n');
            if (firstLineEnd >= 0) {
                value = value.substring(firstLineEnd + 1);
            }
        }
        if (value.trim().endsWith("```")) {
            value = value.trim();
            value = value.substring(0, value.length() - 3);
        }
        return value.trim();
    }

    /** 将自动说明限制到用户可见上限。 */
    private static String limit(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        int characters = text.codePointCount(0, text.length());
        if (characters <= MAX_DESCRIPTION_LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_DESCRIPTION_LENGTH));
    }

    /** 读取 Profile 元数据；损坏文件视为无元数据。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readMetadata(Path profileHome) throws IOException {
        Path file = profileHome.resolve(METADATA_FILE);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object parsed =
                    ONode.deserialize(
                            new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                            Object.class);
            if (parsed instanceof Map) {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
                    if (entry.getKey() != null) {
                        result.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                return result;
            }
        } catch (Exception ignored) {
            // 损坏的非关键元数据不阻断重新生成。
        }
        return new LinkedHashMap<String, Object>();
    }

    /** 原子写回 Profile 元数据，避免中途退出留下半个 JSON。 */
    private void writeMetadata(Path profileHome, Map<String, Object> metadata) throws IOException {
        Path target = profileHome.resolve(METADATA_FILE);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(
                temporary,
                ONode.serialize(metadata).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 将异常压缩成不会泄露提示词或模型原始响应的用户可见原因。 */
    private String failureReason(Exception error) {
        if (error == null) {
            return "LLM error: unknown";
        }
        String type = error.getClass().getSimpleName();
        return "LLM error: " + (StrUtil.isBlank(type) ? "unknown" : type);
    }

    /** 把元数据值转换为去首尾空白的文本。 */
    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 创建不可修改字符串集合。 */
    private static Set<String> unmodifiableSet(String... values) {
        Set<String> result = new LinkedHashSet<String>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    /** 自动描述所需的可注入模型调用边界。 */
    public interface ModelClient {
        /**
         * 请求模型生成原始文本。
         *
         * @param appConfig 目标 Profile 的独立配置。
         * @param systemPrompt 系统提示。
         * @param userPrompt 仅含非密 Profile 摘要的用户提示。
         * @return 模型原始文本。
         * @throws Exception 模型调用失败。
         */
        String complete(AppConfig appConfig, String systemPrompt, String userPrompt)
                throws Exception;
    }

    /** 使用项目现有 Solon AI 网关执行描述请求。 */
    private static class SolonAiModelClient implements ModelClient {
        /** 发送无工具、无历史的临时会话，并优先提取助手可见文本。 */
        @Override
        public String complete(AppConfig appConfig, String systemPrompt, String userPrompt)
                throws Exception {
            SessionRecord session = new SessionRecord();
            session.setSessionId("profile-description-" + IdSupport.newId());
            session.setSourceKey("profile-description");
            session.setNdjson("");
            LlmResult result =
                    new SolonAiLlmGateway(appConfig)
                            .chat(session, systemPrompt, userPrompt, Collections.emptyList());
            if (result == null) {
                return "";
            }
            if (result.getAssistantMessage() != null) {
                if (StrUtil.isNotBlank(result.getAssistantMessage().getResultContent())) {
                    return result.getAssistantMessage().getResultContent();
                }
                if (StrUtil.isNotBlank(result.getAssistantMessage().getContent())) {
                    return result.getAssistantMessage().getContent();
                }
            }
            return StrUtil.nullToEmpty(result.getRawResponse());
        }
    }

    /** 单个 Profile 自动描述结果。 */
    public static class DescribeOutcome {
        /** Profile 名。 */
        private final String profileName;

        /** 是否已成功写回。 */
        private final boolean success;

        /** 失败原因。 */
        private final String reason;

        /** 成功生成的职责说明。 */
        private final String description;

        /** 创建不可变结果。 */
        private DescribeOutcome(
                String profileName, boolean success, String reason, String description) {
            this.profileName = profileName;
            this.success = success;
            this.reason = reason;
            this.description = description;
        }

        /** 创建成功结果。 */
        static DescribeOutcome success(String profileName, String description) {
            return new DescribeOutcome(profileName, true, "", description);
        }

        /** 创建失败结果。 */
        static DescribeOutcome failure(String profileName, String reason) {
            return new DescribeOutcome(profileName, false, reason, null);
        }

        /**
         * @return Profile 名。
         */
        public String getProfileName() {
            return profileName;
        }

        /**
         * @return 成功写回时返回 true。
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * @return 失败原因。
         */
        public String getReason() {
            return reason;
        }

        /**
         * @return 成功生成的说明。
         */
        public String getDescription() {
            return description;
        }
    }

    /** 技能总数与用于提示词的均匀样本。 */
    static class SkillInventory {
        /** 未截断的技能总数。 */
        private final int totalCount;

        /** 最多 60 个稳定样本。 */
        private final List<String> sampledNames;

        /** 创建不可变技能索引。 */
        SkillInventory(int totalCount, List<String> sampledNames) {
            this.totalCount = totalCount;
            this.sampledNames = new ArrayList<String>(sampledNames);
        }

        /**
         * @return 未截断的技能总数。
         */
        int getTotalCount() {
            return totalCount;
        }

        /**
         * @return 最多 60 个技能名称样本。
         */
        List<String> getSampledNames() {
            return Collections.unmodifiableList(sampledNames);
        }
    }

    /** 模型文本解析结果。 */
    static class ParseOutcome {
        /** 是否成功获得说明。 */
        private final boolean success;

        /** 解析后的说明。 */
        private final String description;

        /** 失败原因。 */
        private final String reason;

        /** 创建不可变解析结果。 */
        private ParseOutcome(boolean success, String description, String reason) {
            this.success = success;
            this.description = description;
            this.reason = reason;
        }

        /** 创建成功解析结果。 */
        static ParseOutcome success(String description) {
            return new ParseOutcome(true, description, "");
        }

        /** 创建失败解析结果。 */
        static ParseOutcome failure(String reason) {
            return new ParseOutcome(false, null, reason);
        }

        /**
         * @return 解析成功时返回 true。
         */
        boolean isSuccess() {
            return success;
        }

        /**
         * @return 解析后的说明。
         */
        String getDescription() {
            return description;
        }

        /**
         * @return 失败原因。
         */
        String getReason() {
            return reason;
        }
    }
}
