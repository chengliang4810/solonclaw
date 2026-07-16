package com.jimuqu.solon.claw.tool.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * BOM 文件处理支持工具类，封装 BOM 检测、保留写入等通用逻辑。
 *
 * <p>供 SolonClawFileReadWriteSkill、SolonClawPatchTools 等文件操作工具共享使用，消除跨文件重复代码。
 */
final class BomFileSupport {

    /** UTF-8 BOM 标记。 */
    static final String UTF8_BOM = "\uFEFF";

    private BomFileSupport() {
        // 工具类不允许实例化
    }

    /**
     * 判断文件是否存在 UTF-8 BOM。
     *
     * @param target 目标文件路径。
     * @return 存在 BOM 返回 true；文件不存在、是目录或读取失败时返回 false。
     */
    static boolean hasLeadingBom(Path target) {
        if (target == null || !Files.exists(target) || Files.isDirectory(target)) {
            return false;
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            return bytes.length >= 3
                    && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF;
        } catch (Exception e) {
            // 检查失败时按无 BOM 处理
            return false;
        }
    }

    /**
     * 去除字符串开头的 UTF-8 BOM。
     *
     * @param text 原始文本。
     * @return 去除 BOM 后的文本。
     */
    static String stripLeadingBom(String text) {
        if (text != null && text.startsWith(UTF8_BOM)) {
            return text.substring(UTF8_BOM.length());
        }
        return text;
    }

    /**
     * 读取文件内容并去除 BOM。
     *
     * @param target 目标文件路径。
     * @return 文件内容（已去除 BOM）；文件不存在或为目录时返回空字符串。
     * @throws IOException 读取失败时抛出异常。
     */
    static String readContentStripBom(Path target) throws IOException {
        if (!Files.exists(target) || Files.isDirectory(target)) {
            return "";
        }
        return stripLeadingBom(new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    /**
     * 判断写入时是否需要保留 BOM。
     *
     * <p>如果原文件有 BOM 但新内容没有，则在新内容前添加 BOM。
     *
     * @param target 目标文件路径。
     * @param value 待写入内容。
     * @return 处理后的内容（可能已添加 BOM）。
     */
    static String preserveBomIfNeeded(Path target, String value) {
        if (hasLeadingBom(target) && !value.startsWith(UTF8_BOM)) {
            return UTF8_BOM + value;
        }
        return value;
    }
}
