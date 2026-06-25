package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供Solon项目Patch工具能力，供 Agent 运行时按安全策略调用。 */
public class SolonClawPatchTools {
    /** 记录补丁工具中的可降级异常，日志不输出路径、补丁正文或文件内容。 */
    private static final Logger log = LoggerFactory.getLogger(SolonClawPatchTools.class);

    /** UTF8BOM的统一常量值。 */
    private static final String UTF8_BOM = "\ufeff";

    /** 记录Solon项目补丁中的根用户路径。 */
    private final Path rootPath;

    /** 记录Solon项目补丁中的real根用户路径。 */
    private final Path realRootPath;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 记录Solon项目补丁中的文件状态Tracker。 */
    private final SolonClawFileStateTracker fileStateTracker;

    /**
     * 创建Solon项目Patch工具实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     */
    public SolonClawPatchTools(String workDir) {
        this(workDir, null);
    }

    /**
     * 创建Solon项目Patch工具实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public SolonClawPatchTools(String workDir, SecurityPolicyService securityPolicyService) {
        this(workDir, securityPolicyService, new SolonClawFileStateTracker());
    }

    /**
     * 创建Solon项目Patch工具实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param securityPolicyService 安全策略服务依赖。
     * @param fileStateTracker 文件或目录路径参数。
     */
    public SolonClawPatchTools(
            String workDir,
            SecurityPolicyService securityPolicyService,
            SolonClawFileStateTracker fileStateTracker) {
        String dir = StrUtil.blankToDefault(workDir, ".");
        this.rootPath = Paths.get(dir).toAbsolutePath().normalize();
        this.realRootPath = safeRealPath(this.rootPath);
        this.securityPolicyService = securityPolicyService;
        this.fileStateTracker =
                fileStateTracker == null ? new SolonClawFileStateTracker() : fileStateTracker;
        try {
            Files.createDirectories(this.rootPath);
        } catch (IOException e) {
            logRecoverableFailure("create-root-directory", e);
        }
    }

    /**
     * 执行补丁Parser策略摘要相关逻辑。
     *
     * @return 返回patch Parser策略Summary结果。
     */
    public static Map<String, Object> patchParserPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("enabled", Boolean.TRUE);
        summary.put("toolName", "patch");
        summary.put("modes", Arrays.asList("replace", "patch"));
        summary.put("patchFormat", "V4A");
        summary.put("beginEndMarkersRequired", Boolean.TRUE);
        summary.put("operations", Arrays.asList("update", "add", "delete", "move", "moveTo"));
        summary.put("atomicValidationBeforeWrite", Boolean.TRUE);
        summary.put("noPartialWritesOnValidationFailure", Boolean.TRUE);
        summary.put("replaceRequiresUniqueMatchByDefault", Boolean.TRUE);
        summary.put("replaceAllRequiresExplicitFlag", Boolean.TRUE);
        summary.put("additionOnlyContextHintsSupported", Boolean.TRUE);
        summary.put("ambiguousHunksBlocked", Boolean.TRUE);
        summary.put("missingHunksBlocked", Boolean.TRUE);
        summary.put("addWillNotOverwriteExistingFile", Boolean.TRUE);
        summary.put("moveWillNotOverwriteDestination", Boolean.TRUE);
        summary.put("deleteRequiresExistingFile", Boolean.TRUE);
        summary.put("pathTraversalBlocked", Boolean.TRUE);
        summary.put("nulPathBlocked", Boolean.TRUE);
        summary.put("jarInternalPathBlocked", Boolean.TRUE);
        summary.put("symlinkEscapeBlocked", Boolean.TRUE);
        summary.put("credentialPolicyPrechecked", Boolean.TRUE);
        summary.put("moveDestinationPolicyChecked", Boolean.TRUE);
        summary.put("errorsRedacted", Boolean.TRUE);
        summary.put("staleFileWarnings", Boolean.TRUE);
        summary.put("diffReturned", Boolean.TRUE);
        return summary;
    }

    /**
     * 执行补丁相关逻辑。
     *
     * @param mode 模式参数。
     * @param path 文件或目录路径。
     * @param oldString old字符串参数。
     * @param newString new字符串参数。
     * @param replaceAll replaceAll 参数。
     * @param patchText 补丁文本参数。
     * @return 返回patch结果。
     */
    @ToolMapping(
            name = "patch",
            description =
                    "Targeted file edits. mode=replace does unique old_string/new_string replacement. mode=patch applies V4A multi-file patches using *** Begin Patch blocks.")
    public synchronized String patch(
            @Param(name = "mode", description = "replace or patch", required = false) String mode,
            @Param(name = "path", description = "File path for replace mode", required = false)
                    String path,
            @Param(name = "old_string", description = "Text to find", required = false)
                    String oldString,
            @Param(name = "new_string", description = "Replacement text", required = false)
                    String newString,
            @Param(
                            name = "replace_all",
                            description = "Replace all matches instead of requiring a unique match",
                            required = false)
                    Boolean replaceAll,
            @Param(name = "patch", description = "V4A patch text for patch mode", required = false)
                    String patchText) {
        String selectedMode = StrUtil.blankToDefault(mode, "replace").trim().toLowerCase();
        PatchResult result;
        try {
            if ("replace".equals(selectedMode)) {
                result =
                        replace(
                                path,
                                oldString,
                                StrUtil.nullToEmpty(newString),
                                Boolean.TRUE.equals(replaceAll));
            } else if ("patch".equals(selectedMode)) {
                result = applyV4a(patchText);
            } else {
                result = PatchResult.error("Unknown mode: " + mode);
            }
        } catch (Exception e) {
            result = PatchResult.error(e.getMessage());
        }
        result.redactOutput();
        return ONode.serialize(result);
    }

    /**
     * 执行replace相关逻辑。
     *
     * @param filePath 目标文件相对路径或绝对路径。
     * @param oldString old字符串参数。
     * @param newString new字符串参数。
     * @param replaceAll replaceAll 参数。
     * @return 返回replace结果。
     */
    private PatchResult replace(
            String filePath, String oldString, String newString, boolean replaceAll)
            throws IOException {
        if (StrUtil.isBlank(filePath)) {
            return PatchResult.error("path required");
        }
        if (oldString == null || newString == null) {
            return PatchResult.error("old_string and new_string required");
        }
        if (oldString.length() == 0) {
            return PatchResult.error("old_string cannot be empty");
        }
        if (oldString.equals(newString)) {
            return PatchResult.error("old_string and new_string are identical");
        }

        PatchResult policy = checkPolicy(filePath);
        if (policy != null) {
            return policy;
        }
        Path target = resolvePath(filePath);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            return PatchResult.error("Cannot read file: " + safePath(filePath));
        }
        String staleWarning = fileStateTracker.checkStaleness(filePath, target);
        String content = read(target);
        List<Integer> matches = findMatches(content, oldString);
        if (matches.isEmpty()) {
            return PatchResult.error(
                    "Could not find a match for old_string in the file"
                            + closestHint(oldString, content));
        }
        if (matches.size() > 1 && !replaceAll) {
            return PatchResult.error(
                    "Found "
                            + matches.size()
                            + " matches for old_string. Provide more context to make it unique, or use replace_all=true.");
        }

        String updated = replaceMatches(content, oldString, newString, matches);
        write(target, updated);
        fileStateTracker.recordWrite(target);

        PatchResult result = PatchResult.success();
        String resolvedPath = resolvedOutputPath(target);
        result.resolved_path = resolvedPath;
        result.filesModified.add(resolvedPath);
        result.diff = simpleDiff(normalizePath(filePath), content, updated);
        result.addWarning(staleWarning);
        return result;
    }

    /**
     * 应用V4a。
     *
     * @param patchText 补丁文本参数。
     * @return 返回apply V4a结果。
     */
    private PatchResult applyV4a(String patchText) throws IOException {
        if (StrUtil.isBlank(patchText)) {
            return PatchResult.error("patch content required");
        }
        if (!hasPatchEnvelope(patchText)) {
            return PatchResult.error(
                    "patch rejected: missing *** Begin Patch or *** End Patch boundary");
        }
        List<PatchOperation> operations = parseV4a(patchText);
        if (operations.isEmpty()) {
            return PatchResult.error("patch rejected: empty patch");
        }
        PatchResult policy = checkPolicy(operations);
        if (policy != null) {
            return policy;
        }

        List<String> validationErrors = validateOperations(operations);
        if (!validationErrors.isEmpty()) {
            return PatchResult.error(
                    "Patch validation failed (no files were modified):\n  "
                            + join(validationErrors, "\n  "));
        }

        PatchResult result = PatchResult.success();
        StringBuilder diff = new StringBuilder();
        for (PatchOperation operation : operations) {
            applyOperation(operation, result, diff);
        }
        result.diff = diff.toString();
        return result;
    }

    /**
     * 判断是否存在Patch Envelope。
     *
     * @param patchText 补丁文本参数。
     * @return 如果Patch Envelope满足条件则返回 true，否则返回 false。
     */
    private boolean hasPatchEnvelope(String patchText) {
        String normalized = patchText.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        boolean begin = false;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if ("*** Begin Patch".equals(line) || "***Begin Patch".equals(line)) {
                begin = true;
            } else if (begin && ("*** End Patch".equals(line) || "***End Patch".equals(line))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析V4a。
     *
     * @param patchText 补丁文本参数。
     * @return 返回解析后的V4a。
     */
    private List<PatchOperation> parseV4a(String patchText) {
        String normalized = patchText.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int start = -1;
        int end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if ("*** Begin Patch".equals(line) || "***Begin Patch".equals(line)) {
                start = i;
            } else if ("*** End Patch".equals(line) || "***End Patch".equals(line)) {
                end = i;
                break;
            }
        }

        List<PatchOperation> operations = new ArrayList<PatchOperation>();
        PatchOperation current = null;
        Hunk currentHunk = null;
        for (int i = start + 1; i < end; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("*** Update File:")) {
                finishOperation(operations, current, currentHunk);
                current = new PatchOperation("update", afterMarker(trimmed, "*** Update File:"));
                currentHunk = null;
            } else if (trimmed.startsWith("*** Add File:")) {
                finishOperation(operations, current, currentHunk);
                current = new PatchOperation("add", afterMarker(trimmed, "*** Add File:"));
                currentHunk = new Hunk(null);
            } else if (trimmed.startsWith("*** Delete File:")) {
                finishOperation(operations, current, currentHunk);
                operations.add(
                        new PatchOperation("delete", afterMarker(trimmed, "*** Delete File:")));
                current = null;
                currentHunk = null;
            } else if (trimmed.startsWith("*** Move File:")) {
                finishOperation(operations, current, currentHunk);
                PatchOperation move = parseMoveFile(trimmed);
                operations.add(move);
                current = null;
                currentHunk = null;
            } else if (trimmed.startsWith("*** Move to:") && current != null) {
                current.newPath = afterMarker(trimmed, "*** Move to:");
            } else if (line.startsWith("@@")) {
                if (current != null) {
                    if (currentHunk != null && !currentHunk.lines.isEmpty()) {
                        current.hunks.add(currentHunk);
                    }
                    currentHunk = new Hunk(extractContextHint(line));
                }
            } else if (current != null && line.length() > 0) {
                if (currentHunk == null) {
                    currentHunk = new Hunk(null);
                }
                if (line.startsWith("+")) {
                    currentHunk.lines.add(new HunkLine('+', line.substring(1)));
                } else if (line.startsWith("-")) {
                    currentHunk.lines.add(new HunkLine('-', line.substring(1)));
                } else if (line.startsWith(" ")) {
                    currentHunk.lines.add(new HunkLine(' ', line.substring(1)));
                } else if (!line.startsWith("\\")) {
                    currentHunk.lines.add(new HunkLine(' ', line));
                }
            }
        }
        finishOperation(operations, current, currentHunk);
        return operations;
    }

    /**
     * 执行finishOperation相关逻辑。
     *
     * @param operations operations 参数。
     * @param current current 参数。
     * @param hunk hunk 参数。
     */
    private void finishOperation(
            List<PatchOperation> operations, PatchOperation current, Hunk hunk) {
        if (current == null) {
            return;
        }
        if (hunk != null && !hunk.lines.isEmpty()) {
            current.hunks.add(hunk);
        }
        operations.add(current);
    }

    /**
     * 解析Move文件。
     *
     * @param line 行参数。
     * @return 返回解析后的Move文件。
     */
    private PatchOperation parseMoveFile(String line) {
        String body = afterMarker(line, "*** Move File:");
        int arrow = body.indexOf("->");
        if (arrow < 0) {
            PatchOperation op = new PatchOperation("move", body);
            op.error = "MOVE " + body + ": missing destination path (expected 'src -> dst')";
            return op;
        }
        PatchOperation op = new PatchOperation("move", body.substring(0, arrow).trim());
        op.newPath = body.substring(arrow + 2).trim();
        return op;
    }

    /**
     * 校验Operations。
     *
     * @param operations operations 参数。
     * @return 返回Operations结果。
     */
    private List<String> validateOperations(List<PatchOperation> operations) throws IOException {
        List<String> errors = new ArrayList<String>();
        for (PatchOperation operation : operations) {
            if (StrUtil.isBlank(operation.filePath)) {
                errors.add("Operation with empty file path");
                continue;
            }
            if (StrUtil.isNotBlank(operation.error)) {
                errors.add(operation.error);
                continue;
            }
            if ("update".equals(operation.type)) {
                if (operation.hunks.isEmpty()) {
                    errors.add("UPDATE " + safePath(operation.filePath) + ": no hunks found");
                    continue;
                }
                Path target = resolvePath(operation.filePath);
                if (!Files.exists(target) || Files.isDirectory(target)) {
                    errors.add(safePath(operation.filePath) + ": file not found");
                    continue;
                }
                String simulated = read(target);
                ApplyResult applied = applyHunks(simulated, operation.hunks);
                if (!applied.success) {
                    errors.add(safePath(operation.filePath) + ": " + applied.error);
                } else {
                    Path writeTarget =
                            StrUtil.isBlank(operation.newPath)
                                    ? target
                                    : resolvePath(operation.newPath);
                    addPlaceholderSecretDowngradeError(
                            errors, writeTarget, simulated, applied.content);
                }
                if (StrUtil.isNotBlank(operation.newPath)) {
                    Path destination = resolvePath(operation.newPath);
                    if (Files.exists(destination)) {
                        errors.add(
                                safePath(operation.newPath)
                                        + ": destination already exists - move would overwrite");
                    }
                }
            } else if ("delete".equals(operation.type)) {
                Path target = resolvePath(operation.filePath);
                if (!Files.exists(target) || Files.isDirectory(target)) {
                    errors.add(safePath(operation.filePath) + ": file not found for deletion");
                }
            } else if ("move".equals(operation.type)) {
                Path source = resolvePath(operation.filePath);
                Path destination = resolvePath(operation.newPath);
                if (!Files.exists(source) || Files.isDirectory(source)) {
                    errors.add(safePath(operation.filePath) + ": source file not found for move");
                }
                if (Files.exists(destination)) {
                    errors.add(
                            safePath(operation.newPath)
                                    + ": destination already exists - move would overwrite");
                }
            } else if ("add".equals(operation.type)) {
                Path target = resolvePath(operation.filePath);
                if (Files.exists(target)) {
                    errors.add(
                            safePath(operation.filePath)
                                    + ": file already exists - add would overwrite");
                } else {
                    addPlaceholderSecretDowngradeError(
                            errors, target, "", collectAddContent(operation));
                }
            }
        }
        return errors;
    }

    /**
     * 在 V4A 预验证阶段执行密钥占位符降级检查，避免后续多文件写入出现部分成功。
     *
     * @param errors 验证错误列表。
     * @param target 目标文件。
     * @param oldContent 原始内容。
     * @param newContent 预期写入内容。
     */
    private void addPlaceholderSecretDowngradeError(
            List<String> errors, Path target, String oldContent, String newContent) {
        try {
            ConfigSecretWriteGuard.assertNoPlaceholderSecretDowngrade(
                    target, oldContent, newContent);
        } catch (IllegalArgumentException e) {
            errors.add(SecretRedactor.redact(e.getMessage(), 1000));
        }
    }

    /**
     * 检查策略。
     *
     * @param operations operations 参数。
     * @return 返回策略结果。
     */
    private PatchResult checkPolicy(List<PatchOperation> operations) {
        if (securityPolicyService == null) {
            return null;
        }
        for (PatchOperation operation : operations) {
            PatchResult result = checkPolicy(operation.filePath);
            if (result != null) {
                return result;
            }
            if (StrUtil.isNotBlank(operation.newPath)) {
                result = checkPolicy(operation.newPath);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * 检查策略。
     *
     * @param filePath 目标文件相对路径或绝对路径。
     * @return 返回策略结果。
     */
    private PatchResult checkPolicy(String filePath) {
        if (securityPolicyService == null) {
            return null;
        }
        SecurityPolicyService.FileVerdict verdict = securityPolicyService.checkPath(filePath, true);
        if (verdict.isAllowed()) {
            return null;
        }
        if (verdict.isApprovalRequired()) {
            return PatchResult.error(
                    "APPROVAL_REQUIRED: "
                            + verdict.getMessage()
                            + " path="
                            + redactPath(verdict.getPath(), 400)
                            + "。请先在对话审批该单次操作。");
        }
        return PatchResult.error(
                "BLOCKED: 文件安全策略阻止访问："
                        + verdict.getMessage()
                        + " path="
                        + redactPath(verdict.getPath(), 400));
    }

    /**
     * 脱敏补丁安全拒绝消息中的敏感路径。
     *
     * @param path 原始路径。
     * @param maxLength 最大展示长度。
     * @return 返回脱敏后的路径。
     */
    private static String redactPath(String path, int maxLength) {
        return SecretRedactor.redactSensitivePaths(SecretRedactor.redact(path, maxLength));
    }

    /**
     * 应用Operation。
     *
     * @param operation operation 参数。
     * @param result 结果响应或执行结果。
     * @param diff diff 参数。
     */
    private void applyOperation(PatchOperation operation, PatchResult result, StringBuilder diff)
            throws IOException {
        Path target = resolvePath(operation.filePath);
        if ("add".equals(operation.type)) {
            String content = collectAddContent(operation);
            write(target, content);
            fileStateTracker.recordWrite(target);
            String resolvedPath = resolvedOutputPath(target);
            result.filesCreated.add(resolvedPath);
            result.setSingleResolvedPath(resolvedPath);
            diff.append(simpleDiff(normalizePath(operation.filePath), "", content));
        } else if ("delete".equals(operation.type)) {
            result.addWarning(fileStateTracker.checkStaleness(operation.filePath, target));
            String old = read(target);
            String resolvedPath = resolvedOutputPath(target);
            Files.delete(target);
            fileStateTracker.recordWrite(target);
            result.filesDeleted.add(resolvedPath);
            result.setSingleResolvedPath(resolvedPath);
            diff.append(simpleDiff(normalizePath(operation.filePath), old, ""));
        } else if ("move".equals(operation.type)) {
            result.addWarning(fileStateTracker.checkStaleness(operation.filePath, target));
            Path destination = resolvePath(operation.newPath);
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            Files.move(target, destination, StandardCopyOption.ATOMIC_MOVE);
            fileStateTracker.recordWrite(target);
            fileStateTracker.recordWrite(destination);
            String resolvedSource = resolvedOutputPath(target);
            String resolvedDestination = resolvedOutputPath(destination);
            result.filesModified.add(resolvedSource + " -> " + resolvedDestination);
            diff.append("# Moved: ")
                    .append(normalizePath(operation.filePath))
                    .append(" -> ")
                    .append(normalizePath(operation.newPath))
                    .append('\n');
        } else if ("update".equals(operation.type)) {
            result.addWarning(fileStateTracker.checkStaleness(operation.filePath, target));
            String old = read(target);
            ApplyResult applied = applyHunks(old, operation.hunks);
            if (!applied.success) {
                throw new IOException(applied.error);
            }
            if (StrUtil.isNotBlank(operation.newPath)) {
                Path destination = resolvePath(operation.newPath);
                write(destination, applied.content);
                Files.delete(target);
                fileStateTracker.recordWrite(target);
                fileStateTracker.recordWrite(destination);
                String resolvedSource = resolvedOutputPath(target);
                String resolvedDestination = resolvedOutputPath(destination);
                result.filesModified.add(resolvedSource + " -> " + resolvedDestination);
                diff.append(simpleDiff(normalizePath(operation.newPath), old, applied.content));
            } else {
                write(target, applied.content);
                fileStateTracker.recordWrite(target);
                String resolvedPath = resolvedOutputPath(target);
                result.filesModified.add(resolvedPath);
                result.setSingleResolvedPath(resolvedPath);
                diff.append(simpleDiff(normalizePath(operation.filePath), old, applied.content));
            }
        }
    }

    /**
     * 应用Hunks。
     *
     * @param current current 参数。
     * @param hunks hunks 参数。
     * @return 返回apply Hunks结果。
     */
    private ApplyResult applyHunks(String current, List<Hunk> hunks) {
        String content = current;
        for (Hunk hunk : hunks) {
            List<String> searchLines = new ArrayList<String>();
            List<String> replacementLines = new ArrayList<String>();
            for (HunkLine line : hunk.lines) {
                if (line.prefix == ' ' || line.prefix == '-') {
                    searchLines.add(line.content);
                }
                if (line.prefix == ' ' || line.prefix == '+') {
                    replacementLines.add(line.content);
                }
            }
            String replacement = join(replacementLines, "\n");
            if (searchLines.isEmpty()) {
                ApplyResult inserted = insertAdditionOnly(content, hunk.contextHint, replacement);
                if (!inserted.success) {
                    return inserted;
                }
                content = inserted.content;
                continue;
            }
            String search = join(searchLines, "\n");
            int first = content.indexOf(search);
            if (first < 0) {
                return ApplyResult.error("hunk " + hintLabel(hunk) + " not found");
            }
            int second = content.indexOf(search, first + Math.max(1, search.length()));
            if (second >= 0) {
                return ApplyResult.error("hunk " + hintLabel(hunk) + " is ambiguous");
            }
            content =
                    content.substring(0, first)
                            + replacement
                            + content.substring(first + search.length());
        }
        return ApplyResult.success(content);
    }

    /**
     * 写入AdditionOnly。
     *
     * @param content 待处理内容。
     * @param hint hint 参数。
     * @param insertText insert文本参数。
     * @return 返回insert Addition Only结果。
     */
    private ApplyResult insertAdditionOnly(String content, String hint, String insertText) {
        if (StrUtil.isBlank(insertText)) {
            return ApplyResult.success(content);
        }
        if (StrUtil.isBlank(hint)) {
            return ApplyResult.success(appendWithNewline(content, insertText));
        }
        int occurrences = countOccurrences(content, hint);
        if (occurrences == 0) {
            return ApplyResult.error("addition-only hunk context hint '" + hint + "' not found");
        }
        if (occurrences > 1) {
            return ApplyResult.error(
                    "addition-only hunk context hint '"
                            + hint
                            + "' is ambiguous ("
                            + occurrences
                            + " occurrences)");
        }
        int position = content.indexOf(hint);
        int nextLine = content.indexOf('\n', position);
        if (nextLine < 0) {
            return ApplyResult.success(content + "\n" + insertText);
        }
        return ApplyResult.success(
                content.substring(0, nextLine + 1)
                        + insertText
                        + "\n"
                        + content.substring(nextLine + 1));
    }

    /**
     * 执行次数Occurrences相关逻辑。
     *
     * @param content 待处理内容。
     * @param needle needle 参数。
     * @return 返回次数Occurrences结果。
     */
    private int countOccurrences(String content, String needle) {
        if (StrUtil.isEmpty(content) || StrUtil.isEmpty(needle)) {
            return 0;
        }
        int count = 0;
        int index = content.indexOf(needle);
        while (index >= 0) {
            count++;
            index = content.indexOf(needle, index + 1);
        }
        return count;
    }

    /**
     * 追加With Newline。
     *
     * @param content 待处理内容。
     * @param insertText insert文本参数。
     * @return 返回With Newline结果。
     */
    private String appendWithNewline(String content, String insertText) {
        String base = StrUtil.nullToEmpty(content);
        if (base.endsWith("\n")) {
            return base + insertText + "\n";
        }
        return base + "\n" + insertText + "\n";
    }

    /**
     * 收集Add Content。
     *
     * @param operation operation 参数。
     * @return 返回Add Content结果。
     */
    private String collectAddContent(PatchOperation operation) {
        List<String> lines = new ArrayList<String>();
        for (Hunk hunk : operation.hunks) {
            for (HunkLine line : hunk.lines) {
                if (line.prefix == '+') {
                    lines.add(line.content);
                }
            }
        }
        return join(lines, "\n");
    }

    /**
     * 查找Matches。
     *
     * @param content 待处理内容。
     * @param needle needle 参数。
     * @return 返回Matches结果。
     */
    private List<Integer> findMatches(String content, String needle) {
        if (StrUtil.isEmpty(needle)) {
            return Collections.emptyList();
        }
        List<Integer> matches = new ArrayList<Integer>();
        int index = content.indexOf(needle);
        while (index >= 0) {
            matches.add(index);
            index = content.indexOf(needle, index + 1);
        }
        return matches;
    }

    /**
     * 执行replaceMatches相关逻辑。
     *
     * @param content 待处理内容。
     * @param oldString old字符串参数。
     * @param newString new字符串参数。
     * @param matches matches 参数。
     * @return 返回replace Matches结果。
     */
    private String replaceMatches(
            String content, String oldString, String newString, List<Integer> matches) {
        String updated = content;
        List<Integer> sorted = new ArrayList<Integer>(matches);
        Collections.sort(sorted, Collections.reverseOrder());
        for (Integer index : sorted) {
            updated =
                    updated.substring(0, index)
                            + newString
                            + updated.substring(index + oldString.length());
        }
        return updated;
    }

    /**
     * 执行closestHint相关逻辑。
     *
     * @param oldString old字符串参数。
     * @param content 待处理内容。
     * @return 返回closest Hint结果。
     */
    private String closestHint(String oldString, String content) {
        String anchor = firstNonBlankLine(oldString);
        if (StrUtil.isBlank(anchor)) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().contains(anchor.trim())
                    || anchor.trim().contains(lines[i].trim())) {
                int start = Math.max(0, i - 2);
                int end = Math.min(lines.length, i + 3);
                StringBuilder hint = new StringBuilder();
                hint.append("\n\nDid you mean one of these sections?\n");
                for (int j = start; j < end; j++) {
                    hint.append(String.format("%4d| %s%n", j + 1, lines[j]));
                }
                return hint.toString();
            }
        }
        return "";
    }

    /**
     * 执行firstNon空白值行相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回first Non Blank Line结果。
     */
    private String firstNonBlankLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\n")) {
            if (StrUtil.isNotBlank(line)) {
                return line;
            }
        }
        return "";
    }

    /**
     * 执行simpleDiff相关逻辑。
     *
     * @param filePath 目标文件相对路径或绝对路径。
     * @param oldContent oldContent 参数。
     * @param newContent newContent 参数。
     * @return 返回simple Diff结果。
     */
    private String simpleDiff(String filePath, String oldContent, String newContent) {
        StringBuilder diff = new StringBuilder();
        boolean added = StrUtil.isEmpty(oldContent) && StrUtil.isNotEmpty(newContent);
        boolean deleted = StrUtil.isNotEmpty(oldContent) && StrUtil.isEmpty(newContent);
        if (added) {
            diff.append("--- /dev/null\n");
            diff.append("+++ b/").append(filePath).append('\n');
        } else if (deleted) {
            diff.append("--- a/").append(filePath).append('\n');
            diff.append("+++ /dev/null\n");
        } else {
            diff.append("--- a/").append(filePath).append('\n');
            diff.append("+++ b/").append(filePath).append('\n');
        }
        if (!StrUtil.equals(oldContent, newContent)) {
            for (String line : StrUtil.nullToEmpty(oldContent).split("\n", -1)) {
                if (line.length() > 0) {
                    diff.append('-').append(line).append('\n');
                }
            }
            for (String line : StrUtil.nullToEmpty(newContent).split("\n", -1)) {
                if (line.length() > 0) {
                    diff.append('+').append(line).append('\n');
                }
            }
        }
        return diff.toString();
    }

    /**
     * 解析路径。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 返回解析后的路径。
     */
    private Path resolvePath(String rawPath) {
        String value = StrUtil.nullToEmpty(rawPath).trim();
        if (value.indexOf('\0') >= 0 || value.contains("!/")) {
            throw new IllegalArgumentException("invalid file path: " + rawPath);
        }
        Path target = rootPath.resolve(normalizeWorkspacePath(value)).normalize();
        if (!target.startsWith(rootPath)) {
            throw new SecurityException("禁止越权访问沙箱外部");
        }
        assertResolvedWithinRoot(target);
        return target;
    }

    /**
     * 将用户可见的 workspace 根目录前缀折叠到当前工具根，确保 patch 与读写工具使用一致的相对路径语义。
     *
     * @param rawPath 用户传入的文件路径。
     * @return 返回用于解析的工作区相对路径。
     */
    private String normalizeWorkspacePath(String rawPath) {
        String value = StrUtil.nullToEmpty(rawPath);
        if (StrUtil.isBlank(value)) {
            return value;
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/")) {
            return value;
        }
        Path fileName = rootPath.getFileName();
        if (fileName == null) {
            return value;
        }
        String rootName = fileName.toString();
        if (!"workspace".equalsIgnoreCase(rootName)) {
            return value;
        }
        String prefix = rootName + "/";
        if (normalized.length() > prefix.length()
                && normalized.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            return normalized.substring(prefix.length());
        }
        return value;
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
     * 执行read相关逻辑。
     *
     * @param target target 参数。
     * @return 返回read结果。
     */
    private String read(Path target) throws IOException {
        return stripLeadingBom(new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    /**
     * 执行写入相关逻辑。
     *
     * @param target target 参数。
     * @param content 待处理内容。
     */
    private void write(Path target, String content) throws IOException {
        String value = StrUtil.nullToEmpty(content);
        String oldContent =
                Files.exists(target) && !Files.isDirectory(target) ? read(target) : "";
        ConfigSecretWriteGuard.assertNoPlaceholderSecretDowngrade(target, oldContent, value);
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
        } catch (Exception e) {
            logRecoverableFailure("detect-leading-bom", e);
            return false;
        }
    }

    /**
     * 记录可恢复补丁异常，只写阶段和异常类型，避免泄露文件路径或补丁内容。
     *
     * @param stage 降级阶段。
     * @param error 异常对象。
     */
    private static void logRecoverableFailure(String stage, Exception error) {
        if (log.isDebugEnabled()) {
            log.debug("patch tool fallback. stage={} error={}", stage, exceptionSummary(error));
        }
    }

    /**
     * 生成低敏异常摘要，仅保留异常类型。
     *
     * @param error 异常对象。
     * @return 返回异常类型摘要。
     */
    private static String exceptionSummary(Exception error) {
        return error == null ? "unknown" : error.getClass().getName();
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
     * 执行afterMarker相关逻辑。
     *
     * @param line 行参数。
     * @param marker marker 参数。
     * @return 返回after Marker结果。
     */
    private String afterMarker(String line, String marker) {
        return line.substring(marker.length()).trim();
    }

    /**
     * 提取上下文Hint。
     *
     * @param line 行参数。
     * @return 返回上下文Hint结果。
     */
    private String extractContextHint(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("@@")) {
            return null;
        }
        String body = trimmed.substring(2).trim();
        if (body.endsWith("@@")) {
            body = body.substring(0, body.length() - 2).trim();
        }
        return StrUtil.blankToDefault(body, null);
    }

    /**
     * 执行hintLabel相关逻辑。
     *
     * @param hunk hunk 参数。
     * @return 返回hint Label结果。
     */
    private String hintLabel(Hunk hunk) {
        return StrUtil.isBlank(hunk.contextHint) ? "(no hint)" : "'" + hunk.contextHint + "'";
    }

    /**
     * 规范化路径。
     *
     * @param path 文件或目录路径。
     * @return 返回路径。
     */
    private String normalizePath(String path) {
        return StrUtil.nullToEmpty(path).replace('\\', '/');
    }

    /**
     * 生成安全展示用的路径。
     *
     * @param path 文件或目录路径。
     * @return 返回safe路径。
     */
    private String safePath(String path) {
        String value =
                SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(path))
                        .replace('\\', '/')
                        .trim();
        if (value.length() == 0) {
            return "[unknown]";
        }
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        if (StrUtil.isBlank(name)) {
            name = "[path]";
        }
        return SecretRedactor.redact(name, 400);
    }

    /**
     * 执行join相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param separator separator 参数。
     * @return 返回join结果。
     */
    private String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    /** 表示补丁结果，携带调用方后续判断所需信息。 */
    public static class PatchResult {
        /** 记录补丁工具对外返回的当前执行状态。 */
        public String status;

        /** 记录补丁中的错误。 */
        public String error;

        /** 记录补丁中的diff。 */
        public String diff;

        /** 记录补丁中的warning。 */
        public String _warning;

        /** 记录补丁中的resolved路径。 */
        public String resolved_path;

        /** 保存warnings集合，维持调用顺序或去重语义。 */
        public List<String> warnings = new ArrayList<String>();

        /** 保存filesmodified集合，维持调用顺序或去重语义。 */
        public List<String> files_modified = new ArrayList<String>();

        /** 保存files创建集合，维持调用顺序或去重语义。 */
        public List<String> files_created = new ArrayList<String>();

        /** 保存files删除集合，维持调用顺序或去重语义。 */
        public List<String> files_deleted = new ArrayList<String>();

        /** 保存filesModified集合，维持调用顺序或去重语义。 */
        private final List<String> filesModified = files_modified;

        /** 保存files创建集合，维持调用顺序或去重语义。 */
        private final List<String> filesCreated = files_created;

        /** 保存files删除集合，维持调用顺序或去重语义。 */
        private final List<String> filesDeleted = files_deleted;

        /**
         * 创建补丁成功结果。
         *
         * @return 返回成功状态结果。
         */
        public static PatchResult success() {
            PatchResult result = new PatchResult();
            result.status = "success";
            return result;
        }

        /**
         * 执行错误相关逻辑。
         *
         * @param error 错误参数。
         * @return 返回error结果。
         */
        public static PatchResult error(String error) {
            PatchResult result = new PatchResult();
            result.status = "error";
            result.error =
                    SecretRedactor.redact(StrUtil.blankToDefault(error, "patch failed"), 1000);
            return result;
        }

        /**
         * 追加Warning。
         *
         * @param warning warning 参数。
         */
        private void addWarning(String warning) {
            if (StrUtil.isBlank(warning)) {
                return;
            }
            warnings.add(warning);
            if (StrUtil.isBlank(_warning)) {
                _warning = warning;
            } else {
                _warning = _warning + "\n" + warning;
            }
        }

        /** 脱敏输出。 */
        private void redactOutput() {
            error = redact(error, 1000);
            diff = redact(diff, 20000);
            _warning = redact(_warning, 1000);
            resolved_path = redact(resolved_path, 400);
            redactList(warnings, 1000);
            redactList(files_modified, 400);
            redactList(files_created, 400);
            redactList(files_deleted, 400);
        }

        /**
         * 脱敏List。
         *
         * @param values 待规范化或校验的原始值集合。
         * @param maxLength 最大保留字符数。
         */
        private static void redactList(List<String> values, int maxLength) {
            if (values == null || values.isEmpty()) {
                return;
            }
            for (int i = 0; i < values.size(); i++) {
                values.set(i, redact(values.get(i), maxLength));
            }
        }

        /**
         * 脱敏文本中的密钥、令牌和敏感路径。
         *
         * @param value 待规范化或校验的原始值。
         * @param maxLength 最大保留字符数。
         * @return 返回redact结果。
         */
        private static String redact(String value, int maxLength) {
            return value == null ? null : SecretRedactor.redact(value, maxLength);
        }

        /**
         * 写入Single Resolved路径。
         *
         * @param path 文件或目录路径。
         */
        private void setSingleResolvedPath(String path) {
            if (StrUtil.isNotBlank(path) && StrUtil.isBlank(resolved_path)) {
                resolved_path = path;
            } else {
                resolved_path = null;
            }
        }
    }

    /** 承载补丁Operation相关状态和辅助逻辑。 */
    private static class PatchOperation {
        /** 记录补丁Operation中的类型。 */
        private final String type;

        /** 记录补丁Operation中的文件路径。 */
        private final String filePath;

        /** 记录补丁Operation中的new路径。 */
        private String newPath;

        /** 记录补丁Operation中的错误。 */
        private String error;

        /** 保存hunks集合，维持调用顺序或去重语义。 */
        private final List<Hunk> hunks = new ArrayList<Hunk>();

        /**
         * 创建Patch Operation实例，并注入运行所需依赖。
         *
         * @param type 类型参数。
         * @param filePath 目标文件相对路径或绝对路径。
         */
        private PatchOperation(String type, String filePath) {
            this.type = type;
            this.filePath = StrUtil.nullToEmpty(filePath).trim();
        }
    }

    /** 承载Hunk相关状态和辅助逻辑。 */
    private static class Hunk {
        /** 记录Hunk中的上下文Hint。 */
        private final String contextHint;

        /** 保存lines集合，维持调用顺序或去重语义。 */
        private final List<HunkLine> lines = new ArrayList<HunkLine>();

        /**
         * 创建Hunk实例，并注入运行所需依赖。
         *
         * @param contextHint 上下文Hint上下文。
         */
        private Hunk(String contextHint) {
            this.contextHint = contextHint;
        }
    }

    /** 承载Hunk行相关状态和辅助逻辑。 */
    private static class HunkLine {
        /** 记录Hunk行中的prefix。 */
        private final char prefix;

        /** 记录Hunk行中的content。 */
        private final String content;

        /**
         * 创建Hunk Line实例，并注入运行所需依赖。
         *
         * @param prefix prefix 参数。
         * @param content 待处理内容。
         */
        private HunkLine(char prefix, String content) {
            this.prefix = prefix;
            this.content = StrUtil.nullToEmpty(content);
        }
    }

    /** 表示Apply结果，携带调用方后续判断所需信息。 */
    private static class ApplyResult {
        /** 是否启用success。 */
        private final boolean success;

        /** 记录Apply中的content。 */
        private final String content;

        /** 记录Apply中的错误。 */
        private final String error;

        /**
         * 创建Apply结果实例，并注入运行所需依赖。
         *
         * @param success success 参数。
         * @param content 待处理内容。
         * @param error 错误参数。
         */
        private ApplyResult(boolean success, String content, String error) {
            this.success = success;
            this.content = content;
            this.error = error;
        }

        /**
         * 执行success相关逻辑。
         *
         * @param content 待处理内容。
         * @return 返回success结果。
         */
        private static ApplyResult success(String content) {
            return new ApplyResult(true, content, null);
        }

        /**
         * 执行错误相关逻辑。
         *
         * @param error 错误参数。
         * @return 返回error结果。
         */
        private static ApplyResult error(String error) {
            return new ApplyResult(false, null, error);
        }
    }
}
