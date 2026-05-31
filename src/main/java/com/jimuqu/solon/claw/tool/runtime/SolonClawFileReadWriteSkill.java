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
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.skills.file.FileReadWriteSkill;

/** Solon AI file skill wrapped with Jimuqu path and credential guardrails. */
public class SolonClawFileReadWriteSkill extends FileReadWriteSkill {
    private static final int DEFAULT_READ_OFFSET = 1;
    private static final int DEFAULT_READ_LIMIT = 500;
    private static final String UTF8_BOM = "\ufeff";
    private static final String READ_DEDUP_STATUS_MESSAGE =
            "文件未变化：这一段内容已经读取过，本次不再重复返回正文。请使用之前的 file_read 结果继续任务。";

    private final Path rootPath;
    private final Path realRootPath;
    private final SecurityPolicyService securityPolicyService;
    private final IntSupplier maxLinesSupplier;
    private final IntSupplier maxLineLengthSupplier;
    private final SolonClawFileStateTracker fileStateTracker;
    private final Map<ReadKey, ReadTracker> readDedup = new LinkedHashMap<ReadKey, ReadTracker>();
    private ReadKey lastReadKey;
    private int consecutiveReadCount;
    private int observedOtherToolCallEpoch;

    public SolonClawFileReadWriteSkill(String workDir, SecurityPolicyService securityPolicyService) {
        this(workDir, securityPolicyService, 2000, 2000, new SolonClawFileStateTracker());
    }

    public SolonClawFileReadWriteSkill(
            String workDir,
            SecurityPolicyService securityPolicyService,
            AppConfig appConfig) {
        this(workDir, securityPolicyService, appConfig, new SolonClawFileStateTracker());
    }

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

    public SolonClawFileReadWriteSkill(
            String workDir,
            SecurityPolicyService securityPolicyService,
            int maxLines,
            int maxLineLength) {
        this(workDir, securityPolicyService, maxLines, maxLineLength, new SolonClawFileStateTracker());
    }

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
        this.fileStateTracker = fileStateTracker == null ? new SolonClawFileStateTracker() : fileStateTracker;
    }

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
                    .data("files_modified", Collections.singletonList(safeDisplayPath(resolvedPath)));
        }
        if (StrUtil.isNotBlank(staleWarning)) {
            return envelope.data("_warning", safeDisplayPath(staleWarning))
                    .toJson();
        }
        return envelope.toJson();
    }

    public String read(@Param("fileName") String fileName) {
        return read(fileName, null, null);
    }

    @ToolMapping(
            name = "file_read",
            description =
                    "读取文本文件内容。返回带行号的 JSON 结果；offset 从 1 开始，limit 默认 500，并受 tool_output.max_lines 限制。")
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
                            description = "最多读取多少行，会按 tool_output.max_lines 截断。")
                    Integer limit) {
        assertSafe(ToolNameConstants.FILE_READ, fileName);
        return readPaged(fileName, offset, limit);
    }

    @Override
    @ToolMapping(name = "file_list", description = "列出指定目录下的文件和子目录。如果不指定目录，则列出根目录。")
    public String list(@Param(value = "dirName", required = false) String dirName) {
        assertSafe(ToolNameConstants.FILE_LIST, dirName);
        assertContained(dirName);
        return SecretRedactor.redact(super.list(dirName), 20000);
    }

    @Override
    @ToolMapping(name = "file_delete", description = "删除指定文件或空目录。")
    public String delete(@Param("fileName") String fileName) {
        assertSafe(ToolNameConstants.FILE_DELETE, fileName);
        assertContained(fileName);
        String result = super.delete(fileName);
        clearReadDedup(fileName);
        return SecretRedactor.redact(result, 1000);
    }

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

    private String blockedMessage(String toolName, SecurityPolicyService.FileVerdict verdict) {
        return "BLOCKED: 文件安全策略阻止访问："
                + verdict.getMessage()
                + "\n工具："
                + toolName
                + "\n路径："
                + SecretRedactor.redact(verdict.getPath(), 400)
                + "\n请改用工作区内的普通项目文件，敏感凭据文件不能通过 Agent 工具读取、写入或删除。";
    }

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
            return ToolResultEnvelope.error(
                            "读取失败：'" + displayPath + "' 是一个目录。请使用 file_list 查看其内容。")
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

    private String safeDisplayPath(String path) {
        return SecretRedactor.redact(path, 400);
    }

    private String safeToolError(Exception e) {
        String message = e == null ? "" : e.getMessage();
        if (StrUtil.isBlank(message) && e != null) {
            message = e.getClass().getSimpleName();
        }
        return SecretRedactor.redact(message, 1000);
    }

    private String numberedLine(int lineNumber, String line, int maxLineLength) {
        String value = StrUtil.nullToEmpty(line);
        if (value.length() > maxLineLength) {
            value = value.substring(0, maxLineLength) + "... [truncated]";
        }
        return lineNumber + "|" + value;
    }

    private int resolveMaxLines() {
        return resolvePositiveLimit(maxLinesSupplier, 2000);
    }

    private int resolveMaxLineLength() {
        return resolvePositiveLimit(maxLineLengthSupplier, 2000);
    }

    private static int resolvePositiveLimit(IntSupplier supplier, int fallback) {
        try {
            return Math.max(1, supplier == null ? fallback : supplier.getAsInt());
        } catch (Exception ignored) {
            return Math.max(1, fallback);
        }
    }

    private static IntSupplier fixedLimit(final int value) {
        final int safeValue = Math.max(1, value);
        return new IntSupplier() {
            @Override
            public int getAsInt() {
                return safeValue;
            }
        };
    }

    private static IntSupplier appConfigMaxLines(final AppConfig appConfig) {
        return new IntSupplier() {
            @Override
            public int getAsInt() {
                return appConfig == null || appConfig.getTask() == null
                        ? 2000
                        : appConfig.getTask().getToolOutputMaxLines();
            }
        };
    }

    private static IntSupplier appConfigMaxLineLength(final AppConfig appConfig) {
        return new IntSupplier() {
            @Override
            public int getAsInt() {
                return appConfig == null || appConfig.getTask() == null
                        ? 2000
                        : appConfig.getTask().getToolOutputMaxLineLength();
            }
        };
    }

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

    private String stripLeadingBom(String value) {
        if (value != null && value.startsWith(UTF8_BOM)) {
            return value.substring(UTF8_BOM.length());
        }
        return value;
    }

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

    private void assertContained(String name) {
        if (StrUtil.isBlank(name)) {
            return;
        }
        resolvePath(name);
    }

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

    private Path safeRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private String resolvedOutputPath(Path path) {
        return safeRealPath(path).toString();
    }

    private List<String> similarFiles(String requestedPath, Path target) {
        Path dir = target == null ? null : target.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        Path fileNamePath = target.getFileName();
        String requestedName = fileNamePath == null ? StrUtil.nullToEmpty(requestedPath) : fileNamePath.toString();
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
                String candidateName = candidate.getFileName() == null ? "" : candidate.getFileName().toString();
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

    private boolean allowedSuggestion(Path candidate) {
        if (securityPolicyService == null) {
            return true;
        }
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", displayPathForCandidate(candidate));
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs(ToolNameConstants.FILE_READ, args);
        return verdict.isAllowed();
    }

    private String displayPathForCandidate(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (normalized.startsWith(rootPath)) {
            return rootPath.relativize(normalized).toString();
        }
        return normalized.toString();
    }

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

    private String basename(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot);
    }

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
                                        + "。请停止重复调用 file_read，使用之前读取到的内容继续任务。")
                        .data("path", safeDisplayPath(fileName))
                        .data("resolved_path", safeDisplayPath(resolvedOutputPath(targetFile.toPath())))
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

    private void assertNotInternalFileStatusContent(String content) {
        if (!isInternalFileStatusText(content)) {
            return;
        }
        throw new IllegalArgumentException(
                "Refusing to write internal read_file status text as file content. Re-read the file or reconstruct the intended file contents before writing.");
    }

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
                        "你已经连续 "
                                + consecutiveReadCount
                                + " 次读取同一文件区域。内容未变化，请使用已有信息继续任务。",
                        consecutiveReadCount);
            }
            return ReadStatus.ok(consecutiveReadCount);
        }
    }

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

    private void capReadDedup() {
        while (readDedup.size() > 500) {
            ReadKey first = readDedup.keySet().iterator().next();
            readDedup.remove(first);
        }
    }

    private static final class ReadTracker {
        private long modifiedAt;
        private int dedupHits;
    }

    private static final class ReadStatus {
        private final boolean blocked;
        private final String message;
        private final int count;

        private ReadStatus(boolean blocked, String message, int count) {
            this.blocked = blocked;
            this.message = message;
            this.count = count;
        }

        private static ReadStatus ok(int count) {
            return new ReadStatus(false, null, count);
        }

        private static ReadStatus warn(String message, int count) {
            return new ReadStatus(false, message, count);
        }

        private static ReadStatus block(String message, int count) {
            return new ReadStatus(true, message, count);
        }
    }

    private static final class ReadKey {
        private final String path;
        private final int offset;
        private final int limit;
        private final int maxLineLength;

        private ReadKey(String path, int offset, int limit, int maxLineLength) {
            this.path = path;
            this.offset = offset;
            this.limit = limit;
            this.maxLineLength = maxLineLength;
        }

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

        @Override
        public int hashCode() {
            int result = path.hashCode();
            result = 31 * result + offset;
            result = 31 * result + limit;
            result = 31 * result + maxLineLength;
            return result;
        }
    }

    private static final class ScoredPath {
        private final int score;
        private final String path;

        private ScoredPath(int score, String path) {
            this.score = score;
            this.path = path;
        }
    }
}
