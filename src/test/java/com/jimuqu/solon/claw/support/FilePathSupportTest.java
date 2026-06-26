package com.jimuqu.solon.claw.support;

import java.io.File;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证文件路径包含关系判断，避免兄弟目录被误判为子目录。 */
class FilePathSupportTest {
    /** 根目录自身和子目录应通过，名称前缀相同的兄弟目录不能通过。 */
    @Test
    void shouldMatchOnlyRootOrChildren() {
        File root = new File("/tmp/cache/media");
        assertTrue(FilePathSupport.isUnderPath(root, root));
        assertTrue(FilePathSupport.isUnderPath(new File("/tmp/cache/media/a/b.png"), root));
        assertFalse(FilePathSupport.isUnderPath(new File("/tmp/cache/media-other/b.png"), root));
    }
}
