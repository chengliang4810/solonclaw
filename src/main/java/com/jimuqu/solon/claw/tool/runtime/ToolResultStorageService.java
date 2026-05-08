package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.IdSupport;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.noear.snack4.ONode;

/** reference-style large tool result persistence. */
public class ToolResultStorageService {
    private static final String TOOL_RESULTS_DIR = "tool-results";
    private static final String PERSISTED_OUTPUT_TAG = "<persisted-output>";
    private static final String PERSISTED_OUTPUT_CLOSING_TAG = "</persisted-output>";
    private static final Set<String> PINNED_INLINE_TOOLS =
            Collections.unmodifiableSet(
                    new HashSet<String>(java.util.Arrays.asList("file_read", "read_file")));

    private final String cacheDir;
    private final String workspaceDir;
    private final int inlineLimitBytes;
    private final int turnBudgetBytes;
    private final int previewLength;
    private long turnBytes;

    public ToolResultStorageService(String cacheDir, int inlineLimitBytes, int previewLength) {
        this(cacheDir, inlineLimitBytes, Math.max(200000, inlineLimitBytes), previewLength);
    }

    public ToolResultStorageService(
            String cacheDir, int inlineLimitBytes, int turnBudgetBytes, int previewLength) {
        this(cacheDir, null, inlineLimitBytes, turnBudgetBytes, previewLength);
    }

    public ToolResultStorageService(
            String cacheDir,
            String workspaceDir,
            int inlineLimitBytes,
            int turnBudgetBytes,
            int previewLength) {
        this.cacheDir = cacheDir;
        this.workspaceDir = workspaceDir;
        this.inlineLimitBytes = Math.max(256, inlineLimitBytes);
        this.turnBudgetBytes = Math.max(256, turnBudgetBytes);
        this.previewLength = Math.max(200, previewLength);
    }

    public synchronized StoredResult observe(
            String toolName, String result, String runId, String toolCallId) {
        String raw = StrUtil.nullToEmpty(result);
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        StoredResult stored = new StoredResult();
        stored.setObservation(raw);
        stored.setPreview(preview(raw, previewLength));
        stored.setSizeBytes(bytes.length);
        stored.setTruncated(false);
        if (isPinnedInline(toolName)) {
            turnBytes += bytes.length;
            return stored;
        }

        boolean overInlineLimit = bytes.length > inlineLimitBytes;
        boolean overTurnBudget = turnBytes + bytes.length > turnBudgetBytes;
        if (!overInlineLimit && !overTurnBudget) {
            turnBytes += bytes.length;
            return stored;
        }

        stored.setTruncated(true);
        String ref = persist(bytes, runId, toolCallId);
        stored.setResultRef(ref);
        stored.setObservation(buildEnvelope(toolName, raw, ref, bytes.length));
        turnBytes += stored.getObservation().getBytes(StandardCharsets.UTF_8).length;
        return stored;
    }

    public static StoredResult describeObservation(String observation) {
        String content = StrUtil.nullToEmpty(observation);
        StoredResult stored = new StoredResult();
        stored.setObservation(content);
        stored.setPreview(content);
        stored.setSizeBytes(content.getBytes(StandardCharsets.UTF_8).length);
        stored.setTruncated(false);
        if (looksLikePersistedOutputBlock(content)) {
            describePersistedOutputBlock(content, stored);
            return stored;
        }
        if (StrUtil.isBlank(content) || !content.trim().startsWith("{")) {
            return stored;
        }
        try {
            ONode node = ONode.ofJson(content);
            if (!node.isObject()) {
                return stored;
            }
            if (!looksLikeEnvelope(node)) {
                return stored;
            }
            if (node.hasKey("preview")) {
                stored.setPreview(node.get("preview").getString());
            }
            if (node.hasKey("result_ref")) {
                stored.setResultRef(node.get("result_ref").getString());
            }
            if (node.hasKey("size")) {
                Long size = node.get("size").getLong(0L);
                stored.setSizeBytes(size == null ? 0L : Math.max(0L, size.longValue()));
            }
            if (node.hasKey("truncated")) {
                Boolean truncated = node.get("truncated").getBoolean();
                stored.setTruncated(truncated != null && truncated.booleanValue());
            }
        } catch (Exception ignored) {
            // Keep the raw observation description.
        }
        return stored;
    }

    private static boolean looksLikeEnvelope(ONode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        if (!node.hasKey("status") && !node.hasKey("success")) {
            return false;
        }
        return node.hasKey("truncated") || node.hasKey("result_ref") || node.hasKey("metadata");
    }

    private String buildEnvelope(String toolName, String raw, String ref, long sizeBytes) {
        String preview = preview(raw, previewLength);
        StringBuilder message = new StringBuilder();
        message.append(PERSISTED_OUTPUT_TAG).append('\n');
        message.append("This tool result was too large (")
                .append(sizeBytes)
                .append(" bytes).").append('\n');
        if (StrUtil.isBlank(ref)) {
            message.append("Full output could not be saved; use the preview only.").append('\n');
        } else {
            message.append("Full output saved to: ").append(ref).append('\n');
            message.append(
                            "Use the file_read/read_file tool with offset and limit to access specific sections of this output.")
                    .append('\n');
        }
        message.append("Tool: ").append(StrUtil.blankToDefault(toolName, "unknown")).append('\n');
        message.append('\n')
                .append("Preview (first ")
                .append(preview.length())
                .append(" chars):")
                .append('\n')
                .append(preview);
        if (raw.length() > preview.length()) {
            message.append("\n...");
        }
        message.append('\n').append(PERSISTED_OUTPUT_CLOSING_TAG);
        return message.toString();
    }

    private static boolean looksLikePersistedOutputBlock(String content) {
        String trimmed = StrUtil.nullToEmpty(content).trim();
        return trimmed.startsWith(PERSISTED_OUTPUT_TAG)
                && trimmed.contains(PERSISTED_OUTPUT_CLOSING_TAG);
    }

    private static void describePersistedOutputBlock(String content, StoredResult stored) {
        stored.setTruncated(true);
        stored.setResultRef(lineValue(content, "Full output saved to:"));
        Long size = firstNumberBetween(content, "too large (", " bytes");
        if (size != null) {
            stored.setSizeBytes(size.longValue());
        }
        String preview = previewFromPersistedOutputBlock(content);
        if (preview != null) {
            stored.setPreview(preview);
        }
    }

    private static String lineValue(String content, String prefix) {
        String[] lines = StrUtil.nullToEmpty(content).split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static Long firstNumberBetween(String content, String left, String right) {
        String raw = StrUtil.nullToEmpty(content);
        int start = raw.indexOf(left);
        if (start < 0) {
            return null;
        }
        start += left.length();
        int end = raw.indexOf(right, start);
        if (end <= start) {
            return null;
        }
        String digits = raw.substring(start, end).replaceAll("[^0-9]", "");
        if (StrUtil.isBlank(digits)) {
            return null;
        }
        try {
            return Long.valueOf(digits);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String previewFromPersistedOutputBlock(String content) {
        String marker = " chars):";
        int markerIndex = StrUtil.nullToEmpty(content).indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        int start = markerIndex + marker.length();
        if (start < content.length() && content.charAt(start) == '\r') {
            start++;
        }
        if (start < content.length() && content.charAt(start) == '\n') {
            start++;
        }
        int end = content.indexOf(PERSISTED_OUTPUT_CLOSING_TAG, start);
        if (end < 0) {
            end = content.length();
        }
        String preview = content.substring(start, end);
        if (preview.endsWith("\n...")) {
            preview = preview.substring(0, preview.length() - 4);
        } else if (preview.endsWith("\r\n...")) {
            preview = preview.substring(0, preview.length() - 5);
        }
        return preview.trim();
    }

    private boolean isPinnedInline(String toolName) {
        if (StrUtil.isBlank(toolName)) {
            return false;
        }
        return PINNED_INLINE_TOOLS.contains(toolName.trim().toLowerCase(Locale.ROOT));
    }

    private String persist(byte[] bytes, String runId, String toolCallId) {
        File base = resolveStorageBase();
        if (base == null) {
            return null;
        }
        try {
            base = base.getCanonicalFile();
            File dir = new File(base, safeSegment(runId, "global")).getCanonicalFile();
            if (!isChild(base, dir)) {
                return null;
            }
            FileUtil.mkdir(dir);
            File file =
                    new File(dir, safeSegment(toolCallId, IdSupport.newId()) + ".txt")
                            .getCanonicalFile();
            if (!isChild(base, file)) {
                return null;
            }
            Files.write(file.toPath(), bytes);
            return file.getAbsolutePath();
        } catch (Exception ignored) {
            return null;
        }
    }

    private File resolveStorageBase() {
        if (StrUtil.isNotBlank(workspaceDir)) {
            return new File(new File(workspaceDir, ".jimuqu"), TOOL_RESULTS_DIR);
        }
        if (StrUtil.isNotBlank(cacheDir)) {
            return new File(cacheDir, TOOL_RESULTS_DIR);
        }
        return null;
    }

    private boolean isChild(File base, File candidate) {
        String basePath = base.getPath();
        String candidatePath = candidate.getPath();
        return candidatePath.equals(basePath)
                || candidatePath.startsWith(basePath + File.separator);
    }

    private String safeSegment(String value, String fallback) {
        String raw = StrUtil.blankToDefault(value, fallback);
        String normalized = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        normalized = normalized.replace("..", "_");
        if (StrUtil.isBlank(normalized)) {
            normalized = IdSupport.newId();
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String preview(String content, int maxChars) {
        String raw = StrUtil.nullToEmpty(content);
        if (raw.length() <= maxChars) {
            return raw;
        }
        String truncated = raw.substring(0, maxChars);
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > maxChars / 2) {
            truncated = truncated.substring(0, lastNewline + 1);
        }
        return truncated;
    }

    public static class StoredResult {
        private String observation;
        private String preview;
        private String resultRef;
        private long sizeBytes;
        private boolean truncated;

        public String getObservation() {
            return observation;
        }

        public void setObservation(String observation) {
            this.observation = observation;
        }

        public String getPreview() {
            return preview;
        }

        public void setPreview(String preview) {
            this.preview = preview;
        }

        public String getResultRef() {
            return resultRef;
        }

        public void setResultRef(String resultRef) {
            this.resultRef = resultRef;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public void setTruncated(boolean truncated) {
            this.truncated = truncated;
        }
    }
}
