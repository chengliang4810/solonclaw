package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.support.constants.AgentSettingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 聊天窗口中间态显示设置服务。 */
public class DisplaySettingsService {
    /** 记录展示设置读取失败的低敏诊断日志，不输出来源键原文。 */
    private static final Logger log = LoggerFactory.getLogger(DisplaySettingsService.class);

    /** 注入应用配置，用于展示设置。 */
    private final AppConfig appConfig;

    /** 保存global设置仓储集合，维持调用顺序或去重语义。 */
    private final GlobalSettingRepository globalSettingRepository;

    /**
     * 创建展示设置服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     */
    public DisplaySettingsService(
            AppConfig appConfig, GlobalSettingRepository globalSettingRepository) {
        this.appConfig = appConfig;
        this.globalSettingRepository = globalSettingRepository;
    }

    /** 解析渠道默认工具进度模式。 */
    public String resolveToolProgress(PlatformType platform) {
        String configured =
                channelConfig(platform) == null ? null : channelConfig(platform).getToolProgress();
        if (StrUtil.isNotBlank(configured)) {
            return normalizeToolProgress(configured);
        }
        return normalizeToolProgress(appConfig.getDisplay().getToolProgress());
    }

    /** 当前来源键是否展示 reasoning。 */
    public boolean isReasoningVisible(String sourceKey, PlatformType platform) {
        if (globalSettingRepository != null && StrUtil.isNotBlank(sourceKey)) {
            try {
                String stored =
                        globalSettingRepository.get(
                                AgentSettingConstants.DISPLAY_REASONING_VISIBLE_PREFIX + sourceKey);
                if (StrUtil.isNotBlank(stored)) {
                    return parseBoolean(stored, false);
                }
            } catch (Exception e) {
                log.debug("读取reasoning展示设置失败，使用配置默认值 error={}", e.getClass().getSimpleName());
            }
        }
        return appConfig.getDisplay().isShowReasoning();
    }

    /** 持久化当前来源键的 reasoning 展示设置。 */
    public void setReasoningVisible(String sourceKey, boolean visible) throws Exception {
        if (globalSettingRepository == null || StrUtil.isBlank(sourceKey)) {
            return;
        }
        globalSettingRepository.set(
                AgentSettingConstants.DISPLAY_REASONING_VISIBLE_PREFIX + sourceKey,
                visible ? "true" : "false");
    }

    /** 返回 reasoning 显示状态说明。 */
    public String describeReasoning(String sourceKey, PlatformType platform) {
        return isReasoningVisible(sourceKey, platform) ? "show" : "hide";
    }

    /** 预览长度。 */
    public int toolPreviewLength() {
        return Math.max(20, appConfig.getDisplay().getToolPreviewLength());
    }

    /** 进度/思考节流毫秒数。 */
    public int progressThrottleMs() {
        return Math.max(0, appConfig.getDisplay().getProgressThrottleMs());
    }

    /** 钉钉长任务进度卡模板 ID。 */
    public String dingtalkProgressCardTemplateId() {
        AppConfig.ChannelConfig config = channelConfig(PlatformType.DINGTALK);
        return config == null ? "" : StrUtil.nullToEmpty(config.getProgressCardTemplateId()).trim();
    }

    /**
     * 执行渠道配置相关逻辑。
     *
     * @param platform 平台参数。
     * @return 返回渠道配置。
     */
    private AppConfig.ChannelConfig channelConfig(PlatformType platform) {
        if (platform == null) {
            return null;
        }
        if (platform == PlatformType.FEISHU) {
            return appConfig.getChannels().getFeishu();
        }
        if (platform == PlatformType.DINGTALK) {
            return appConfig.getChannels().getDingtalk();
        }
        if (platform == PlatformType.WECOM) {
            return appConfig.getChannels().getWecom();
        }
        if (platform == PlatformType.WEIXIN) {
            return appConfig.getChannels().getWeixin();
        }
        return null;
    }

    /**
     * 规范化工具Progress。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回工具Progress结果。
     */
    private String normalizeToolProgress(String value) {
        String normalized = StrUtil.nullToEmpty(value).trim().toLowerCase();
        if ("new".equals(normalized) || "all".equals(normalized) || "verbose".equals(normalized)) {
            return normalized;
        }
        return "off";
    }

    /**
     * 解析Boolean。
     *
     * @param value 待规范化或校验的原始值。
     * @param fallback 兜底参数。
     * @return 返回解析后的Boolean。
     */
    private boolean parseBoolean(String value, boolean fallback) {
        if (StrUtil.isBlank(value)) {
            return fallback;
        }
        String normalized = value.trim();
        return "true".equalsIgnoreCase(normalized)
                || "1".equals(normalized)
                || "yes".equalsIgnoreCase(normalized)
                || "show".equalsIgnoreCase(normalized)
                || "on".equalsIgnoreCase(normalized);
    }
}
