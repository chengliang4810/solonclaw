package com.jimuqu.solon.claw.command;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 描述一条对话内 slash command，统一提供 CLI、TUI 与消息网关可复用的命令元数据。 */
public final class CommandDescriptor {
    /** 命令规范名，不包含斜杠前缀，用于注册表索引和用户输入解析。 */
    private final String name;

    /** 可解析到同一命令的别名列表，保持声明顺序便于终端帮助稳定展示。 */
    private final List<String> aliases;

    /** 命令所属功能域，例如 session、runtime、security，供帮助和权限视图分组。 */
    private final String category;

    /** 面向用户展示的中文命令用途说明。 */
    private final String description;

    /** 命令可用入口范围，例如 cli、tui、gateway，避免不同入口维护多套清单。 */
    private final List<String> scopes;

    /** 命令是否默认启用，后续可被安全策略或入口能力裁剪。 */
    private final boolean enabledByDefault;

    /**
     * 从构建器创建不可变命令描述符。
     *
     * @param builder 已填充的命令元数据构建器。
     */
    private CommandDescriptor(Builder builder) {
        this.name = builder.name;
        this.aliases = Collections.unmodifiableList(new ArrayList<String>(builder.aliases));
        this.category = builder.category;
        this.description = builder.description;
        this.scopes = Collections.unmodifiableList(new ArrayList<String>(builder.scopes));
        this.enabledByDefault = builder.enabledByDefault;
    }

    /**
     * 读取不带斜杠的命令规范名。
     *
     * @return 命令规范名。
     */
    public String getName() {
        return name;
    }

    /**
     * 读取命令别名列表。
     *
     * @return 不可变别名列表。
     */
    public List<String> getAliases() {
        return aliases;
    }

    /**
     * 读取命令功能分组。
     *
     * @return 功能分组标识。
     */
    public String getCategory() {
        return category;
    }

    /**
     * 读取面向终端和 Dashboard 的命令说明。
     *
     * @return 中文命令说明。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 读取命令可用入口范围。
     *
     * @return 不可变入口范围列表。
     */
    public List<String> getScopes() {
        return scopes;
    }

    /**
     * 判断命令是否允许在指定入口执行。
     *
     * @param scope 入口范围，例如 cli、gateway 或 tui。
     * @return 当前命令包含该入口范围时返回 true。
     */
    public boolean supportsScope(String scope) {
        return scopes.contains(StrUtil.nullToEmpty(scope).trim().toLowerCase());
    }

    /**
     * 判断命令是否按默认策略启用。
     *
     * @return 默认启用返回 true。
     */
    public boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    /**
     * 返回用户可输入的斜杠命令名。
     *
     * @return 带 "/" 前缀的命令名。
     */
    public String slashName() {
        return "/" + name;
    }

    /**
     * 创建当前类型的构建器。
     *
     * @param name 命令规范名或带斜杠的命令文本。
     * @return 命令描述符构建器。
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 以链式方式声明命令元数据，集中服务注册表初始化。 */
    public static final class Builder {
        /** 命令规范名，不包含斜杠前缀。 */
        private final String name;

        /** 命令别名，构建时会复制为不可变列表。 */
        private final List<String> aliases = new ArrayList<String>();

        /** 命令功能分组。 */
        private String category;

        /** 用户可见的命令用途说明。 */
        private String description;

        /** 命令可用入口范围。 */
        private final List<String> scopes = new ArrayList<String>();

        /** 命令默认启用状态。 */
        private boolean enabledByDefault = true;

        /**
         * 创建命令描述符构建器。
         *
         * @param name 命令规范名或带斜杠的命令文本。
         */
        private Builder(String name) {
            this.name = normalize(name);
        }

        /**
         * 为命令添加一个可解析别名。
         *
         * @param alias 别名文本，可带斜杠。
         * @return 当前构建器，便于链式声明。
         */
        public Builder alias(String alias) {
            this.aliases.add(normalize(alias));
            return this;
        }

        /**
         * 设置命令功能分组。
         *
         * @return 当前构建器，便于链式声明。
         */
        public Builder category(String category) {
            this.category = category;
            return this;
        }

        /**
         * 设置用户可见的中文说明。
         *
         * @return 当前构建器，便于链式声明。
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置命令可用入口范围。
         *
         * @return 当前构建器，便于链式声明。
         */
        public Builder scopes(String... scopes) {
            if (ArrayUtil.isNotEmpty(scopes)) {
                Collections.addAll(this.scopes, scopes);
            }
            return this;
        }

        /**
         * 设置命令是否默认启用。
         *
         * @return 当前构建器，便于链式声明。
         */
        public Builder enabledByDefault(boolean enabledByDefault) {
            this.enabledByDefault = enabledByDefault;
            return this;
        }

        /**
         * 构建当前对象并返回不可变结果。
         *
         * @return 不可变命令描述符。
         */
        public CommandDescriptor build() {
            return new CommandDescriptor(this);
        }
    }

    /**
     * 将用户输入或注册文本归一化为注册表 key。
     *
     * @return 去掉斜杠前缀并转小写后的命令 key。
     */
    static String normalize(String command) {
        String value = StrUtil.nullToEmpty(command).trim().toLowerCase();
        return StrUtil.startWith(value, "/") ? value.substring(1) : value;
    }
}
