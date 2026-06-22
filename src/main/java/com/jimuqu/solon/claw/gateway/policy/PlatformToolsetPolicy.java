package com.jimuqu.solon.claw.gateway.policy;

import cn.hutool.core.collection.CollUtil;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 平台工具集权限策略。
 *
 * <p>根据 {@code solonclaw.gateway.platforms.<platform>.enabledToolsets} 和 {@code disabledToolsets}
 * 配置，决定特定平台允许使用的工具集列表。
 *
 * <p>规则优先级：
 *
 * <ol>
 *   <li>disabledToolsets 中的工具集始终被禁用，优先级最高。
 *   <li>enabledToolsets 非空时，只允许列表中的工具集。
 *   <li>enabledToolsets 为空时，不限制（使用调用方传入的全局默认列表）。
 * </ol>
 */
public final class PlatformToolsetPolicy {

    /** 创建平台Toolset策略实例。 */
    private PlatformToolsetPolicy() {}

    /**
     * 根据平台配置过滤工具集列表。
     *
     * @param platform 目标平台
     * @param globalToolsets 全局默认工具集列表（可为 null 或空）
     * @param gatewayConfig 网关配置
     * @return 该平台实际允许使用的工具集列表，不为 null
     */
    public static List<String> resolveToolsets(
            PlatformType platform,
            List<String> globalToolsets,
            AppConfig.GatewayConfig gatewayConfig) {
        if (platform == null || gatewayConfig == null) {
            return safeList(globalToolsets);
        }

        AppConfig.PlatformConfig platformConfig = gatewayConfig.getPlatforms().get(platform.name());
        if (platformConfig == null) {
            return safeList(globalToolsets);
        }

        List<String> enabled = platformConfig.getEnabledToolsets();
        List<String> disabled = platformConfig.getDisabledToolsets();

        // enabledToolsets 非空时收紧到平台白名单，否则沿用全局工具集。
        List<String> candidates;
        if (CollUtil.isNotEmpty(enabled)) {
            candidates = new ArrayList<String>(enabled);
        } else {
            candidates = safeList(globalToolsets);
        }

        // disabledToolsets 始终覆盖候选列表，避免平台显式禁用项被全局配置重新放开。
        if (CollUtil.isNotEmpty(disabled)) {
            candidates = new ArrayList<String>(candidates);
            candidates.removeAll(disabled);
        }

        return Collections.unmodifiableList(candidates);
    }

    /**
     * 判断平台是否配置了强制审批。
     *
     * @param platform 目标平台
     * @param gatewayConfig 网关配置
     * @return 该平台是否要求审批
     */
    public static boolean isApprovalRequired(
            PlatformType platform, AppConfig.GatewayConfig gatewayConfig) {
        if (platform == null || gatewayConfig == null) {
            return false;
        }
        AppConfig.PlatformConfig platformConfig = gatewayConfig.getPlatforms().get(platform.name());
        return platformConfig != null && platformConfig.isApprovalRequired();
    }

    /**
     * 生成调用方不可变的工具集快照。
     *
     * @param list 配置或全局默认传入的工具集列表。
     * @return 不会反向修改原始配置的不可变列表。
     */
    private static List<String> safeList(List<String> list) {
        if (list == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(list));
    }
}
