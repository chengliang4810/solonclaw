package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.core.repository.ProfileTaskRepository;
import com.jimuqu.solon.claw.profile.ProfileCreateOptions;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.profile.ProfileRuntimeIdentity;
import com.jimuqu.solon.claw.web.DashboardProfileService;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 为 default 主智能体提供受审批保护的命名 Profile 管理工具。 */
public class ProfileManageTools {
    /** 应用配置。 */
    private final AppConfig appConfig;

    /** Profile 核心管理器。 */
    private final ProfileManager profileManager;

    /** 复用 Dashboard 的模型写入和运行时释放逻辑。 */
    private final DashboardProfileService profileService;

    /** default 控制面的协作任务仓储，用于删除保护。 */
    private final ProfileTaskRepository profileTaskRepository;

    /** 创建 Profile 管理工具。 */
    public ProfileManageTools(
            AppConfig appConfig,
            ProfileManager profileManager,
            DashboardProfileService profileService,
            ProfileTaskRepository profileTaskRepository) {
        this.appConfig = appConfig;
        this.profileManager = profileManager;
        this.profileService = profileService;
        this.profileTaskRepository = profileTaskRepository;
    }

    /** 仅供不涉及协作任务的独立生命周期测试使用。 */
    public ProfileManageTools(
            AppConfig appConfig,
            ProfileManager profileManager,
            DashboardProfileService profileService) {
        this(appConfig, profileManager, profileService, null);
    }

    /** 创建命名智能体；执行前必须由用户逐次批准。 */
    @ToolMapping(
            name = "profile_create",
            description =
                    "Create a named agent Profile after explicit user approval. The default Profile cannot be created.")
    public String create(
            @Param(name = "name", description = "Profile name") String name,
            @Param(name = "description", description = "Agent responsibility") String description,
            @Param(name = "provider", required = false, description = "Model provider key")
                    String provider,
            @Param(name = "model", required = false, description = "Model name") String model) {
        try {
            requireDefaultRuntime();
            requireNamed(name);
            int limit = Math.max(1, appConfig.getProfiles().getMaxNamedProfiles());
            if (profileManager.listProfileNames().size() - 1 >= limit) {
                throw new IllegalStateException("命名智能体数量已达到上限 " + limit);
            }
            ProfileCreateOptions options =
                    new ProfileCreateOptions().setDescription(description).setNoAlias(true);
            profileManager.createProfile(name, options);
            try {
                if (StrUtil.isNotBlank(provider) || StrUtil.isNotBlank(model)) {
                    if (StrUtil.hasBlank(provider, model)) {
                        throw new IllegalArgumentException("provider 和 model 必须同时提供");
                    }
                    profileService.updateModel(name, provider, model);
                }
            } catch (Exception e) {
                profileService.deleteProfile(name);
                throw e;
            }
            return ok("智能体已创建", profileService.showProfile(name));
        } catch (Exception e) {
            return error(e);
        }
    }

    /** 修改命名智能体；执行前必须由用户逐次批准。 */
    @ToolMapping(
            name = "profile_update",
            description =
                    "Update a named agent Profile after explicit user approval. Only supplied fields are changed; default is immutable.")
    public String update(
            @Param(name = "name", description = "Current Profile name") String name,
            @Param(name = "new_name", required = false, description = "New Profile name")
                    String newName,
            @Param(name = "description", required = false, description = "New responsibility")
                    String description,
            @Param(name = "provider", required = false, description = "New model provider key")
                    String provider,
            @Param(name = "model", required = false, description = "New model name") String model) {
        try {
            requireDefaultRuntime();
            requireNamed(name);
            boolean renameRequested =
                    StrUtil.isNotBlank(newName) && !name.trim().equals(newName.trim());
            boolean descriptionRequested = description != null;
            boolean modelRequested = StrUtil.isNotBlank(provider) || StrUtil.isNotBlank(model);
            int changeCount =
                    (renameRequested ? 1 : 0)
                            + (descriptionRequested ? 1 : 0)
                            + (modelRequested ? 1 : 0);
            if (changeCount != 1) {
                throw new IllegalArgumentException("每次只能修改名称、职责或模型中的一项");
            }
            if (modelRequested) {
                if (StrUtil.hasBlank(provider, model)) {
                    throw new IllegalArgumentException("provider 和 model 必须同时提供");
                }
                profileService.updateModel(name, provider, model);
            }
            if (descriptionRequested) {
                profileService.updateDescription(name, description);
            }
            String resultName = name;
            if (renameRequested) {
                requireNamed(newName);
                profileService.renameProfile(name, newName);
                resultName = newName;
            }
            return ok("智能体已修改", profileService.showProfile(resultName));
        } catch (Exception e) {
            return error(e);
        }
    }

    /** 删除命名智能体；执行前必须由用户逐次批准。 */
    @ToolMapping(
            name = "profile_delete",
            description =
                    "Permanently delete a named agent Profile after explicit user approval. default cannot be deleted.")
    public String delete(@Param(name = "name", description = "Profile name") String name) {
        try {
            requireDefaultRuntime();
            requireNamed(name);
            if (profileTaskRepository != null
                    && profileTaskRepository.hasActiveTasks(name.trim())) {
                throw new IllegalStateException("智能体仍有等待或运行中的协作任务，不能删除");
            }
            return ok("智能体已删除", profileService.deleteProfile(name));
        } catch (Exception e) {
            return error(e);
        }
    }

    /** 查询单个智能体，包含 default。 */
    @ToolMapping(
            name = "profile_get",
            description =
                    "Get one agent Profile, including default. Read-only and does not require approval.")
    public String get(@Param(name = "name", description = "Profile name") String name) {
        try {
            requireDefaultRuntime();
            return ok("智能体查询完成", profileService.showProfile(name));
        } catch (Exception e) {
            return error(e);
        }
    }

    /** 查询全部智能体，包含 default。 */
    @ToolMapping(
            name = "profile_list",
            description =
                    "List all agent Profiles, including default. Read-only and does not require approval.")
    public String list() {
        try {
            requireDefaultRuntime();
            return ok("智能体列表查询完成", profileService.listProfiles());
        } catch (Exception e) {
            return error(e);
        }
    }

    /** 只允许 default 运行时使用 Profile 管理工具。 */
    private void requireDefaultRuntime() {
        if (!"default".equals(ProfileRuntimeIdentity.resolve(appConfig))) {
            throw new IllegalStateException("Profile 管理工具只允许 default 智能体使用");
        }
    }

    /** 校验目标是命名 Profile。 */
    private void requireNamed(String name) {
        if (StrUtil.isBlank(name) || "default".equalsIgnoreCase(name.trim())) {
            throw new IllegalArgumentException("default 智能体不能被创建、修改或删除");
        }
    }

    /** 生成统一成功结果。 */
    private String ok(String message, Object result) {
        return ToolResultEnvelope.ok(message).data("result", result).toJson();
    }

    /** 生成统一错误结果。 */
    private String error(Exception error) {
        String message = error.getMessage();
        return ToolResultEnvelope.error(
                        StrUtil.blankToDefault(message, error.getClass().getSimpleName()))
                .toJson();
    }
}
