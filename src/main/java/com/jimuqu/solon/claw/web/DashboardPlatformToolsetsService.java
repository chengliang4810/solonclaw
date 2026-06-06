package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 提供控制台平台Toolsets相关业务能力，封装调用方不需要感知的运行细节。 */
public class DashboardPlatformToolsetsService {
    /** SUPPORTEDPLATFORMS的统一常量值。 */
    private static final List<String> SUPPORTED_PLATFORMS =
            Arrays.asList("feishu", "dingtalk", "wecom", "weixin", "qqbot", "yuanbao");

    /** 注入应用配置，用于控制台平台Toolsets。 */
    private final AppConfig appConfig;

    /** 注入控制台配置服务，用于调用对应业务能力。 */
    private final DashboardConfigService dashboardConfigService;

    /**
     * 创建控制台平台Toolsets服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param dashboardConfigService dashboard配置Service配置对象。
     */
    public DashboardPlatformToolsetsService(
            AppConfig appConfig, DashboardConfigService dashboardConfigService) {
        this.appConfig = appConfig;
        this.dashboardConfigService = dashboardConfigService;
    }

    /**
     * 执行overview相关逻辑。
     *
     * @return 返回overview结果。
     */
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> platformSummary = new LinkedHashMap<String, Object>();
        for (String platform : SUPPORTED_PLATFORMS) {
            platformSummary.put(platform, platformConfig(platform));
        }
        result.put("platforms", platformSummary);
        return result;
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param platform 平台参数。
     * @param body 请求体或消息正文内容。
     * @return 返回更新结果。
     */
    public Map<String, Object> update(String platform, Map<String, Object> body) {
        String normalizedPlatform = normalizePlatform(platform);
        List<String> enabledToolsets =
                normalizeToolsets(body == null ? null : body.get("enabledToolsets"));
        List<String> disabledToolsets =
                normalizeToolsets(body == null ? null : body.get("disabledToolsets"));
        boolean approvalRequired = parseBoolean(body == null ? null : body.get("approvalRequired"));

        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put(platformKey(normalizedPlatform, "enabledToolsets"), enabledToolsets);
        updates.put(platformKey(normalizedPlatform, "disabledToolsets"), disabledToolsets);
        updates.put(
                platformKey(normalizedPlatform, "approvalRequired"),
                Boolean.valueOf(approvalRequired));
        dashboardConfigService.savePartialFlat(updates, false);
        return platformConfig(normalizedPlatform);
    }

    /**
     * 执行平台配置相关逻辑。
     *
     * @param platform 平台参数。
     * @return 返回平台配置。
     */
    private Map<String, Object> platformConfig(String platform) {
        AppConfig.PlatformConfig config = readPlatformConfig(platform);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("platform", platform);
        result.put("enabledToolsets", copyList(config.getEnabledToolsets()));
        result.put("disabledToolsets", copyList(config.getDisabledToolsets()));
        result.put("approvalRequired", Boolean.valueOf(config.isApprovalRequired()));
        return result;
    }

    /**
     * 读取平台配置。
     *
     * @param platform 平台参数。
     * @return 返回读取到的平台配置。
     */
    private AppConfig.PlatformConfig readPlatformConfig(String platform) {
        Map<String, AppConfig.PlatformConfig> platforms = appConfig.getGateway().getPlatforms();
        AppConfig.PlatformConfig config =
                platforms == null ? null : platforms.get(platform.toUpperCase());
        return config == null ? new AppConfig.PlatformConfig() : config;
    }

    /**
     * 规范化平台。
     *
     * @param platform 平台参数。
     * @return 返回平台结果。
     */
    private String normalizePlatform(String platform) {
        String value = StrUtil.nullToEmpty(platform).trim().toLowerCase();
        if (!SUPPORTED_PLATFORMS.contains(value)) {
            throw new IllegalArgumentException("Unsupported platform: " + platform);
        }
        return value;
    }

    /**
     * 规范化Toolsets。
     *
     * @param raw 原始输入值。
     * @return 返回Toolsets结果。
     */
    @SuppressWarnings("unchecked")
    private List<String> normalizeToolsets(Object raw) {
        List<Object> items = new ArrayList<Object>();
        if (raw == null) {
            return Collections.emptyList();
        }
        if (raw instanceof Iterable) {
            for (Object item : (Iterable<Object>) raw) {
                items.add(item);
            }
        } else if (raw instanceof String) {
            String text = (String) raw;
            if (StrUtil.isBlank(text)) {
                return Collections.emptyList();
            }
            items.addAll(Arrays.asList(text.split(",")));
        } else {
            throw new IllegalArgumentException(
                    "工具集列表必须是字符串数组或逗号字符串 / Toolsets must be an array or comma-separated string");
        }

        List<String> result = new ArrayList<String>();
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            String value = String.valueOf(item).trim();
            if (StrUtil.isBlank(value) || result.contains(value)) {
                continue;
            }
            result.add(value);
        }
        return result;
    }

    /**
     * 解析Boolean。
     *
     * @param raw 原始输入值。
     * @return 返回解析后的Boolean。
     */
    private boolean parseBoolean(Object raw) {
        if (raw instanceof Boolean) {
            return ((Boolean) raw).booleanValue();
        }
        String value = StrUtil.nullToEmpty(raw == null ? null : String.valueOf(raw)).trim();
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    /**
     * 复制List。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回List结果。
     */
    private List<String> copyList(List<String> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        return new ArrayList<String>(values);
    }

    /**
     * 执行平台键相关逻辑。
     *
     * @param platform 平台参数。
     * @param field field 参数。
     * @return 返回平台键结果。
     */
    private String platformKey(String platform, String field) {
        return "gateway.platforms." + platform.toUpperCase() + "." + field;
    }
}
