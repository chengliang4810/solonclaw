package com.jimuqu.solon.claw.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 承载命令描述符相关状态和辅助逻辑。 */
public final class CommandDescriptor {
    /** 记录命令描述符中的名称。 */
    private final String name;

    /** 保存aliases集合，维持调用顺序或去重语义。 */
    private final List<String> aliases;

    /** 记录命令描述符中的category。 */
    private final String category;

    /** 记录命令描述符中的描述。 */
    private final String description;

    /** 保存scopes集合，维持调用顺序或去重语义。 */
    private final List<String> scopes;

    /** 标记是否启用根据默认。 */
    private final boolean enabledByDefault;

    /**
     * 创建命令描述符实例，并注入运行所需依赖。
     *
     * @param builder 构建器参数。
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
     * 读取名称。
     *
     * @return 返回读取到的名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 读取Aliases。
     *
     * @return 返回读取到的Aliases。
     */
    public List<String> getAliases() {
        return aliases;
    }

    /**
     * 读取Category。
     *
     * @return 返回读取到的Category。
     */
    public String getCategory() {
        return category;
    }

    /**
     * 读取Description。
     *
     * @return 返回读取到的Description。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 读取Scopes。
     *
     * @return 返回读取到的Scopes。
     */
    public List<String> getScopes() {
        return scopes;
    }

    /**
     * 判断是否启用根据默认。
     *
     * @return 如果启用根据默认满足条件则返回 true，否则返回 false。
     */
    public boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    /**
     * 执行斜杠命令名称相关逻辑。
     *
     * @return 返回slash名称结果。
     */
    public String slashName() {
        return "/" + name;
    }

    /**
     * 创建当前类型的构建器。
     *
     * @param name 名称参数。
     * @return 返回builder结果。
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 承载构建器相关状态和辅助逻辑。 */
    public static final class Builder {
        /** 记录构建器中的名称。 */
        private final String name;

        /** 保存aliases集合，维持调用顺序或去重语义。 */
        private final List<String> aliases = new ArrayList<String>();

        /** 记录构建器中的category。 */
        private String category;

        /** 记录构建器中的描述。 */
        private String description;

        /** 保存scopes集合，维持调用顺序或去重语义。 */
        private final List<String> scopes = new ArrayList<String>();

        /** 标记是否启用根据默认。 */
        private boolean enabledByDefault = true;

        /**
         * 创建Builder实例，并注入运行所需依赖。
         *
         * @param name 名称参数。
         */
        private Builder(String name) {
            this.name = normalize(name);
        }

        /**
         * 执行alias相关逻辑。
         *
         * @param alias 别名参数。
         * @return 返回alias结果。
         */
        public Builder alias(String alias) {
            this.aliases.add(normalize(alias));
            return this;
        }

        /**
         * 执行category相关逻辑。
         *
         * @param category 分类参数。
         * @return 返回category结果。
         */
        public Builder category(String category) {
            this.category = category;
            return this;
        }

        /**
         * 执行description相关逻辑。
         *
         * @param description 描述参数。
         * @return 返回description结果。
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 执行scopes相关逻辑。
         *
         * @param scopes scopes 参数。
         * @return 返回scopes结果。
         */
        public Builder scopes(String... scopes) {
            Collections.addAll(this.scopes, scopes);
            return this;
        }

        /**
         * 执行启用状态根据默认相关逻辑。
         *
         * @param enabledByDefault 启用状态By默认开关值。
         * @return 返回enabled根据默认结果。
         */
        public Builder enabledByDefault(boolean enabledByDefault) {
            this.enabledByDefault = enabledByDefault;
            return this;
        }

        /**
         * 构建当前对象并返回不可变结果。
         *
         * @return 返回build结果。
         */
        public CommandDescriptor build() {
            return new CommandDescriptor(this);
        }
    }

    /**
     * 执行规范化相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回规范化结果。
     */
    static String normalize(String command) {
        String value = command == null ? "" : command.trim().toLowerCase();
        return value.startsWith("/") ? value.substring(1) : value;
    }
}
