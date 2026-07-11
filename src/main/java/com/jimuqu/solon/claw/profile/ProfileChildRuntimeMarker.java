package com.jimuqu.solon.claw.profile;

/** 标记命名 Profile 的独立子容器，使装配层排除主机级 Dashboard 与 HTTP 组件。 */
public final class ProfileChildRuntimeMarker {
    /** 创建仅在 Profile 子容器中登记的标记对象。 */
    public ProfileChildRuntimeMarker() {}
}
