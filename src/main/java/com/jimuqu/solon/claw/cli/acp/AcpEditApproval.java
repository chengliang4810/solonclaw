package com.jimuqu.solon.claw.cli.acp;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages approval requests for file edit operations in ACP sessions.
 * Callers submit an edit for review; the user approves or rejects it.
 */
public class AcpEditApproval {

    /** Possible outcomes for an edit approval request. */
    public enum Outcome {
        PENDING,
        APPROVED,
        REJECTED
    }

    private final List<EditRequest> pending = new ArrayList<EditRequest>();
    private int sequence;

    /**
     * Submits a file edit for approval.
     *
     * @param filePath  path of the file being edited
     * @param diffPreview  unified diff or summary of the change (may be truncated)
     * @return the approval request ID
     */
    public synchronized String submit(String filePath, String diffPreview) {
        sequence += 1;
        String id = "edit-" + sequence;
        EditRequest request = new EditRequest(
                id,
                StrUtil.nullToEmpty(filePath).trim(),
                truncate(StrUtil.nullToEmpty(diffPreview), 8000),
                System.currentTimeMillis());
        pending.add(request);
        return id;
    }

    /**
     * Approves a pending edit request.
     *
     * @param id  the request ID returned by {@link #submit}
     * @return true if the request was found and approved
     */
    public synchronized boolean approve(String id) {
        return resolve(id, Outcome.APPROVED);
    }

    /**
     * Rejects a pending edit request.
     *
     * @param id  the request ID returned by {@link #submit}
     * @return true if the request was found and rejected
     */
    public synchronized boolean reject(String id) {
        return resolve(id, Outcome.REJECTED);
    }

    /** Returns a snapshot of all pending (unresolved) edit requests. */
    public synchronized List<Map<String, Object>> listPending() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (EditRequest request : pending) {
            if (request.getOutcome() == Outcome.PENDING) {
                result.add(toMap(request));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns the outcome for a given request ID, or null if not found. */
    public synchronized Outcome getOutcome(String id) {
        for (EditRequest request : pending) {
            if (request.getId().equals(id)) {
                return request.getOutcome();
            }
        }
        return null;
    }

    /** Clears all resolved (non-pending) requests. */
    public synchronized void clearResolved() {
        List<EditRequest> remaining = new ArrayList<EditRequest>();
        for (EditRequest request : pending) {
            if (request.getOutcome() == Outcome.PENDING) {
                remaining.add(request);
            }
        }
        pending.clear();
        pending.addAll(remaining);
    }

    private boolean resolve(String id, Outcome outcome) {
        for (EditRequest request : pending) {
            if (request.getId().equals(id) && request.getOutcome() == Outcome.PENDING) {
                request.setOutcome(outcome);
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> toMap(EditRequest request) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", request.getId());
        map.put("file_path", request.getFilePath());
        map.put("diff_preview", request.getDiffPreview());
        map.put("created_at", Long.valueOf(request.getCreatedAt()));
        map.put("outcome", request.getOutcome().name().toLowerCase());
        return map;
    }

    private String truncate(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit - 40))
                + "\n... (" + value.length() + " chars total, truncated)";
    }

    /** Represents a single pending edit approval request. */
    public static class EditRequest {
        private final String id;
        private final String filePath;
        private final String diffPreview;
        private final long createdAt;
        private Outcome outcome = Outcome.PENDING;

        private EditRequest(String id, String filePath, String diffPreview, long createdAt) {
            this.id = id;
            this.filePath = filePath;
            this.diffPreview = diffPreview;
            this.createdAt = createdAt;
        }

        public String getId() { return id; }
        public String getFilePath() { return filePath; }
        public String getDiffPreview() { return diffPreview; }
        public long getCreatedAt() { return createdAt; }
        public Outcome getOutcome() { return outcome; }
        public void setOutcome(Outcome outcome) { this.outcome = outcome; }
    }
}
