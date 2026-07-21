package com.jimuqu.solon.claw.agent;

/** 单轮运行开始时冻结的工作区与工具权限范围。 */
public class AgentRuntimeScope {
    /** 本轮允许使用的工具选择器 JSON。 */
    private String allowedToolsJson = "[]";

    /** 本轮文件和命令工具使用的工作区目录。 */
    private String workspaceDir;

    /** 是否由任务显式覆盖工作区目录。 */
    private boolean workspaceDirOverride;

    /**
     * 读取本轮允许的工具选择器 JSON。
     *
     * @return 返回读取到的Allowed工具JSON。
     */
    public String getAllowedToolsJson() {
        return allowedToolsJson;
    }

    /**
     * 写入本轮允许的工具选择器 JSON。
     *
     * @param allowedToolsJson 工具选择器 JSON。
     */
    public void setAllowedToolsJson(String allowedToolsJson) {
        this.allowedToolsJson = allowedToolsJson;
    }

    /**
     * 读取工作区Dir。
     *
     * @return 返回读取到的工作区Dir。
     */
    public String getWorkspaceDir() {
        return workspaceDir;
    }

    /**
     * 写入工作区Dir。
     *
     * @param workspaceDir 文件或目录路径参数。
     */
    public void setWorkspaceDir(String workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    /**
     * 判断是否工作区Dir Override。
     *
     * @return 如果工作区Dir Override满足条件则返回 true，否则返回 false。
     */
    public boolean isWorkspaceDirOverride() {
        return workspaceDirOverride;
    }

    /**
     * 写入工作区Dir Override。
     *
     * @param workspaceDirOverride 文件或目录路径参数。
     */
    public void setWorkspaceDirOverride(boolean workspaceDirOverride) {
        this.workspaceDirOverride = workspaceDirOverride;
    }
}
