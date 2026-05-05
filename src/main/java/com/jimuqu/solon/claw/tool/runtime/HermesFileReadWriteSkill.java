package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.skills.file.FileReadWriteSkill;

/** Solon AI file skill wrapped with Hermes-style path and credential guardrails. */
public class HermesFileReadWriteSkill extends FileReadWriteSkill {
    private final SecurityPolicyService securityPolicyService;

    public HermesFileReadWriteSkill(String workDir, SecurityPolicyService securityPolicyService) {
        super(workDir);
        this.securityPolicyService = securityPolicyService;
    }

    @Override
    @ToolMapping(name = "file_write", description = "写入文本到文件。会自动创建不存在的目录。")
    public String write(@Param("fileName") String fileName, @Param("content") String content) {
        assertSafe(ToolNameConstants.FILE_WRITE, fileName);
        return super.write(fileName, content);
    }

    @Override
    @ToolMapping(name = "file_read", description = "读取文本文件内容。")
    public String read(@Param("fileName") String fileName) {
        assertSafe(ToolNameConstants.FILE_READ, fileName);
        return super.read(fileName);
    }

    @Override
    @ToolMapping(name = "file_list", description = "列出指定目录下的文件和子目录。如果不指定目录，则列出根目录。")
    public String list(@Param(value = "dirName", required = false) String dirName) {
        assertSafe(ToolNameConstants.FILE_LIST, dirName);
        return super.list(dirName);
    }

    @Override
    @ToolMapping(name = "file_delete", description = "删除指定文件或空目录。")
    public String delete(@Param("fileName") String fileName) {
        assertSafe(ToolNameConstants.FILE_DELETE, fileName);
        return super.delete(fileName);
    }

    private void assertSafe(String toolName, String path) {
        if (securityPolicyService == null || StrUtil.isBlank(path)) {
            return;
        }
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", path);
        args.put("dirName", path);
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs(toolName, args);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(blockedMessage(toolName, verdict));
        }
    }

    private String blockedMessage(String toolName, SecurityPolicyService.FileVerdict verdict) {
        return "BLOCKED: 文件安全策略阻止访问："
                + verdict.getMessage()
                + "\n工具："
                + toolName
                + "\n路径："
                + StrUtil.nullToEmpty(verdict.getPath())
                + "\n请改用工作区内的普通项目文件，敏感凭据文件不能通过 Agent 工具读取、写入或删除。";
    }
}
