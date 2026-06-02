package com.jimuqu.solon.claw.skillhub.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** bundle 路径安全校验。 */
public final class SkillBundlePathSupport {
    private SkillBundlePathSupport() {}

    public static String normalizeSkillName(String value) {
        return normalize(value, false, "skill name");
    }

    public static String normalizeCategoryName(String value) {
        return normalize(value, false, "category");
    }

    public static String normalizeBundlePath(String value) {
        return normalize(value, true, "bundle path");
    }

    public static File resolveUnderRoot(File rootDir, String relativePath) {
        String normalized = normalizeBundlePath(relativePath);
        File target = new File(rootDir, normalized.replace('/', File.separatorChar));
        return requireCanonicalUnderRoot(rootDir, target, "bundle path");
    }

    public static File requireCanonicalUnderRoot(File rootDir, File target, String fieldName) {
        if (rootDir == null || target == null) {
            throw unsafe(fieldName, null);
        }
        try {
            File canonicalRoot = rootDir.getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();
            String rootPath = canonicalRoot.getAbsolutePath();
            String targetPath = canonicalTarget.getAbsolutePath();
            String rootPrefix =
                    rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
            if (targetPath.equals(rootPath) || targetPath.startsWith(rootPrefix)) {
                return target;
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unsafe " + fieldName + ": cannot resolve canonical path", e);
        }
        throw unsafe(fieldName, target.getPath());
    }

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

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException unsafe(String fieldName, String value) {
        return new IllegalStateException("Unsafe " + fieldName + ": " + safeValue(value));
    }

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
