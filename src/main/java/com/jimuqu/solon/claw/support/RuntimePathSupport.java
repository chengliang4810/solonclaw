package com.jimuqu.solon.claw.support;

import java.io.File;

/** 运行时路径解析工具，集中处理启动目录、Jar 目录等基础路径。 */
public final class RuntimePathSupport {
    /** 工具类不保存状态，禁止创建实例。 */
    private RuntimePathSupport() {}

    /**
     * 解析运行 Jar 所在目录；测试或解包运行时回退到当前进程目录。
     *
     * @param anchor 用于定位代码来源的类。
     * @param fallbackBase 无法识别 Jar 路径时使用的目录。
     * @return 返回用于解析工作区相对路径的基准目录。
     */
    public static File jarBaseDir(Class<?> anchor, File fallbackBase) {
        try {
            java.net.URL location = anchor.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return fallbackBase;
            }
            File file = new File(location.toURI()).getAbsoluteFile();
            if (file.isFile()) {
                File parent = file.getParentFile();
                return parent == null ? fallbackBase : parent;
            }
        } catch (Exception e) {
            return fallbackBase;
        }
        return fallbackBase;
    }
}
