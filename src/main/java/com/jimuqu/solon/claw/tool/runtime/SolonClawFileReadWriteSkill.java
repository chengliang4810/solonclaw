package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.skills.file.FileReadWriteSkill;
import org.noear.solon.annotation.Param;

/** 承载Solon项目文件Read写入技能相关状态和辅助逻辑。 */
public class SolonClawFileReadWriteSkill extends FileReadWriteSkill {
    /** 默认READOFFSET的统一常量值。 */
    private static final int DEFAULT_READ_OFFSET = 1;

    /** 默认READ限制的统一常量值。 */
    private static final int DEFAULT_READ_LIMIT = 500;

    /** UTF8BOM的统一常量值。 */
    private static final String UTF8_BOM = "\ufeff";

    /** READDEDUP状态消息的统一常量值。 */
    private static final String READ_DEDUP_STATUS_MESSAGE =
            "文件未变化：这一段内容已经读取过，本次不再重复返回正文。请使用之前的 read_file 结果继续任务。";

    /** 记录Solon项目文件Read写入技能中的根用户路径。 */
    private final Path rootPath;

    /** 记录Solon项目文件Read写入技能中的real根用户路径。 */
    private final Path realRootPath;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 记录Solon项目文件Read写入技能中的maxLinesSupplier。 */
    private final IntSupplier maxLinesSupplier;

    /** 记录Solon项目文件Read写入技能中的max行LengthSupplier。 */
    private final IntSupplier maxLineLengthSupplier;

    /** 记录Solon项目文件Read写入技能中的文件状态Tracker。 */
    private final SolonClawFileStateTracker fileStateTracker;

    /** 保存readDedup映射，便于按键快速查询。 */
    private final Map<ReadKey, ReadTracker> readDedup = new LinkedHashMap<ReadKey, ReadTracker>();

    /** 记录Solon项目文件Read写入技能中的最近一次Read键。 */
    private ReadKey lastReadKey;

    /** 记录Solon项目文件Read写入技能中的consecutiveRead次数。 */
    private int consecutiveReadCount;

    /** 记录Solon项目文件Read写入技能中的observedOther工具CallEpoch。 */
    private int observedOtherToolCallEpoch;

    /**
     * 创建Solon项目文件Read Write技能实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public SolonClawFileReadWriteSkill(
            String workDir, SecurityPolicyService securityPolicyService) {
        this(workDir, securityPolicyService, 2000, 2000, new SolonClawFileStateTracker());
    }

    /**
     * 创建Solon项目文件Read Write技能实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param securityPolicyService 安全策略服务依赖。
     * @param appConfig 应用运行配置。
     */
    public SolonClawFileReadWriteSkill(
            String workDir, SecurityPolicyService securityPolicyService, AppConfig appConfig) {
        this(workDir, securityPolicyService, appConfig, new SolonClawFileStateTracker());
    }

    /**
     * 创建Solon项目文件Read Write技能实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param securityPolicyService 安全策略服务依赖。
     * @param appConfig 应用运行配置。
     * @param fileStateTracker 文件或目录路径参数。
     */
    public SolonClawFileReadWriteSkill(
            String workDir,
            SecurityPolicyService securityPolicyService,
            AppConfig appConfig,
            SolonClawFileStateTracker fileStateTracker) {
        this(
                workDir,
                securityPolicyService,
                appConfigMaxLines(appConfig),
                appConfigMaxLineLength(appConfig),
                fileStateTracker);
    }

    /**
     * 创建Solon项目文件Read Write技能实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param securityPolicyService 安全策略服务依赖。
     * @param maxLines maxLines 参数。
     * @param maxLineLength max行Length参数。
     */
    public SolonClawFileReadWriteSkill(
            String workDir,
            SecurityPolicyService securityPolicyService,
            int maxLines,
            int maxLineLength) {
        this(
                workDir,
                securityPolicyService,
                maxLines,
                maxLineLength,
                new SolonClawFileStateTracker());
    }

    /**
     * 创建Solon项目文件Read Write技能实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param securityPolicyService 安全策略服务依赖。
     * @param maxLines maxLines 参数。
     * @param maxLineLength max行Length参数。
     * @param fileStateTracker 文件或目录路径参数。
     */
    public SolonClawFileReadWriteSkill(
            String workDir,
            SecurityPolicyService securityPolicyService,
            int maxLines,
            int maxLineLength,
            SolonClawFileStateTracker fileStateTracker) {
        this(
                workDir,
                securityPolicyService,
                fixedLimit(maxLines),
                fixedLimit(maxLineLength),
                fileStateTracker);
    }

    /**
     * 创建Solon项目文件Read Write技能实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param securityPolicyService 安全策略服务依赖。
     * @param maxLinesSupplier maxLinesSupplier 参数。
     * @param maxLineLengthSupplier max行LengthSupplier参数。
     * @param fileStateTracker 文件或目录路径参数。
     */
    private SolonClawFileReadWriteSkill(
            String workDir,
            SecurityPolicyService securityPolicyService,
            IntSupplier maxLinesSupplier,
            IntSupplier maxLineLengthSupplier,
            SolonClawFileStateTracker fileStateTracker) {
        super(workDir);
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        this.realRootPath = safeRealPath(this.rootPath);
        this.securityPolicyService = securityPolicyService;
        this.maxLinesSupplier = maxLinesSupplier == null ? fixedLimit(2000) : maxLinesSupplier;
        this.maxLineLengthSupplier =
                maxLineLengthSupplier == null ? fixedLimit(2000) : maxLineLengthSupplier;
        this.fileStateTracker =
                fileStateTracker == null ? new SolonClawFileStateTracker() : fileStateTracker;
    }

    /**
     * 执行写入相关逻辑。
     *
     * @param fileName 文件或目录路径参数。
     * @param content 待处理内容。
     * @return 返回write结果。
     */
    @Override
    @ToolMapping(name = "file_write", description = "写入文本到文件。会自动创建不存在的目录。")
    public String write(@Param("fileName") String fileName, @Param("content") String content) {
        assertSafe(ToolNameConstants.FILE_WRITE, fileName);
        assertNotInternalFileStatusContent(content);
        Path target = resolvePath(fileName);
        String staleWarning = fileStateTracker.checkStaleness(fileName, target);
        String result;
        boolean success;
        try {
            writeTextPreservingBom(target, content);
            result = "文件保存成功: " + fileName;
            success = true;
        } catch (Exception e) {
            result = "写入失败: " + safeToolError(e);
            success = false;
        }
        if (success) {
            clearReadDedup(fileName);
            fileStateTracker.recordWrite(target);
        }
        String safeResult = SecretRedactor.redact(result, 1000);
        ToolResultEnvelope envelope =
                success ? ToolResultEnvelope.ok(safeResult) : ToolResultEnvelope.error(safeResult);
        String resolvedPath = resolvedOutputPath(target);
        envelope.data("path", safeDisplayPath(fileName));
        if (success) {
            envelope.data("resolved_path", safeDisplayPath(resolvedPath))
                    .data(
                            "files_modified",
                            Collections.singletonList(safeDisplayPath(resolvedPath)));
        }
        if (StrUtil.isNotBlank(staleWarning)) {
            return envelope.data("_warning", safeDisplayPath(staleWarning)).toJson();
        }
        return envelope.toJson();
    }

    /**
     * 参考风格写入工具名，复用当前文件安全与结果封装。
     *
     * @param path 文件路径。
     * @param content 待写入内容。
     * @return 返回write结果。
     */
    @ToolMapping(name = "write_file", description = "Write text content to a workspace file.")
    public String writeFile(@Param("path") String path, @Param("content") String content) {
        return write(path, content);
    }

    /**
     * 执行read相关逻辑。
     *
     * @param fileName 文件或目录路径参数。
     * @return 返回read结果。
     */
    public String read(@Param("fileName") String fileName) {
        return read(fileName, null, null);
    }

    /**
     * 执行read相关逻辑。
     *
     * @param fileName 文件或目录路径参数。
     * @param offset 分页偏移量。
     * @param limit 最大返回数量。
     * @return 返回read结果。
     */
    @ToolMapping(
            name = "file_read",
            description =
                    "读取文本文件内容。返回带行号的 JSON 结果；offset 从 1 开始，limit 默认 500，并受 solonclaw.task.toolOutputMaxLines 限制。")
    public String read(
            @Param("fileName") String fileName,
            @Param(
                            name = "offset",
                            required = false,
                            defaultValue = "1",
                            description = "从第几行开始读取，1 表示第一行。")
                    Integer offset,
            @Param(
                            name = "limit",
                            required = false,
                            defaultValue = "500",
                            description = "最多读取多少行，会按 solonclaw.task.toolOutputMaxLines 截断。")
                    Integer limit) {
        assertSafe(ToolNameConstants.FILE_READ, fileName);
        return readPaged(fileName, offset, limit);
    }

    /**
     * 参考风格读取工具名，复用当前分页、去重和安全策略。
     *
     * @param path 文件路径。
     * @param offset 从第几行开始读取。
     * @param limit 最大返回行数。
     * @return 返回read结果。
     */
    @ToolMapping(
            name = "read_file",
            description = "Read a text file with line numbers. offset starts at 1 and limit defaults to 500.")
    public String readFile(
            @Param("path") String path,
            @Param(name = "offset", required = false, defaultValue = "1") Integer offset,
            @Param(name = "limit", required = false, defaultValue = "500") Integer limit) {
        assertSafe(ToolNameConstants.READ_FILE, path);
        return readPaged(path, offset, limit);
    }

    /**
     * 搜索文件内容或文件名。
     *
     * @param pattern 搜索模式。
     * @param target 搜索目标：content/files。
     * @param path 搜索目录。
     * @param fileGlob 文件名包含过滤。
     * @param limit 最大返回条数。
     * @param offset 跳过条数。
     * @param outputMode 输出模式。
     * @param context 匹配上下文行数。
     * @return 返回搜索结果。
     */
    @ToolMapping(name = "search_files", description = "Search workspace files by content or file name.")
    public String searchFiles(
            @Param(name = "pattern", description = "Text or regex pattern to search.") String pattern,
            @Param(name = "target", required = false, defaultValue = "content") String target,
            @Param(name = "path", required = false, defaultValue = ".") String path,
            @Param(name = "file_glob", required = false) String fileGlob,
            @Param(name = "limit", required = false, defaultValue = "50") Integer limit,
            @Param(name = "offset", required = false, defaultValue = "0") Integer offset,
            @Param(name = "output_mode", required = false, defaultValue = "content")
                    String outputMode,
            @Param(name = "context", required = false, defaultValue = "0") Integer context) {
        assertSafe(ToolNameConstants.SEARCH_FILES, path);
        String query = StrUtil.nullToEmpty(pattern);
        if (StrUtil.isBlank(query)) {
            return ToolResultEnvelope.error("pattern is required").toJson();
        }
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit.intValue(), 200));
        int safeOffset = Math.max(0, offset == null ? 0 : offset.intValue());
        int safeContext = Math.max(0, Math.min(context == null ? 0 : context.intValue(), 5));
        Path root;
        try {
            root = resolvePath(StrUtil.blankToDefault(path, "."));
        } catch (Exception e) {
            return ToolResultEnvelope.error("搜索失败: " + safeToolError(e)).toJson();
        }
        if (!Files.exists(root)) {
            return ToolResultEnvelope.error("搜索路径不存在: " + safeDisplayPath(path))
                    .data("path", safeDisplayPath(path))
                    .toJson();
        }
        SearchCollector collector =
                new SearchCollector(
                        query,
                        StrUtil.blankToDefault(target, "content"),
                        fileGlob,
                        StrUtil.blankToDefault(outputMode, "content"),
                        safeLimit,
                        safeOffset,
                        safeContext);
        try {
            searchFiles(root, collector);
        } catch (Exception e) {
            return ToolResultEnvelope.error("搜索失败: " + safeToolError(e)).toJson();
        }
        String preview = joinSearchMatches(collector.matches);
        return ToolResultEnvelope.ok(
                        "搜索完成："
                                + collector.returned
                                + "/"
                                + collector.matched
                                + " matches")
                .data("pattern", SecretRedactor.redact(query, 400))
                .data("target", collector.target)
                .data("path", safeDisplayPath(StrUtil.blankToDefault(path, ".")))
                .data("matches", collector.matches)
                .data("match_count", Integer.valueOf(collector.matched))
                .data("returned", Integer.valueOf(collector.returned))
                .data("offset", Integer.valueOf(safeOffset))
                .data("limit", Integer.valueOf(safeLimit))
                .preview(preview)
                .truncated(collector.truncated)
                .toJson();
    }

    /**
     * 执行列表相关逻辑。
     *
     * @param dirName 文件或目录路径参数。
     * @return 返回list结果。
     */
    @Override
    @ToolMapping(name = "file_list", description = "列出指定目录下的文件和子目录。如果不指定目录，则列出根目录。")
    public String list(@Param(value = "dirName", required = false) String dirName) {
        assertSafe(ToolNameConstants.FILE_LIST, dirName);
        assertContained(dirName);
        return SecretRedactor.redact(super.list(dirName), 20000);
    }

    /**
     * 执行delete，服务于Solon项目文件Read写入技能主流程相关逻辑。
     *
     * @param fileName 文件或目录路径参数。
     * @return 返回delete结果。
     */
    @Override
    @ToolMapping(name = "file_delete", description = "删除指定文件或空目录。")
    public String delete(@Param("fileName") String fileName) {
        assertSafe(ToolNameConstants.FILE_DELETE, fileName);
        assertContained(fileName);
        String result = super.delete(fileName);
        clearReadDedup(fileName);
        return SecretRedactor.redact(result, 1000);
    }

    /**
     * 执行assert安全相关逻辑。
     *
     * @param toolName 工具名称。
     * @param path 文件或目录路径。
     */
    private void assertSafe(String toolName, String path) {
        if (securityPolicyService == null || StrUtil.isBlank(path)) {
            return;
        }
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", path);
        args.put("dirName", path);
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs(toolName, args);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(blockedMessage(toolName, verdict));
        }
    }

    /**
     * 执行阻断消息相关逻辑。
     *
     * @param toolName 工具名称。
     * @param verdict 判定参数。
     * @return 返回blocked消息结果。
     */
    private String blockedMessage(String toolName, SecurityPolicyService.FileVerdict verdict) {
        return "BLOCKED: 文件安全策略阻止访问："
                + verdict.getMessage()
                + "\n工具："
                + toolName
                + "\n路径："
                + SecretRedactor.redact(verdict.getPath(), 400)
                + "\n请改用工作区内的普通项目文件，敏感凭据文件不能通过 Agent 工具读取、写入或删除。";
    }

    /**
     * 读取Paged。
     *
     * @param fileName 文件或目录路径参数。
     * @param offset 分页偏移量。
     * @param limit 最大返回数量。
     * @return 返回读取到的Paged。
     */
    private String readPaged(String fileName, Integer offset, Integer limit) {
        if (StrUtil.isBlank(fileName)) {
            return ToolResultEnvelope.error("fileName is required").toJson();
        }
        int safeOffset = Math.max(1, offset == null ? DEFAULT_READ_OFFSET : offset.intValue());
        int safeMaxLineLength = resolveMaxLineLength();
        int safeLimit =
                Math.max(
                        1,
                        Math.min(
                                limit == null ? DEFAULT_READ_LIMIT : limit.intValue(),
                                resolveMaxLines()));
        Path target;
        try {
            target = resolvePath(fileName);
        } catch (Exception e) {
            return ToolResultEnvelope.error("读取失败: " + safeToolError(e)).toJson();
        }
        File targetFile = target.toFile();
        String resolvedPath = safeDisplayPath(resolvedOutputPath(target));
        if (!targetFile.exists()) {
            String displayPath = safeDisplayPath(fileName);
            ToolResultEnvelope envelope =
                    ToolResultEnvelope.error("文件不存在: " + displayPath)
                            .data("path", displayPath)
                            .data("resolved_path", resolvedPath);
            List<String> similarFiles = similarFiles(fileName, target);
            if (!similarFiles.isEmpty()) {
                envelope.data("similar_files", similarFiles);
            }
            return envelope.toJson();
        }
        if (targetFile.isDirectory()) {
            String displayPath = safeDisplayPath(fileName);
            return ToolResultEnvelope.error("读取失败：'" + displayPath + "' 是一个目录。请使用 file_list 查看其内容。")
                    .data("path", displayPath)
                    .data("resolved_path", resolvedPath)
                    .toJson();
        }
        ReadKey readKey =
                new ReadKey(
                        target.toAbsolutePath().normalize().toString(),
                        safeOffset,
                        safeLimit,
                        safeMaxLineLength);
        String duplicate = duplicateReadResult(fileName, readKey, targetFile);
        if (duplicate != null) {
            return duplicate;
        }

        List<String> selected = new ArrayList<String>();
        int totalLines = 0;
        int endLine = safeOffset + safeLimit - 1;
        try (BufferedReader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                if (totalLines == 1) {
                    line = stripLeadingBom(line);
                }
                if (totalLines >= safeOffset && totalLines <= endLine) {
                    selected.add(numberedLine(totalLines, line, safeMaxLineLength));
                }
            }
            boolean truncated = totalLines > endLine;
            String content = SecretRedactor.redact(joinLines(selected));
            ReadStatus readStatus = recordRead(fileName, readKey, targetFile);
            fileStateTracker.recordRead(target);
            if (readStatus.blocked) {
                return ToolResultEnvelope.error(readStatus.message)
                        .data("path", safeDisplayPath(fileName))
                        .data("resolved_path", resolvedPath)
                        .data("already_read", Integer.valueOf(readStatus.count))
                        .toJson();
            }
            String displayPath = safeDisplayPath(fileName);
            ToolResultEnvelope envelope =
                    ToolResultEnvelope.ok("文件读取完成：" + displayPath)
                            .data("path", displayPath)
                            .data("resolved_path", resolvedPath)
                            .data("content", content)
                            .data("total_lines", Integer.valueOf(totalLines))
                            .data("file_size", Long.valueOf(Files.size(target)))
                            .data("offset", Integer.valueOf(safeOffset))
                            .data("limit", Integer.valueOf(safeLimit))
                            .preview(content)
                            .truncated(truncated);
            if (truncated) {
                envelope.data(
                        "hint",
                        "Use offset="
                                + (endLine + 1)
                                + " to continue reading (showing "
                                + safeOffset
                                + "-"
                                + endLine
                                + " of "
                                + totalLines
                                + " lines)");
            }
            if (StrUtil.isNotBlank(readStatus.message)) {
                envelope.data("warning", readStatus.message);
            }
            return envelope.toJson();
        } catch (Exception e) {
            return ToolResultEnvelope.error("读取失败: " + safeToolError(e))
                    .data("path", safeDisplayPath(fileName))
                    .data("resolved_path", resolvedPath)
                    .toJson();
        }
    }

    /**
     * 递归搜索文件。
     *
     * @param root 搜索根路径。
     * @param collector 搜索状态。
     */
    private void searchFiles(Path root, SearchCollector collector) throws Exception {
        if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
            searchOneFile(root, collector);
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(root, 8)) {
            java.util.Iterator<Path> iterator = stream.iterator();
            int scanned = 0;
            while (iterator.hasNext() && scanned < 5000 && !collector.isFull()) {
                Path path = iterator.next();
                scanned++;
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !collector.acceptFile(path)) {
                    continue;
                }
                searchOneFile(path, collector);
            }
        }
    }

    /**
     * 搜索单个文件。
     *
     * @param file 文件路径。
     * @param collector 搜索状态。
     */
    private void searchOneFile(Path file, SearchCollector collector) {
        if (!allowedSuggestion(file)) {
            return;
        }
        String displayPath = displayPathForCandidate(file);
        if (collector.searchFilesOnly()) {
            if (collector.matchesText(displayPath)) {
                collector.addMatch(searchMatch(displayPath, 0, ""));
            }
            return;
        }
        if (looksBinary(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && !collector.isFull(); i++) {
                String line = stripLeadingBom(lines.get(i));
                if (collector.matchesText(line)) {
                    collector.addMatch(
                            searchMatch(
                                    displayPath,
                                    i + 1,
                                    collector.renderLine(lines, i, resolveMaxLineLength())));
                }
            }
        } catch (Exception ignored) {
            // 非UTF-8或不可读文件跳过，保持搜索工具可继续返回其它结果。
        }
    }

    /**
     * 构建搜索匹配项。
     *
     * @param path 文件路径。
     * @param line 行号。
     * @param text 匹配文本。
     * @return 返回匹配项。
     */
    private Map<String, Object> searchMatch(String path, int line, String text) {
        Map<String, Object> match = new LinkedHashMap<String, Object>();
        match.put("path", safeDisplayPath(path));
        if (line > 0) {
            match.put("line", Integer.valueOf(line));
        }
        if (StrUtil.isNotBlank(text)) {
            match.put("text", SecretRedactor.redact(text, 2000));
        }
        return match;
    }

    /**
     * 拼接搜索预览。
     *
     * @param matches 匹配项。
     * @return 返回预览文本。
     */
    private String joinSearchMatches(List<Map<String, Object>> matches) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> match : matches) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(match.get("path"));
            Object line = match.get("line");
            if (line != null) {
                builder.append(':').append(line);
            }
            Object text = match.get("text");
            if (text != null) {
                builder.append(" ").append(text);
            }
        }
        return builder.toString();
    }

    /**
     * 判断文件是否像二进制。
     *
     * @param file 文件路径。
     * @return 如果像二进制返回true。
     */
    private boolean looksBinary(Path file) {
        try {
            if (Files.size(file) > 2L * 1024L * 1024L) {
                return true;
            }
            byte[] bytes = Files.readAllBytes(file);
            int max = Math.min(bytes.length, 4096);
            for (int i = 0; i < max; i++) {
                if (bytes[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 生成安全展示用的展示路径。
     *
     * @param path 文件或目录路径。
     * @return 返回safe展示路径。
     */
    private String safeDisplayPath(String path) {
        return SecretRedactor.redact(path, 400);
    }

    /**
     * 生成安全展示用的工具错误。
     *
     * @param e 捕获到的异常。
     * @return 返回safe工具Error结果。
     */
    private String safeToolError(Exception e) {
        String message = e == null ? "" : e.getMessage();
        if (StrUtil.isBlank(message) && e != null) {
            message = e.getClass().getSimpleName();
        }
        return SecretRedactor.redact(message, 1000);
    }

    /**
     * 执行numbered行相关逻辑。
     *
     * @param lineNumber 行Number参数。
     * @param line 行参数。
     * @param maxLineLength max行Length参数。
     * @return 返回numbered Line结果。
     */
    private String numberedLine(int lineNumber, String line, int maxLineLength) {
        String value = StrUtil.nullToEmpty(line);
        if (value.length() > maxLineLength) {
            value = value.substring(0, maxLineLength) + "... [truncated]";
        }
        return lineNumber + "|" + value;
    }

    /**
     * 解析Max Lines。
     *
     * @return 返回解析后的Max Lines。
     */
    private int resolveMaxLines() {
        return resolvePositiveLimit(maxLinesSupplier, 2000);
    }

    /**
     * 解析Max Line Length。
     *
     * @return 返回解析后的Max Line Length。
     */
    private int resolveMaxLineLength() {
        return resolvePositiveLimit(maxLineLengthSupplier, 2000);
    }

    /**
     * 解析Positive限制。
     *
     * @param supplier supplier 参数。
     * @param fallback 兜底参数。
     * @return 返回解析后的Positive限制。
     */
    private static int resolvePositiveLimit(IntSupplier supplier, int fallback) {
        try {
            return Math.max(1, supplier == null ? fallback : supplier.getAsInt());
        } catch (Exception ignored) {
            return Math.max(1, fallback);
        }
    }

    /**
     * 执行fixed限制相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回fixed限制结果。
     */
    private static IntSupplier fixedLimit(final int value) {
        final int safeValue = Math.max(1, value);
        return new IntSupplier() {
            /**
             * 读取As Int。
             *
             * @return 返回读取到的As Int。
             */
            @Override
            public int getAsInt() {
                return safeValue;
            }
        };
    }

    /**
     * 执行应用配置MaxLines相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回app配置Max Lines结果。
     */
    private static IntSupplier appConfigMaxLines(final AppConfig appConfig) {
        return new IntSupplier() {
            /**
             * 读取As Int。
             *
             * @return 返回读取到的As Int。
             */
            @Override
            public int getAsInt() {
                return appConfig == null || appConfig.getTask() == null
                        ? 2000
                        : appConfig.getTask().getToolOutputMaxLines();
            }
        };
    }

    /**
     * 执行应用配置Max行Length相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回app配置Max Line Length结果。
     */
    private static IntSupplier appConfigMaxLineLength(final AppConfig appConfig) {
        return new IntSupplier() {
            /**
             * 读取As Int。
             *
             * @return 返回读取到的As Int。
             */
            @Override
            public int getAsInt() {
                return appConfig == null || appConfig.getTask() == null
                        ? 2000
                        : appConfig.getTask().getToolOutputMaxLineLength();
            }
        };
    }

    /**
     * 执行joinLines相关逻辑。
     *
     * @param lines lines 参数。
     * @return 返回join Lines结果。
     */
    private String joinLines(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    /**
     * 写入Text Preserving Bom。
     *
     * @param target target 参数。
     * @param content 待处理内容。
     */
    private void writeTextPreservingBom(Path target, String content) throws Exception {
        String value = StrUtil.nullToEmpty(content);
        if (hasLeadingBom(target) && !value.startsWith(UTF8_BOM)) {
            value = UTF8_BOM + value;
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        AtomicFileWriteSupport.writeUtf8(target, value);
    }

    /**
     * 判断是否存在Leading Bom。
     *
     * @param target target 参数。
     * @return 如果Leading Bom满足条件则返回 true，否则返回 false。
     */
    private boolean hasLeadingBom(Path target) {
        if (target == null || !Files.exists(target) || Files.isDirectory(target)) {
            return false;
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            return bytes.length >= 3
                    && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 剥离LeadingBom。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回strip Leading Bom结果。
     */
    private String stripLeadingBom(String value) {
        if (value != null && value.startsWith(UTF8_BOM)) {
            return value.substring(UTF8_BOM.length());
        }
        return value;
    }

    /**
     * 解析路径。
     *
     * @param name 名称参数。
     * @return 返回解析后的路径。
     */
    private Path resolvePath(String name) {
        String value = StrUtil.nullToEmpty(name);
        if (value.indexOf('\0') >= 0 || value.contains("!/")) {
            throw new IllegalArgumentException("jar-internal paths are not disk files");
        }
        Path path = rootPath.resolve(name).normalize();
        if (!path.startsWith(rootPath)) {
            throw new SecurityException("禁止越权访问沙箱外部");
        }
        assertResolvedWithinRoot(path);
        return path;
    }

    /**
     * 执行assertContained相关逻辑。
     *
     * @param name 名称参数。
     */
    private void assertContained(String name) {
        if (StrUtil.isBlank(name)) {
            return;
        }
        resolvePath(name);
    }

    /**
     * 执行assertResolvedWithin根用户相关逻辑。
     *
     * @param target target 参数。
     */
    private void assertResolvedWithinRoot(Path target) {
        Path existing = nearestExistingPath(target);
        if (existing == null) {
            return;
        }
        Path real = safeRealPath(existing);
        if (!real.startsWith(realRootPath)) {
            throw new SecurityException("禁止通过符号链接访问沙箱外部");
        }
    }

    /**
     * 执行nearestExisting路径相关逻辑。
     *
     * @param target target 参数。
     * @return 返回nearest Existing路径。
     */
    private Path nearestExistingPath(Path target) {
        Path current = target;
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 生成安全展示用的Real路径。
     *
     * @param path 文件或目录路径。
     * @return 返回safe Real路径。
     */
    private Path safeRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * 执行resolved输出路径相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回resolved输出路径。
     */
    private String resolvedOutputPath(Path path) {
        return safeRealPath(path).toString();
    }

    /**
     * 执行similarFiles相关逻辑。
     *
     * @param requestedPath 文件或目录路径参数。
     * @param target target 参数。
     * @return 返回similar Files结果。
     */
    private List<String> similarFiles(String requestedPath, Path target) {
        Path dir = target == null ? null : target.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        Path fileNamePath = target.getFileName();
        String requestedName =
                fileNamePath == null ? StrUtil.nullToEmpty(requestedPath) : fileNamePath.toString();
        String lowerName = requestedName.toLowerCase(Locale.ROOT);
        String requestedBase = basename(lowerName);
        String requestedExt = extension(lowerName);
        List<ScoredPath> scored = new ArrayList<ScoredPath>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            java.util.Iterator<Path> iterator = stream.iterator();
            int scanned = 0;
            while (iterator.hasNext() && scanned < 200) {
                scanned++;
                Path candidate = iterator.next();
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String candidateName =
                        candidate.getFileName() == null ? "" : candidate.getFileName().toString();
                int score = similarityScore(lowerName, requestedBase, requestedExt, candidateName);
                if (score <= 0 || !allowedSuggestion(candidate)) {
                    continue;
                }
                scored.add(new ScoredPath(score, displayPathForCandidate(candidate)));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        Collections.sort(
                scored,
                new Comparator<ScoredPath>() {
                    /**
                     * 比较两个对象的排序位置。
                     *
                     * @param left 左侧比较对象。
                     * @param right 右侧比较对象。
                     * @return 返回compare结果。
                     */
                    @Override
                    public int compare(ScoredPath left, ScoredPath right) {
                        int byScore = Integer.compare(right.score, left.score);
                        if (byScore != 0) {
                            return byScore;
                        }
                        return left.path.compareTo(right.path);
                    }
                });
        List<String> result = new ArrayList<String>();
        for (ScoredPath item : scored) {
            result.add(safeDisplayPath(item.path));
            if (result.size() >= 5) {
                break;
            }
        }
        return result;
    }

    /**
     * 执行allowedSuggestion相关逻辑。
     *
     * @param candidate candidate标识或键值。
     * @return 返回allowed Suggestion结果。
     */
    private boolean allowedSuggestion(Path candidate) {
        if (securityPolicyService == null) {
            return true;
        }
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", displayPathForCandidate(candidate));
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs(ToolNameConstants.READ_FILE, args);
        return verdict.isAllowed();
    }

    /**
     * 执行展示路径ForCandidate相关逻辑。
     *
     * @param candidate candidate标识或键值。
     * @return 返回展示路径For Candidate结果。
     */
    private String displayPathForCandidate(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (normalized.startsWith(rootPath)) {
            return rootPath.relativize(normalized).toString();
        }
        return normalized.toString();
    }

    /**
     * 执行similarityScore相关逻辑。
     *
     * @param lowerName lower名称参数。
     * @param requestedBase requested基础请求载荷。
     * @param requestedExt requestedExt请求载荷。
     * @param candidateName candidate名称标识或键值。
     * @return 返回similarity Score结果。
     */
    private int similarityScore(
            String lowerName, String requestedBase, String requestedExt, String candidateName) {
        String candidateLower = candidateName.toLowerCase(Locale.ROOT);
        String candidateBase = basename(candidateLower);
        String candidateExt = extension(candidateLower);
        if (candidateLower.equals(lowerName)) {
            return 100;
        }
        if (candidateBase.equals(requestedBase)) {
            return 90;
        }
        if (candidateLower.startsWith(lowerName) || lowerName.startsWith(candidateLower)) {
            return 70;
        }
        if (candidateLower.contains(lowerName)) {
            return 60;
        }
        if (lowerName.contains(candidateLower) && candidateLower.length() > 2) {
            return 40;
        }
        if (StrUtil.isNotBlank(requestedExt) && requestedExt.equals(candidateExt)) {
            int common = commonCharacterCount(lowerName, candidateLower);
            int max = Math.max(lowerName.length(), candidateLower.length());
            if (max > 0 && common >= Math.max(1, max * 4 / 10)) {
                return 30;
            }
        }
        if (StrUtil.isNotBlank(requestedBase) && candidateBase.contains(requestedBase)) {
            return 20;
        }
        return 0;
    }

    /**
     * 执行commonCharacter次数相关逻辑。
     *
     * @param left 左侧比较对象。
     * @param right 右侧比较对象。
     * @return 返回common Character次数结果。
     */
    private int commonCharacterCount(String left, String right) {
        java.util.Set<Character> seen = new java.util.HashSet<Character>();
        for (int i = 0; i < left.length(); i++) {
            seen.add(Character.valueOf(left.charAt(i)));
        }
        int common = 0;
        for (int i = 0; i < right.length(); i++) {
            if (seen.contains(Character.valueOf(right.charAt(i)))) {
                common++;
            }
        }
        return common;
    }

    /**
     * 执行basename相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回basename结果。
     */
    private String basename(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /**
     * 执行扩展名相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回extension结果。
     */
    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot);
    }

    /**
     * 执行duplicateRead结果相关逻辑。
     *
     * @param fileName 文件或目录路径参数。
     * @param key 配置键或映射键。
     * @param targetFile 文件或目录路径参数。
     * @return 返回duplicate Read结果。
     */
    private String duplicateReadResult(String fileName, ReadKey key, File targetFile) {
        resetDedupHitsAfterOtherToolCall();
        long modifiedAt = targetFile.lastModified();
        synchronized (readDedup) {
            ReadTracker tracker = readDedup.get(key);
            if (tracker == null || tracker.modifiedAt != modifiedAt) {
                return null;
            }
            tracker.dedupHits++;
            if (tracker.dedupHits >= 2) {
                return ToolResultEnvelope.error(
                                "BLOCKED: 已连续多次读取同一文件区域且文件未变化："
                                        + safeDisplayPath(fileName)
                                        + "。请停止重复调用 read_file，使用之前读取到的内容继续任务。")
                        .data("path", safeDisplayPath(fileName))
                        .data(
                                "resolved_path",
                                safeDisplayPath(resolvedOutputPath(targetFile.toPath())))
                        .data("already_read", Integer.valueOf(tracker.dedupHits + 1))
                        .toJson();
            }
            return ToolResultEnvelope.ok(READ_DEDUP_STATUS_MESSAGE)
                    .data("path", safeDisplayPath(fileName))
                    .data("resolved_path", safeDisplayPath(resolvedOutputPath(targetFile.toPath())))
                    .data("dedup", Boolean.TRUE)
                    .data("content_returned", Boolean.FALSE)
                    .data("offset", Integer.valueOf(key.offset))
                    .data("limit", Integer.valueOf(key.limit))
                    .toJson();
        }
    }

    /**
     * 执行assertNotInternal文件状态Content相关逻辑。
     *
     * @param content 待处理内容。
     */
    private void assertNotInternalFileStatusContent(String content) {
        if (!isInternalFileStatusText(content)) {
            return;
        }
        throw new IllegalArgumentException(
                "Refusing to write internal read_file status text as file content. Re-read the file or reconstruct the intended file contents before writing.");
    }

    /**
     * 判断是否Internal文件状态Text。
     *
     * @param content 待处理内容。
     * @return 如果Internal文件状态Text满足条件则返回 true，否则返回 false。
     */
    private boolean isInternalFileStatusText(String content) {
        String stripped = StrUtil.nullToEmpty(content).trim();
        if (stripped.length() == 0) {
            return false;
        }
        if (READ_DEDUP_STATUS_MESSAGE.equals(stripped)) {
            return true;
        }
        return stripped.contains(READ_DEDUP_STATUS_MESSAGE)
                && stripped.length() <= 2 * READ_DEDUP_STATUS_MESSAGE.length();
    }

    /**
     * 记录Read。
     *
     * @param fileName 文件或目录路径参数。
     * @param key 配置键或映射键。
     * @param targetFile 文件或目录路径参数。
     * @return 返回Read结果。
     */
    private ReadStatus recordRead(String fileName, ReadKey key, File targetFile) {
        resetDedupHitsAfterOtherToolCall();
        synchronized (readDedup) {
            ReadTracker tracker = readDedup.get(key);
            if (tracker == null) {
                tracker = new ReadTracker();
                readDedup.put(key, tracker);
            }
            tracker.modifiedAt = targetFile.lastModified();
            tracker.dedupHits = 0;
            if (key.equals(lastReadKey)) {
                consecutiveReadCount++;
            } else {
                lastReadKey = key;
                consecutiveReadCount = 1;
            }
            capReadDedup();
            if (consecutiveReadCount >= 4) {
                return ReadStatus.block(
                        "BLOCKED: 已连续 "
                                + consecutiveReadCount
                                + " 次读取同一文件区域且文件未变化："
                                + safeDisplayPath(fileName),
                        consecutiveReadCount);
            }
            if (consecutiveReadCount >= 3) {
                return ReadStatus.warn(
                        "你已经连续 " + consecutiveReadCount + " 次读取同一文件区域。内容未变化，请使用已有信息继续任务。",
                        consecutiveReadCount);
            }
            return ReadStatus.ok(consecutiveReadCount);
        }
    }

    /**
     * 清理Read Dedup。
     *
     * @param fileName 文件或目录路径参数。
     */
    private void clearReadDedup(String fileName) {
        if (StrUtil.isBlank(fileName)) {
            return;
        }
        try {
            Path target = resolvePath(fileName).toAbsolutePath().normalize();
            String targetPath = target.toString();
            synchronized (readDedup) {
                List<ReadKey> removed = new ArrayList<ReadKey>();
                for (ReadKey key : readDedup.keySet()) {
                    if (key.path.equals(targetPath)) {
                        removed.add(key);
                    }
                }
                for (ReadKey key : removed) {
                    readDedup.remove(key);
                }
                if (lastReadKey != null && lastReadKey.path.equals(targetPath)) {
                    lastReadKey = null;
                    consecutiveReadCount = 0;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 执行resetDedupHitsAfterOther工具Call相关逻辑。 */
    private void resetDedupHitsAfterOtherToolCall() {
        int currentEpoch = ToolCallLoopGuardrailService.otherToolCallEpoch();
        synchronized (readDedup) {
            if (observedOtherToolCallEpoch == currentEpoch) {
                return;
            }
            observedOtherToolCallEpoch = currentEpoch;
            for (ReadTracker tracker : readDedup.values()) {
                if (tracker != null) {
                    tracker.dedupHits = 0;
                }
            }
        }
    }

    /** 执行capReadDedup相关逻辑。 */
    private void capReadDedup() {
        while (readDedup.size() > 500) {
            ReadKey first = readDedup.keySet().iterator().next();
            readDedup.remove(first);
        }
    }

    /** 承载ReadTracker相关状态和辅助逻辑。 */
    private static final class ReadTracker {
        /** 记录ReadTracker中的modified时间。 */
        private long modifiedAt;

        /** 记录ReadTracker中的dedupHits。 */
        private int dedupHits;
    }

    /** 承载Read状态相关状态和辅助逻辑。 */
    private static final class ReadStatus {
        /** 是否启用阻断。 */
        private final boolean blocked;

        /** 记录Read状态中的消息。 */
        private final String message;

        /** 记录Read状态中的次数。 */
        private final int count;

        /**
         * 创建Read状态实例，并注入运行所需依赖。
         *
         * @param blocked 阻断参数。
         * @param message 平台消息或错误消息。
         * @param count count 参数。
         */
        private ReadStatus(boolean blocked, String message, int count) {
            this.blocked = blocked;
            this.message = message;
            this.count = count;
        }

        /**
         * 构造成功结果。
         *
         * @param count count 参数。
         * @return 返回ok结果。
         */
        private static ReadStatus ok(int count) {
            return new ReadStatus(false, null, count);
        }

        /**
         * 执行warn相关逻辑。
         *
         * @param message 平台消息或错误消息。
         * @param count count 参数。
         * @return 返回warn结果。
         */
        private static ReadStatus warn(String message, int count) {
            return new ReadStatus(false, message, count);
        }

        /**
         * 执行阻断相关逻辑。
         *
         * @param message 平台消息或错误消息。
         * @param count count 参数。
         * @return 返回block结果。
         */
        private static ReadStatus block(String message, int count) {
            return new ReadStatus(true, message, count);
        }
    }

    /** 承载Read键相关状态和辅助逻辑。 */
    private static final class ReadKey {
        /** 记录Read键中的路径。 */
        private final String path;

        /** 记录Read键中的offset。 */
        private final int offset;

        /** 记录Read键中的限制。 */
        private final int limit;

        /** 记录Read键中的max行Length。 */
        private final int maxLineLength;

        /**
         * 创建Read键实例，并注入运行所需依赖。
         *
         * @param path 文件或目录路径。
         * @param offset 分页偏移量。
         * @param limit 最大返回数量。
         * @param maxLineLength max行Length参数。
         */
        private ReadKey(String path, int offset, int limit, int maxLineLength) {
            this.path = path;
            this.offset = offset;
            this.limit = limit;
            this.maxLineLength = maxLineLength;
        }

        /**
         * 判断两个对象是否表示同一业务值。
         *
         * @param o o 参数。
         * @return 返回equals结果。
         */
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ReadKey)) {
                return false;
            }
            ReadKey other = (ReadKey) o;
            return offset == other.offset
                    && limit == other.limit
                    && maxLineLength == other.maxLineLength
                    && path.equals(other.path);
        }

        /**
         * 计算当前对象的哈希值。
         *
         * @return 返回hash Code结果。
         */
        @Override
        public int hashCode() {
            int result = path.hashCode();
            result = 31 * result + offset;
            result = 31 * result + limit;
            result = 31 * result + maxLineLength;
            return result;
        }
    }

    /** 承载Scored路径相关状态和辅助逻辑。 */
    private static final class ScoredPath {
        /** 记录Scored路径中的score。 */
        private final int score;

        /** 记录Scored路径中的路径。 */
        private final String path;

        /**
         * 创建Scored路径实例，并注入运行所需依赖。
         *
         * @param score score 参数。
         * @param path 文件或目录路径。
         */
        private ScoredPath(int score, String path) {
            this.score = score;
            this.path = path;
        }
    }

    /** 文件搜索状态。 */
    private static final class SearchCollector {
        /** 搜索模式。 */
        private final String pattern;

        /** 搜索目标。 */
        private final String target;

        /** 文件名过滤。 */
        private final String fileGlob;

        /** 输出模式。 */
        private final String outputMode;

        /** 最大返回条数。 */
        private final int limit;

        /** 跳过条数。 */
        private final int offset;

        /** 上下文行数。 */
        private final int context;

        /** 正则模式。 */
        private final Pattern regex;

        /** 保存匹配项。 */
        private final List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();

        /** 匹配总数。 */
        private int matched;

        /** 返回数量。 */
        private int returned;

        /** 是否截断。 */
        private boolean truncated;

        /**
         * 创建搜索状态。
         *
         * @param pattern 搜索模式。
         * @param target 搜索目标。
         * @param fileGlob 文件名过滤。
         * @param outputMode 输出模式。
         * @param limit 最大返回条数。
         * @param offset 跳过条数。
         * @param context 上下文行数。
         */
        private SearchCollector(
                String pattern,
                String target,
                String fileGlob,
                String outputMode,
                int limit,
                int offset,
                int context) {
            this.pattern = StrUtil.nullToEmpty(pattern);
            this.target = StrUtil.blankToDefault(target, "content").trim().toLowerCase(Locale.ROOT);
            this.fileGlob = StrUtil.nullToEmpty(fileGlob).trim().toLowerCase(Locale.ROOT);
            this.outputMode =
                    StrUtil.blankToDefault(outputMode, "content").trim().toLowerCase(Locale.ROOT);
            this.limit = limit;
            this.offset = offset;
            this.context = context;
            this.regex = compileRegex(this.pattern);
        }

        /**
         * 判断是否只搜索文件名。
         *
         * @return 如果只搜索文件名返回true。
         */
        private boolean searchFilesOnly() {
            return "files".equals(target) || "filename".equals(target) || "file".equals(target);
        }

        /**
         * 判断是否接受文件。
         *
         * @param path 文件路径。
         * @return 如果接受返回true。
         */
        private boolean acceptFile(Path path) {
            if (StrUtil.isBlank(fileGlob)) {
                return true;
            }
            String name =
                    path == null || path.getFileName() == null
                            ? ""
                            : path.getFileName().toString().toLowerCase(Locale.ROOT);
            return name.contains(fileGlob.replace("*", ""));
        }

        /**
         * 判断文本是否匹配。
         *
         * @param text 文本。
         * @return 如果匹配返回true。
         */
        private boolean matchesText(String text) {
            String value = StrUtil.nullToEmpty(text);
            if (regex != null) {
                return regex.matcher(value).find();
            }
            return value.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
        }

        /**
         * 追加匹配项。
         *
         * @param match 匹配项。
         */
        private void addMatch(Map<String, Object> match) {
            matched++;
            if (matched <= offset) {
                return;
            }
            if (returned >= limit) {
                truncated = true;
                return;
            }
            matches.add(match);
            returned++;
        }

        /**
         * 判断是否已收满。
         *
         * @return 如果收满返回true。
         */
        private boolean isFull() {
            return returned >= limit && truncated;
        }

        /**
         * 渲染匹配行。
         *
         * @param lines 文件行。
         * @param index 匹配行索引。
         * @param maxLineLength 最大行长度。
         * @return 返回渲染文本。
         */
        private String renderLine(List<String> lines, int index, int maxLineLength) {
            if (!"content".equals(outputMode) && !"context".equals(outputMode)) {
                return "";
            }
            if (context <= 0 || lines == null) {
                return trimLine(lines == null ? "" : lines.get(index), maxLineLength);
            }
            int start = Math.max(0, index - context);
            int end = Math.min(lines.size() - 1, index + context);
            StringBuilder builder = new StringBuilder();
            for (int i = start; i <= end; i++) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(i + 1).append('|').append(trimLine(lines.get(i), maxLineLength));
            }
            return builder.toString();
        }

        /**
         * 编译正则。
         *
         * @param pattern 搜索模式。
         * @return 返回正则，普通文本则返回null。
         */
        private static Pattern compileRegex(String pattern) {
            String value = StrUtil.nullToEmpty(pattern);
            if (!(value.startsWith("/") && value.endsWith("/") && value.length() > 2)) {
                return null;
            }
            try {
                return Pattern.compile(value.substring(1, value.length() - 1));
            } catch (PatternSyntaxException ignored) {
                return null;
            }
        }

        /**
         * 裁剪单行文本。
         *
         * @param line 行内容。
         * @param maxLineLength 最大长度。
         * @return 返回裁剪结果。
         */
        private static String trimLine(String line, int maxLineLength) {
            String value = StrUtil.nullToEmpty(line);
            int max = Math.max(1, maxLineLength);
            return value.length() > max ? value.substring(0, max) + "... [truncated]" : value;
        }
    }
}
