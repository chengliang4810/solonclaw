package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.skills.file.FileReadWriteSkill;

/** Solon AI file skill wrapped with Hermes-style path and credential guardrails. */
public class HermesFileReadWriteSkill extends FileReadWriteSkill {
    private static final int DEFAULT_READ_OFFSET = 1;
    private static final int DEFAULT_READ_LIMIT = 500;

    private final Path rootPath;
    private final Path realRootPath;
    private final SecurityPolicyService securityPolicyService;
    private final int maxLines;
    private final int maxLineLength;

    public HermesFileReadWriteSkill(String workDir, SecurityPolicyService securityPolicyService) {
        this(workDir, securityPolicyService, 2000, 2000);
    }

    public HermesFileReadWriteSkill(
            String workDir,
            SecurityPolicyService securityPolicyService,
            int maxLines,
            int maxLineLength) {
        super(workDir);
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        this.realRootPath = safeRealPath(this.rootPath);
        this.securityPolicyService = securityPolicyService;
        this.maxLines = Math.max(1, maxLines);
        this.maxLineLength = Math.max(1, maxLineLength);
    }

    @Override
    @ToolMapping(name = "file_write", description = "写入文本到文件。会自动创建不存在的目录。")
    public String write(@Param("fileName") String fileName, @Param("content") String content) {
        assertSafe(ToolNameConstants.FILE_WRITE, fileName);
        assertContained(fileName);
        return super.write(fileName, content);
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
        return super.list(dirName);
    }

    @Override
    @ToolMapping(name = "file_delete", description = "删除指定文件或空目录。")
    public String delete(@Param("fileName") String fileName) {
        assertSafe(ToolNameConstants.FILE_DELETE, fileName);
        assertContained(fileName);
        return super.delete(fileName);
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
                + StrUtil.nullToEmpty(verdict.getPath())
                + "\n请改用工作区内的普通项目文件，敏感凭据文件不能通过 Agent 工具读取、写入或删除。";
    }

    private String readPaged(String fileName, Integer offset, Integer limit) {
        if (StrUtil.isBlank(fileName)) {
            return ToolResultEnvelope.error("fileName is required").toJson();
        }
        int safeOffset = Math.max(1, offset == null ? DEFAULT_READ_OFFSET : offset.intValue());
        int safeLimit =
                Math.max(1, Math.min(limit == null ? DEFAULT_READ_LIMIT : limit.intValue(), maxLines));
        Path target;
        try {
            target = resolvePath(fileName);
        } catch (Exception e) {
            return ToolResultEnvelope.error("读取失败: " + e.getMessage()).toJson();
        }
        File targetFile = target.toFile();
        if (!targetFile.exists()) {
            return ToolResultEnvelope.error("文件不存在: " + fileName)
                    .data("path", fileName)
                    .toJson();
        }
        if (targetFile.isDirectory()) {
            return ToolResultEnvelope.error(
                            "读取失败：'" + fileName + "' 是一个目录。请使用 file_list 查看其内容。")
                    .data("path", fileName)
                    .toJson();
        }

        List<String> selected = new ArrayList<String>();
        int totalLines = 0;
        int endLine = safeOffset + safeLimit - 1;
        try (BufferedReader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                if (totalLines >= safeOffset && totalLines <= endLine) {
                    selected.add(numberedLine(totalLines, line));
                }
            }
            boolean truncated = totalLines > endLine;
            String content = joinLines(selected);
            ToolResultEnvelope envelope =
                    ToolResultEnvelope.ok("文件读取完成：" + fileName)
                            .data("path", fileName)
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
            return envelope.toJson();
        } catch (Exception e) {
            return ToolResultEnvelope.error("读取失败: " + e.getMessage())
                    .data("path", fileName)
                    .toJson();
        }
    }

    private String numberedLine(int lineNumber, String line) {
        String value = StrUtil.nullToEmpty(line);
        if (value.length() > maxLineLength) {
            value = value.substring(0, maxLineLength) + "... [truncated]";
        }
        return String.format("%6d|%s", Integer.valueOf(lineNumber), value);
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

    private Path resolvePath(String name) {
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
}
