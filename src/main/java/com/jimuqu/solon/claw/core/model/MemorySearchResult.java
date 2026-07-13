package com.jimuqu.solon.claw.core.model;

/** 统一记忆检索命中结果。 */
public class MemorySearchResult {
    /** 工作区内的记忆文件相对路径。 */
    private String path;

    /** FTS5 生成的命中摘要。 */
    private String snippet;

    /** FTS5 BM25 排名值，数值越小相关度越高。 */
    private double rank;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public double getRank() {
        return rank;
    }

    public void setRank(double rank) {
        this.rank = rank;
    }
}
