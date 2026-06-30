package com.jimuqu.solon.claw.skillhub.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** bundle 路径安全校验。 */
public final class SkillBundlePathSupport {
    /** 创建技能包路径辅助实例。 */
    private SkillBundlePathSupport() {}

    /**
     * 规范化技能名称。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回技能名称结果。
     */
    public static String normalizeSkillName(String value) {
        return normalize(value, false, "skill name");
    }

    /**
     * 规范化Category名称。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Category名称结果。
     */
    public static String normalizeCategoryName(String value) {
        return normalize(value, false, "category");
    }

    /**
     * 规范化包路径。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回包路径。
     */
    public static String normalizeBundlePath(String value) {
        return normalize(value, true, "bundle path");
    }

    /**
     * 解析Under根用户。
     *
     * @param rootDir 文件或目录路径参数。
     * @param relativePath 文件或目录路径参数。
     * @return 返回解析后的Under根用户。
     */
    public static File resolveUnderRoot(File rootDir, String relativePath) {
        String normalized = normalizeBundlePath(relativePath);
        File target = new File(rootDir, normalized.replace('/', File.separatorChar));
        return requireCanonicalUnderRoot(rootDir, target, "bundle path");
    }

    /**
     * 要求规范Under根用户。
     *
     * @param rootDir 文件或目录路径参数。
     * @param target target 参数。
     * @param fieldName field名称参数。
     * @return 返回规范Under根用户结果。
     */
    public static File requireCanonicalUnderRoot(File rootDir, File target, String fieldName) {
        if (rootDir == null || target == null) {
            throw unsafe(fieldName, null);
        }
        try {
            Path rootPath = resolveRealPath(rootDir);
            Path targetPath = resolveRealPath(target);
            if (targetPath.equals(rootPath) || targetPath.startsWith(rootPath)) {
                return target;
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unsafe " + fieldName + ": cannot resolve canonical path", e);
        }
        throw unsafe(fieldName, target.getPath());
    }

    /**
     * 解析真实文件系统路径，已存在的路径段会跟随链接，未创建的尾部路径保留原始名称。
     *
     * @param file 文件或目录路径参数。
     * @return 返回可用于根目录边界比较的真实路径。
     * @throws IOException 路径无法解析时抛出。
     */
    private static Path resolveRealPath(File file) throws IOException {
        Path path = file.toPath().toAbsolutePath().normalize();
        if (Files.exists(path)) {
            return path.toRealPath();
        }
        List<Path> missingParts = new ArrayList<Path>();
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            missingParts.add(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            return path;
        }
        Path resolved = existing.toRealPath();
        for (int i = missingParts.size() - 1; i >= 0; i--) {
            resolved = resolved.resolve(missingParts.get(i).toString());
        }
        return resolved.normalize();
    }

    /**
     * 执行规范化相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param allowNested allowNested开关值。
     * @param fieldName field名称参数。
     * @return 返回规范化结果。
     */
    private static String normalize(String value, boolean allowNested, String fieldName) {
        if (StrUtil.isBlank(value)) {
            throw new IllegalStateException("Unsafe " + fieldName + ": empty path");
        }
        String normalized = value.trim().replace('\\', '/');
        if (".".equals(normalized)
                || normalized.startsWith("/")
                || normalized.startsWith("./")
                || new File(normalized).isAbsolute()
                || normalized.toLowerCase(Locale.ROOT).matches("^[a-z]:/.*")) {
            throw unsafe(fieldName, value);
        }

        String[] rawParts = normalized.split("/", -1);
        List<String> parts = new ArrayList<String>();
        for (String rawPart : rawParts) {
            String part = rawPart.trim();
            if (part.length() == 0
                    || ".".equals(part)
                    || "..".equals(part)
                    || part.contains("..")) {
                throw unsafe(fieldName, value);
            }
            if (part.contains(":") || containsControlCharacter(part)) {
                throw unsafe(fieldName, value);
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            throw unsafe(fieldName, value);
        }
        if (!allowNested && parts.size() != 1) {
            throw unsafe(fieldName, value);
        }
        return String.join("/", parts);
    }

    /**
     * 判断是否包含控制Character。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回contains Control Character结果。
     */
    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行un安全相关逻辑。
     *
     * @param fieldName field名称参数。
     * @param value 待规范化或校验的原始值。
     * @return 返回unsafe结果。
     */
    private static IllegalStateException unsafe(String fieldName, String value) {
        return new IllegalStateException("Unsafe " + fieldName + ": " + safeValue(value));
    }

    /**
     * 生成安全展示用的值。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Value结果。
     */
    private static String safeValue(String value) {
        String text = StrUtil.nullToEmpty(value).replace('\\', '/').trim();
        if (text.length() == 0) {
            return "empty path";
        }
        int slash = text.lastIndexOf('/');
        String name = slash >= 0 ? text.substring(slash + 1) : text;
        if (name.length() == 0) {
            name = "[path]";
        }
        return SecretRedactor.redact(name, 400);
    }
}
