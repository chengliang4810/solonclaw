package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** Skills Hub 工具集合。 */
@RequiredArgsConstructor
public class SkillHubTools {
    /** 注入技能中心服务，用于调用对应业务能力。 */
    private final SkillHubService skillHubService;

    /**
     * 执行搜索相关逻辑。
     *
     * @param query 查询参数。
     * @param source 来源参数。
     * @param limit 最大返回数量。
     * @return 返回搜索结果。
     */
    @ToolMapping(
            name = "skills_hub_search",
            description = "Search remote skill sources and return normalized metadata.")
    public String search(
            @Param(name = "query", description = "搜索词") String query,
            @Param(name = "source", description = "来源过滤，可选", required = false) String source,
            @Param(name = "limit", description = "结果条数，默认 10", required = false) Integer limit)
            throws Exception {
        return safeResult(
                skillHubService.search(
                        query,
                        source == null ? "all" : source,
                        limit == null ? 10 : limit.intValue()));
    }

    /**
     * 执行inspect相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回inspect结果。
     */
    @ToolMapping(
            name = "skills_hub_inspect",
            description = "Inspect a remote skill identifier without installing it.")
    public String inspect(@Param(name = "identifier", description = "来源标识符") String identifier)
            throws Exception {
        return safeResult(skillHubService.inspect(identifier));
    }

    /**
     * 执行install相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @param category 分类参数。
     * @param force force 参数。
     * @return 返回install结果。
     */
    @ToolMapping(
            name = "skills_hub_install",
            description =
                    "Install a skill from a remote source into the local runtime skills directory.")
    public String install(
            @Param(name = "identifier", description = "来源标识符") String identifier,
            @Param(name = "category", description = "可选安装分类", required = false) String category,
            @Param(name = "force", description = "是否强制安装", required = false) Boolean force)
            throws Exception {
        return safeResult(
                skillHubService.install(
                        identifier, category, force != null && force.booleanValue()));
    }

    /**
     * 执行列表相关逻辑。
     *
     * @return 返回list结果。
     */
    @ToolMapping(name = "skills_hub_list", description = "List hub-installed skills.")
    public String list() throws Exception {
        return safeResult(skillHubService.listInstalled());
    }

    /**
     * 执行check相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回check结果。
     */
    @ToolMapping(
            name = "skills_hub_check",
            description = "Check hub-installed skills for upstream updates.")
    public String check(@Param(name = "name", description = "可选技能名", required = false) String name)
            throws Exception {
        return safeResult(skillHubService.check(name));
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param name 名称参数。
     * @param force force 参数。
     * @return 返回更新结果。
     */
    @ToolMapping(
            name = "skills_hub_update",
            description = "Update hub-installed skills from their upstream sources.")
    public String update(
            @Param(name = "name", description = "可选技能名", required = false) String name,
            @Param(name = "force", description = "是否允许覆盖 caution 限制或本地修改", required = false)
                    Boolean force)
            throws Exception {
        return safeResult(skillHubService.update(name, force != null && force.booleanValue()));
    }

    /**
     * 执行审计相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回审计结果。
     */
    @ToolMapping(
            name = "skills_hub_audit",
            description = "Audit installed hub skills with the local skills guard.")
    public String audit(@Param(name = "name", description = "可选技能名", required = false) String name)
            throws Exception {
        return safeResult(skillHubService.audit(name));
    }

    /**
     * 执行uninstall相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回uninstall结果。
     */
    @ToolMapping(name = "skills_hub_uninstall", description = "Uninstall a hub-installed skill.")
    public String uninstall(@Param(name = "name", description = "技能名") String name)
            throws Exception {
        return safeResult(
                java.util.Collections.singletonMap("message", skillHubService.uninstall(name)));
    }

    /**
     * 执行来源库相关逻辑。
     *
     * @param action 操作参数。
     * @param repo repo 参数。
     * @param path 文件或目录路径。
     * @return 返回tap结果。
     */
    @ToolMapping(
            name = "skills_hub_tap",
            description = "Manage GitHub taps for the skills hub. action supports list/add/remove.")
    public String tap(
            @Param(name = "action", description = "list/add/remove") String action,
            @Param(name = "repo", description = "owner/repo", required = false) String repo,
            @Param(name = "path", description = "repo 内 skills 根路径，可选", required = false)
                    String path)
            throws Exception {
        if ("list".equalsIgnoreCase(action)) {
            return safeResult(skillHubService.listTaps());
        }
        if ("add".equalsIgnoreCase(action)) {
            return safeResult(
                    java.util.Collections.singletonMap(
                            "message", skillHubService.addTap(repo, path)));
        }
        if ("remove".equalsIgnoreCase(action)) {
            return safeResult(
                    java.util.Collections.singletonMap("message", skillHubService.removeTap(repo)));
        }
        return SecretRedactor.redact(
                new ONode().set("status", "error").set("error", "Unsupported tap action").toJson(),
                1000);
    }

    /**
     * 生成安全展示用的结果。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe结果。
     */
    private String safeResult(Object value) {
        return SecretRedactor.redact(ONode.serialize(value), 20000);
    }

    /** 提供搜索工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class SearchTool {
        /** 记录搜索中的委托。 */
        private final SkillHubTools delegate;

        /**
         * 执行搜索相关逻辑。
         *
         * @param query 查询参数。
         * @param source 来源参数。
         * @param limit 最大返回数量。
         * @return 返回搜索结果。
         */
        @ToolMapping(
                name = "skills_hub_search",
                description = "Search remote skill sources and return normalized metadata.")
        public String search(
                @Param(name = "query", description = "搜索词") String query,
                @Param(name = "source", description = "来源过滤，可选", required = false) String source,
                @Param(name = "limit", description = "结果条数，默认 10", required = false) Integer limit)
                throws Exception {
            return delegate.search(query, source, limit);
        }
    }

    /** 提供Inspect工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class InspectTool {
        /** 记录Inspect中的委托。 */
        private final SkillHubTools delegate;

        /**
         * 执行inspect相关逻辑。
         *
         * @param identifier identifier标识或键值。
         * @return 返回inspect结果。
         */
        @ToolMapping(
                name = "skills_hub_inspect",
                description = "Inspect a remote skill identifier without installing it.")
        public String inspect(@Param(name = "identifier", description = "来源标识符") String identifier)
                throws Exception {
            return delegate.inspect(identifier);
        }
    }

    /** 提供Install工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class InstallTool {
        /** 记录Install中的委托。 */
        private final SkillHubTools delegate;

        /**
         * 执行install相关逻辑。
         *
         * @param identifier identifier标识或键值。
         * @param category 分类参数。
         * @param force force 参数。
         * @return 返回install结果。
         */
        @ToolMapping(
                name = "skills_hub_install",
                description =
                        "Install a skill from a remote source into the local runtime skills directory.")
        public String install(
                @Param(name = "identifier", description = "来源标识符") String identifier,
                @Param(name = "category", description = "可选安装分类", required = false) String category,
                @Param(name = "force", description = "是否强制安装", required = false) Boolean force)
                throws Exception {
            return delegate.install(identifier, category, force);
        }
    }

    /** 提供List工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class ListTool {
        /** 记录列表中的委托。 */
        private final SkillHubTools delegate;

        /**
         * 执行列表相关逻辑。
         *
         * @return 返回list结果。
         */
        @ToolMapping(name = "skills_hub_list", description = "List hub-installed skills.")
        public String list() throws Exception {
            return delegate.list();
        }
    }

    /** 提供Check工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class CheckTool {
        /** 记录Check中的委托。 */
        private final SkillHubTools delegate;

        /**
         * 执行check相关逻辑。
         *
         * @param name 名称参数。
         * @return 返回check结果。
         */
        @ToolMapping(
                name = "skills_hub_check",
                description = "Check hub-installed skills for upstream updates.")
        public String check(
                @Param(name = "name", description = "可选技能名", required = false) String name)
                throws Exception {
            return delegate.check(name);
        }
    }

    /** 提供更新工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class UpdateTool {
        /** 记录更新中的委托。 */
        private final SkillHubTools delegate;

        /**
         * 执行更新相关逻辑。
         *
         * @param name 名称参数。
         * @param force force 参数。
         * @return 返回更新结果。
         */
        @ToolMapping(
                name = "skills_hub_update",
                description = "Update hub-installed skills from their upstream sources.")
        public String update(
                @Param(name = "name", description = "可选技能名", required = false) String name,
                @Param(name = "force", description = "是否允许覆盖 caution 限制或本地修改", required = false)
                        Boolean force)
                throws Exception {
            return delegate.update(name, force);
        }
    }

    /** 提供审计工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class AuditTool {
        /** 记录审计中的委托。 */
        private final SkillHubTools delegate;

        /**
         * 执行审计相关逻辑。
         *
         * @param name 名称参数。
         * @return 返回审计结果。
         */
        @ToolMapping(
                name = "skills_hub_audit",
                description = "Audit installed hub skills with the local skills guard.")
        public String audit(
                @Param(name = "name", description = "可选技能名", required = false) String name)
                throws Exception {
            return delegate.audit(name);
        }
    }

    /** 提供Uninstall工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class UninstallTool {
        /** 记录Uninstall中的委托。 */
        private final SkillHubTools delegate;

        /**
         * 执行uninstall相关逻辑。
         *
         * @param name 名称参数。
         * @return 返回uninstall结果。
         */
        @ToolMapping(
                name = "skills_hub_uninstall",
                description = "Uninstall a hub-installed skill.")
        public String uninstall(@Param(name = "name", description = "技能名") String name)
                throws Exception {
            return delegate.uninstall(name);
        }
    }

    /** 提供Tap工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class TapTool {
        /** 记录来源库中的委托。 */
        private final SkillHubTools delegate;

        /**
         * 执行来源库相关逻辑。
         *
         * @param action 操作参数。
         * @param repo repo 参数。
         * @param path 文件或目录路径。
         * @return 返回tap结果。
         */
        @ToolMapping(
                name = "skills_hub_tap",
                description =
                        "Manage GitHub taps for the skills hub. action supports list/add/remove.")
        public String tap(
                @Param(name = "action", description = "list/add/remove") String action,
                @Param(name = "repo", description = "owner/repo", required = false) String repo,
                @Param(name = "path", description = "repo 内 skills 根路径，可选", required = false)
                        String path)
                throws Exception {
            return delegate.tap(action, repo, path);
        }
    }
}
