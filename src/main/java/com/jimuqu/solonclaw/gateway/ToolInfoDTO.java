package com.jimuqu.solonclaw.gateway;

import java.util.List;

/**
 * 工具信息 DTO（数据传输对象）
 * <p>
 * 简化版工具信息，避免循环引用导致的序列化问题
 *
 * @author SolonClaw
 */
public record ToolInfoDTO(
        String name,
        String description,
        List<ParameterInfoDTO> parameters
) {
    /**
     * 参数信息 DTO
     */
    public record ParameterInfoDTO(
            String name,
            String description,
            String type
    ) {}
}
