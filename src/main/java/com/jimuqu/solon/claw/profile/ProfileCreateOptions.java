package com.jimuqu.solon.claw.profile;

/** 描述创建 Profile 时的克隆、别名、说明和技能初始化选项。 */
public class ProfileCreateOptions {
    /** 是否从来源 Profile 复制配置、身份、记忆和技能。 */
    private boolean clone;

    /** 是否复制来源 Profile 的完整树，并排除根历史与进程状态。 */
    private boolean cloneAll;

    /** 克隆来源 Profile；仅 null 表示由调用方使用当前选择项，空字符串属于非法名称。 */
    private String cloneFrom;

    /** 是否跳过快捷命令别名。 */
    private boolean noAlias;

    /** Profile 的用户可见职责说明。 */
    private String description;

    /** 是否明确禁用内置技能初始化。 */
    private boolean noSkills;

    /** 创建默认选项。 */
    public ProfileCreateOptions() {}

    /**
     * 判断是否复制基础配置与技能。
     *
     * @return 启用基础克隆时返回 true。
     */
    public boolean isClone() {
        return clone;
    }

    /**
     * 设置是否复制基础配置与技能。
     *
     * @param clone 是否启用基础克隆。
     * @return 当前选项对象。
     */
    public ProfileCreateOptions setClone(boolean clone) {
        this.clone = clone;
        return this;
    }

    /**
     * 判断是否执行排除根历史与进程状态的全量克隆。
     *
     * @return 启用全量克隆时返回 true。
     */
    public boolean isCloneAll() {
        return cloneAll;
    }

    /**
     * 设置是否执行排除根历史与进程状态的全量克隆。
     *
     * @param cloneAll 是否启用全量克隆。
     * @return 当前选项对象。
     */
    public ProfileCreateOptions setCloneAll(boolean cloneAll) {
        this.cloneAll = cloneAll;
        return this;
    }

    /**
     * 读取克隆来源 Profile。
     *
     * @return 来源 Profile 名；null 表示未显式指定。
     */
    public String getCloneFrom() {
        return cloneFrom;
    }

    /**
     * 设置克隆来源 Profile。
     *
     * @param cloneFrom 来源 Profile 名。
     * @return 当前选项对象。
     */
    public ProfileCreateOptions setCloneFrom(String cloneFrom) {
        this.cloneFrom = cloneFrom;
        return this;
    }

    /**
     * 判断是否跳过快捷命令别名。
     *
     * @return 跳过别名时返回 true。
     */
    public boolean isNoAlias() {
        return noAlias;
    }

    /**
     * 设置是否跳过快捷命令别名。
     *
     * @param noAlias 是否跳过别名。
     * @return 当前选项对象。
     */
    public ProfileCreateOptions setNoAlias(boolean noAlias) {
        this.noAlias = noAlias;
        return this;
    }

    /**
     * 读取 Profile 职责说明。
     *
     * @return 职责说明，可为空。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置 Profile 职责说明。
     *
     * @param description 一到两句话的职责说明。
     * @return 当前选项对象。
     */
    public ProfileCreateOptions setDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * 判断是否明确禁用内置技能初始化。
     *
     * @return 禁用内置技能时返回 true。
     */
    public boolean isNoSkills() {
        return noSkills;
    }

    /**
     * 设置是否明确禁用内置技能初始化。
     *
     * @param noSkills 是否禁用内置技能。
     * @return 当前选项对象。
     */
    public ProfileCreateOptions setNoSkills(boolean noSkills) {
        this.noSkills = noSkills;
        return this;
    }
}
