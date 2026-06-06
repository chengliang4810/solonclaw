package com.jimuqu.solon.claw.agent;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 承载Agent角色配置相关状态和辅助逻辑。 */
@Getter
@Setter
@NoArgsConstructor
public class AgentProfile {
    /** 记录Agent角色配置中的Agent名称。 */
    private String agentName;

    /** 记录Agent角色配置中的展示名称。 */
    private String displayName;

    /** 记录Agent角色配置中的描述。 */
    private String description;

    /** 记录Agent角色配置中的角色提示词。 */
    private String rolePrompt;

    /** 记录Agent角色配置中的默认模型。 */
    private String defaultModel;

    /** 记录Agent角色配置中的模型。 */
    private String model;

    /** 记录Agent角色配置中的允许工具 JSON。 */
    private String allowedToolsJson;

    /** 记录Agent角色配置中的技能 JSON。 */
    private String skillsJson;

    /** 记录Agent角色配置中的记忆。 */
    private String memory;

    /** 标记该配置项或记录是否处于启用状态。 */
    private boolean enabled = true;

    /** 记录Agent角色配置中的最近一次使用时间。 */
    private long lastUsedAt;

    /** 记录Agent角色配置中的创建时间。 */
    private long createdAt;

    /** 记录Agent角色配置中的更新时间。 */
    private long updatedAt;

    /**
     * 读取默认模型。
     *
     * @return 返回读取到的默认模型。
     */
    public String getDefaultModel() {
        if (StrUtil.isNotBlank(defaultModel)) {
            return defaultModel;
        }
        return model;
    }

    /**
     * 写入默认模型。
     *
     * @param defaultModel 默认模型参数。
     */
    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
        this.model = defaultModel;
    }

    /**
     * 读取模型。
     *
     * @return 返回读取到的模型。
     */
    public String getModel() {
        return getDefaultModel();
    }

    /**
     * 写入模型。
     *
     * @param model 模型名称。
     */
    public void setModel(String model) {
        setDefaultModel(model);
    }
}
