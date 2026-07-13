package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.crypto.digest.DigestUtil;
import com.jimuqu.solon.claw.skillhub.support.SkillHubContentSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 校验技能目录内容摘要的边界编码。 */
public class SkillHubContentSupportTest {

    /** 完整扫描摘要必须分隔每个文件内容与下一相对路径，短摘要继续沿用旧口径。 */
    @Test
    void shouldSeparateFullHashFileContentFromFollowingPath() throws Exception {
        Path splitFiles = Files.createTempDirectory("skill-hash-split");
        Files.write(splitFiles.resolve("a"), new byte[] {'b'});
        Files.write(splitFiles.resolve("c"), new byte[0]);
        Path embeddedBoundary = Files.createTempDirectory("skill-hash-embedded");
        Files.write(embeddedBoundary.resolve("a"), new byte[] {'b', 'c', 0});

        assertThat(SkillHubContentSupport.fullContentHash(splitFiles.toFile()))
                .isNotEqualTo(SkillHubContentSupport.fullContentHash(embeddedBoundary.toFile()));
        assertThat(SkillHubContentSupport.contentHash(splitFiles.toFile()))
                .isEqualTo("sha256:" + DigestUtil.sha256Hex("abc").substring(0, 16));
    }
}
