package com.jimuqu.solon.claw.support;

import java.io.File;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证运行时路径解析工具的兜底语义。 */
class RuntimePathSupportTest {
    /** code source 不是 Jar 文件时应回退到调用方提供的启动目录。 */
    @Test
    void shouldFallbackWhenCodeSourceIsDirectory() {
        File fallback = new File(System.getProperty("user.dir")).getAbsoluteFile();
        assertEquals(fallback, RuntimePathSupport.jarBaseDir(RuntimePathSupportTest.class, fallback));
    }
}
