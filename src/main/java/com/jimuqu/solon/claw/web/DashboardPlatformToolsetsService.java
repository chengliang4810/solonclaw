package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard platform toolsets API service. */
public class DashboardPlatformToolsetsService {
    private static final List<String> SUPPORTED_PLATFORMS =
            Arrays.asList("feishu", "dingtalk", "wecom", "weixin", "qqbot", "yuanbao");

    private final AppConfig appConfig;
    private final DashboardConfigService dashboardConfigService;

    public DashboardPlatformToolsetsService(
            AppConfig appConfig, DashboardConfigService dashboardConfigService) {
        this.appConfig = appConfig;
        this.dashboardConfigService = dashboardConfigService;
    }

    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> platformSummary = new LinkedHashMap<String, Object>();
        for (String platform : SUPPORTED_PLATFORMS) {
            platformSummary.put(platform, platformConfig(platform));
        }
        result.put("platforms", platformSummary);
        return result;
    }

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

    private Map<String, Object> platformConfig(String platform) {
        AppConfig.PlatformConfig config = readPlatformConfig(platform);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("platform", platform);
        result.put("enabledToolsets", copyList(config.getEnabledToolsets()));
        result.put("disabledToolsets", copyList(config.getDisabledToolsets()));
        result.put("approvalRequired", Boolean.valueOf(config.isApprovalRequired()));
        return result;
    }

    private AppConfig.PlatformConfig readPlatformConfig(String platform) {
        Map<String, AppConfig.PlatformConfig> platforms = appConfig.getGateway().getPlatforms();
        AppConfig.PlatformConfig config =
                platforms == null ? null : platforms.get(platform.toUpperCase());
        return config == null ? new AppConfig.PlatformConfig() : config;
    }

    private String normalizePlatform(String platform) {
        String value = StrUtil.nullToEmpty(platform).trim().toLowerCase();
        if (!SUPPORTED_PLATFORMS.contains(value)) {
            throw new IllegalArgumentException("Unsupported platform: " + platform);
        }
        return value;
    }

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

    private boolean parseBoolean(Object raw) {
        if (raw instanceof Boolean) {
            return ((Boolean) raw).booleanValue();
        }
        String value = StrUtil.nullToEmpty(raw == null ? null : String.valueOf(raw)).trim();
        return "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value);
    }

    private List<String> copyList(List<String> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        return new ArrayList<String>(values);
    }

    private String platformKey(String platform, String field) {
        return "gateway.platforms." + platform.toUpperCase() + "." + field;
    }
}
