package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.IdUtil;

/** ID 生成辅助类。 */
public final class IdSupport {
    /** 创建标识辅助实例。 */
    private IdSupport() {}

    /** 生成无分隔符 UUID。 */
    public static String newId() {
        return IdUtil.fastSimpleUUID();
    }
}
