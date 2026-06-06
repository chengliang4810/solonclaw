package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** MCP server 配置与发现状态。 */
@Getter
@Setter
@NoArgsConstructor
public class McpServerRecord {
    /** 记录MCP服务端中的服务端标识。 */
    private String serverId;

    /** 记录MCP服务端中的名称。 */
    private String name;

    /** 记录MCP服务端中的transport。 */
    private String transport;

    /** 记录MCP服务端中的endpoint。 */
    private String endpoint;

    /** 记录MCP服务端中的命令。 */
    private String command;

    /** 记录MCP服务端中的参数JSON。 */
    private String argsJson;

    /** 记录MCP服务端中的认证JSON。 */
    private String authJson;

    /** 记录MCP服务端中的oauthJSON。 */
    private String oauthJson;

    /** 记录MCP服务端中的capabilitiesJSON。 */
    private String capabilitiesJson;

    /** 记录MCP服务端中的状态。 */
    private String status;

    /** 记录MCP服务端中的工具JSON。 */
    private String toolsJson;

    /** 记录MCP服务端中的最近一次工具哈希。 */
    private String lastToolsHash;

    /** 记录MCP服务端中的最近一次错误。 */
    private String lastError;

    /** 标记该配置项或记录是否处于启用状态。 */
    private boolean enabled;

    /** 记录MCP服务端中的创建时间。 */
    private long createdAt;

    /** 记录MCP服务端中的更新时间。 */
    private long updatedAt;

    /** 记录MCP服务端中的最近一次Checked时间。 */
    private long lastCheckedAt;

    /** 记录MCP服务端中的最近一次工具Changed时间。 */
    private long lastToolsChangedAt;
}
