package com.jimuqu.solon.claw.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 验证文件路径包含关系判断，避免兄弟目录被误判为子目录。 */
class FilePathSupportTest {
    /** 每个用例后恢复用户主目录属性，避免污染其它路径测试。 */
    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", ORIGINAL_USER_HOME);
    }

    /** 测试启动时的用户主目录。 */
    private static final String ORIGINAL_USER_HOME = System.getProperty("user.home");

    /** 根目录自身和子目录应通过，名称前缀相同的兄弟目录不能通过。 */
    @Test
    void shouldMatchOnlyRootOrChildren() {
        File root = new File("/tmp/cache/media");
        assertTrue(FilePathSupport.isUnderPath(root, root));
        assertTrue(FilePathSupport.isUnderPath(new File("/tmp/cache/media/a/b.png"), root));
        assertFalse(FilePathSupport.isUnderPath(new File("/tmp/cache/media-other/b.png"), root));
    }

    /** 规范化后逃逸根目录的路径不能通过。 */
    @Test
    void shouldRejectNormalizedEscapes() {
        File root = new File("/tmp/cache/media");

        assertFalse(FilePathSupport.isUnderPath(new File("/tmp/cache/media/../secret.txt"), root));
    }

    /** 只展开独立的 ~ 或 ~/ 前缀路径。 */
    @Test
    void shouldExpandUserHomePrefix() {
        System.setProperty("user.home", "/home/tester");

        assertEquals("/home/tester", FilePathSupport.expandUserHome("~"));
        assertEquals("/home/tester/work", FilePathSupport.expandUserHome("~/work"));
        assertEquals("~other/work", FilePathSupport.expandUserHome("~other/work"));
        assertEquals("", FilePathSupport.expandUserHome(""));
    }
}
