package com.jimuqu.solon.claw.profile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** 使用 JDK 标准库读写受限 tar.gz，专门服务 Profile 本地导出与安全导入。 */
final class ProfileArchive {
    /** tar 固定块大小。 */
    private static final int BLOCK_SIZE = 512;

    /** 单个归档最多允许的成员数，避免恶意归档耗尽 inode。 */
    private static final int MAX_ENTRIES = 100000;

    /** 单个归档最多解压八 GiB 数据，超过时拒绝继续写盘。 */
    private static final long MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L;

    /** 单个 PAX/GNU 扩展头最大一 MiB，足以承载长路径且避免恶意分配。 */
    private static final int MAX_EXTENDED_HEADER_BYTES = 1024 * 1024;

    /** 工具类不保存实例状态。 */
    private ProfileArchive() {}

    /**
     * 将目录写为包含普通文件、目录和原样符号链接的 tar.gz。
     *
     * @param source 已准备好的安全导出目录。
     * @param rootName 归档内唯一顶层目录名。
     * @param output 输出归档路径。
     * @throws IOException 文件读取或归档写入失败。
     */
    static void create(Path source, String rootName, Path output) throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            throw new IOException("Profile export source is not a directory: " + source);
        }
        Path rootPath = safeRelativePath(rootName);
        if (rootPath.getNameCount() != 1) {
            throw new IllegalArgumentException(
                    "Archive root must be one directory name: " + rootName);
        }
        Path absoluteOutput = output.toAbsolutePath().normalize();
        Path parent = absoluteOutput.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = absoluteOutput.resolveSibling(absoluteOutput.getFileName() + ".tmp");
        try (OutputStream file =
                        Files.newOutputStream(
                                temporary,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE);
                GZIPOutputStream gzip = new GZIPOutputStream(new BufferedOutputStream(file))) {
            writePortableHeader(
                    gzip,
                    rootName + "/",
                    0L,
                    (byte) '5',
                    fileMode(source, 0755),
                    "",
                    Files.getLastModifiedTime(source).toMillis());
            Files.walkFileTree(
                    source,
                    new SimpleFileVisitor<Path>() {
                        /** 写入子目录条目。 */
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) throws IOException {
                            if (dir.equals(source)) {
                                return FileVisitResult.CONTINUE;
                            }
                            String name = archiveName(rootName, source.relativize(dir), true);
                            writePortableHeader(
                                    gzip,
                                    name,
                                    0L,
                                    (byte) '5',
                                    fileMode(dir, 0755),
                                    "",
                                    attrs.lastModifiedTime().toMillis());
                            return FileVisitResult.CONTINUE;
                        }

                        /** 写入普通文件内容或原样链接，同时拒绝设备文件。 */
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            String name = archiveName(rootName, source.relativize(file), false);
                            if (Files.isSymbolicLink(file)) {
                                writePortableHeader(
                                        gzip,
                                        name,
                                        0L,
                                        (byte) '2',
                                        0777,
                                        Files.readSymbolicLink(file).toString(),
                                        attrs.lastModifiedTime().toMillis());
                                return FileVisitResult.CONTINUE;
                            }
                            if (!attrs.isRegularFile()) {
                                throw new IOException(
                                        "Profile archive only supports regular files: " + file);
                            }
                            writePortableHeader(
                                    gzip,
                                    name,
                                    attrs.size(),
                                    (byte) '0',
                                    fileMode(file, 0644),
                                    "",
                                    attrs.lastModifiedTime().toMillis());
                            try (InputStream input =
                                    new BufferedInputStream(Files.newInputStream(file))) {
                                copy(input, gzip, attrs.size());
                            }
                            writePadding(gzip, attrs.size());
                            return FileVisitResult.CONTINUE;
                        }
                    });
            gzip.write(new byte[BLOCK_SIZE * 2]);
            gzip.finish();
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        moveReplacing(temporary, absoluteOutput);
    }

    /**
     * 安全解压 tar.gz 并返回归档内唯一顶层目录名。
     *
     * @param archive 输入归档。
     * @param destination 空的临时解压目录。
     * @return 归档内唯一顶层目录名。
     * @throws IOException 归档损坏、成员越界或写盘失败。
     */
    static String extract(Path archive, Path destination) throws IOException {
        if (archive == null || !Files.isRegularFile(archive)) {
            throw new IOException("Profile archive not found: " + archive);
        }
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        String archiveRoot = null;
        int entries = 0;
        long totalBytes = 0L;
        Map<String, String> pendingExtended = new LinkedHashMap<String, String>();
        try (InputStream file = Files.newInputStream(archive);
                GZIPInputStream gzip = new GZIPInputStream(new BufferedInputStream(file))) {
            byte[] header = new byte[BLOCK_SIZE];
            while (readBlock(gzip, header)) {
                if (allZero(header)) {
                    break;
                }
                verifyChecksum(header);
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("Profile archive contains too many entries.");
                }
                long size = parseOctal(header, 124, 12);
                int mode = (int) parseOctal(header, 100, 8);
                byte type = header[156];
                if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                    totalBytes = addExtractedBytes(totalBytes, size);
                    byte[] payload = readExtendedHeader(gzip, size);
                    skipPadding(gzip, size);
                    if (type == 'x') {
                        pendingExtended.putAll(parsePaxRecords(payload));
                    } else if (type == 'L') {
                        pendingExtended.put("path", decodeExtendedText(payload));
                    } else if (type == 'K') {
                        pendingExtended.put("linkpath", decodeExtendedText(payload));
                    }
                    Arrays.fill(header, (byte) 0);
                    continue;
                }
                String memberName =
                        pendingExtended.containsKey("path")
                                ? pendingExtended.get("path")
                                : readName(header);
                if (pendingExtended.containsKey("size")) {
                    size = parseExtendedSize(pendingExtended.get("size"));
                }
                pendingExtended.clear();
                totalBytes = addExtractedBytes(totalBytes, size);
                Path relative = safeRelativePath(memberName);
                String currentRoot = relative.getName(0).toString();
                if (archiveRoot == null) {
                    archiveRoot = currentRoot;
                } else if (!archiveRoot.equals(currentRoot)) {
                    throw new IOException(
                            "Profile archive must contain exactly one top-level directory.");
                }
                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Unsafe profile archive member: " + memberName);
                }
                if (type == '5') {
                    if (size != 0L) {
                        throw new IOException("Invalid directory entry size: " + memberName);
                    }
                    Files.createDirectories(target);
                } else if (type == 0 || type == '0') {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    if (Files.exists(target)) {
                        throw new IOException("Duplicate profile archive member: " + memberName);
                    }
                    try (OutputStream output =
                            new BufferedOutputStream(
                                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))) {
                        copy(gzip, output, size);
                    }
                    skipPadding(gzip, size);
                    applyFileMode(target, mode);
                } else {
                    throw new IOException("Unsupported profile archive member type: " + memberName);
                }
                Arrays.fill(header, (byte) 0);
            }
        }
        if (archiveRoot == null) {
            throw new IOException("Profile archive is empty.");
        }
        Path extractedRoot = root.resolve(archiveRoot).normalize();
        if (!Files.isDirectory(extractedRoot)) {
            throw new IOException("Profile archive root is missing: " + archiveRoot);
        }
        return archiveRoot;
    }

    /**
     * 将归档成员名转换为安全相对路径。
     *
     * @param raw 归档成员原始名称。
     * @return 已校验的相对路径。
     */
    static Path safeRelativePath(String raw) {
        if (raw == null || raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Unsafe archive member path: " + raw);
        }
        String normalized = raw.replace('\\', '/');
        if (normalized.length() == 0
                || normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Unsafe archive member path: " + raw);
        }
        Path result = Paths.get("");
        boolean found = false;
        for (String part : normalized.split("/")) {
            if (part.length() == 0 || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("Unsafe archive member path: " + raw);
            }
            result = result.resolve(part);
            found = true;
        }
        if (!found || result.isAbsolute()) {
            throw new IllegalArgumentException("Unsafe archive member path: " + raw);
        }
        return result.normalize();
    }

    /** 读取受限大小的 PAX/GNU 扩展头正文。 */
    private static byte[] readExtendedHeader(InputStream input, long size) throws IOException {
        if (size < 0L || size > MAX_EXTENDED_HEADER_BYTES) {
            throw new IOException("Profile archive extended header is too large.");
        }
        byte[] result = new byte[(int) size];
        int offset = 0;
        while (offset < result.length) {
            int read = input.read(result, offset, result.length - offset);
            if (read < 0) {
                throw new IOException("Truncated profile archive extended header.");
            }
            offset += read;
        }
        return result;
    }

    /** 解析 POSIX PAX 长路径与元数据记录。 */
    private static Map<String, String> parsePaxRecords(byte[] payload) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        int offset = 0;
        while (offset < payload.length) {
            int space = offset;
            while (space < payload.length && payload[space] != (byte) ' ') {
                if (payload[space] < (byte) '0' || payload[space] > (byte) '9') {
                    throw new IOException("Invalid PAX record length.");
                }
                space++;
            }
            if (space == offset || space >= payload.length) {
                throw new IOException("Invalid PAX record length.");
            }
            int length;
            try {
                length =
                        Integer.parseInt(
                                new String(
                                        payload,
                                        offset,
                                        space - offset,
                                        StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid PAX record length.", e);
            }
            if (length <= space - offset + 2 || length > payload.length - offset) {
                throw new IOException("Invalid PAX record boundary.");
            }
            int end = offset + length;
            if (payload[end - 1] != (byte) '\n') {
                throw new IOException("Invalid PAX record boundary.");
            }
            String record = decodeUtf8(payload, space + 1, end - space - 2);
            int equals = record.indexOf('=');
            if (equals <= 0) {
                throw new IOException("Invalid PAX record.");
            }
            result.put(record.substring(0, equals), record.substring(equals + 1));
            offset = end;
        }
        return result;
    }

    /** 解码 GNU 长名称正文并移除格式尾部的 NUL/换行。 */
    private static String decodeExtendedText(byte[] payload) throws IOException {
        int length = payload.length;
        while (length > 0 && (payload[length - 1] == 0 || payload[length - 1] == (byte) '\n')) {
            length--;
        }
        return decodeUtf8(payload, 0, length);
    }

    /** 严格解码 UTF-8，拒绝用替换字符掩盖损坏路径。 */
    private static String decodeUtf8(byte[] payload, int offset, int length) throws IOException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload, offset, length))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid UTF-8 in profile archive extended header.", e);
        }
    }

    /** 解析 PAX 十进制 size 字段并应用归档总大小边界。 */
    private static long parseExtendedSize(String value) throws IOException {
        try {
            long size = Long.parseLong(value);
            if (size < 0L) {
                throw new NumberFormatException("negative");
            }
            return size;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid PAX size value.", e);
        }
    }

    /** 累计归档正文大小并保持八 GiB 解压上限。 */
    private static long addExtractedBytes(long total, long size) throws IOException {
        if (size < 0L || size > MAX_TOTAL_BYTES || total > MAX_TOTAL_BYTES - size) {
            throw new IOException("Profile archive exceeds the extraction size limit.");
        }
        return total + size;
    }

    /** 生成使用正斜杠的归档成员名。 */
    private static String archiveName(String rootName, Path relative, boolean directory) {
        String suffix = relative.toString().replace('\\', '/');
        return rootName + "/" + suffix + (directory ? "/" : "");
    }

    /** 超出 ustar 字段时先写 PAX 扩展头，再写兼容占位头。 */
    private static void writePortableHeader(
            OutputStream output,
            String name,
            long size,
            byte type,
            int mode,
            String linkName,
            long modifiedMillis)
            throws IOException {
        Map<String, String> pax = new LinkedHashMap<String, String>();
        String headerName = name;
        String headerLink = linkName == null ? "" : linkName;
        if (!canWriteName(name)) {
            pax.put("path", name);
            headerName = type == (byte) '5' ? "PaxEntry/" : "PaxEntry";
        }
        if (headerLink.getBytes(StandardCharsets.UTF_8).length > 100) {
            pax.put("linkpath", headerLink);
            headerLink = "PaxLink";
        }
        if (!pax.isEmpty()) {
            writePaxHeader(output, pax, modifiedMillis);
        }
        writeHeader(output, headerName, size, type, mode, headerLink, modifiedMillis);
    }

    /** 判断路径是否可直接写入 ustar name/prefix 字段。 */
    private static boolean canWriteName(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 100) {
            return true;
        }
        int split = name.length();
        while ((split = name.lastIndexOf('/', split - 1)) > 0) {
            byte[] prefix = name.substring(0, split).getBytes(StandardCharsets.UTF_8);
            byte[] suffix = name.substring(split + 1).getBytes(StandardCharsets.UTF_8);
            if (prefix.length <= 155 && suffix.length <= 100) {
                return true;
            }
        }
        return false;
    }

    /** 写入一个仅包含 path/linkpath 的 POSIX PAX 扩展头。 */
    private static void writePaxHeader(
            OutputStream output, Map<String, String> values, long modifiedMillis)
            throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            payload.write(paxRecord(entry.getKey(), entry.getValue()));
        }
        byte[] bytes = payload.toByteArray();
        writeHeader(output, "PaxHeaders/entry", bytes.length, (byte) 'x', 0644, "", modifiedMillis);
        output.write(bytes);
        writePadding(output, bytes.length);
    }

    /** 编码带自包含字节长度的单条 PAX 记录。 */
    private static byte[] paxRecord(String key, String value) {
        byte[] body = (key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8);
        int length = body.length + 2;
        while (true) {
            int next = Integer.toString(length).length() + 1 + body.length;
            if (next == length) {
                break;
            }
            length = next;
        }
        byte[] prefix = (Integer.toString(length) + " ").getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(body, 0, result, prefix.length, body.length);
        return result;
    }

    /** 写入一个 ustar 头块。 */
    private static void writeHeader(
            OutputStream output,
            String name,
            long size,
            byte type,
            int mode,
            String linkName,
            long modifiedMillis)
            throws IOException {
        byte[] header = new byte[BLOCK_SIZE];
        writeName(header, name);
        writeOctal(header, 100, 8, mode);
        writeOctal(header, 108, 8, 0L);
        writeOctal(header, 116, 8, 0L);
        writeOctal(header, 124, 12, size);
        writeOctal(header, 136, 12, Math.max(0L, modifiedMillis / 1000L));
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = type;
        writeLinkName(header, linkName);
        writeAscii(header, 257, 6, "ustar\0");
        writeAscii(header, 263, 2, "00");
        writeAscii(header, 265, 32, "solonclaw");
        writeAscii(header, 297, 32, "solonclaw");
        long checksum = checksum(header);
        String value = String.format("%06o", Long.valueOf(checksum));
        writeAscii(header, 148, 6, value);
        header[154] = 0;
        header[155] = (byte) ' ';
        output.write(header);
    }

    /** 写入符号链接目标字段。 */
    private static void writeLinkName(byte[] header, String linkName) throws IOException {
        if (linkName == null || linkName.length() == 0) {
            return;
        }
        byte[] bytes = linkName.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 100) {
            throw new IOException("Profile archive link target is too long: " + linkName);
        }
        System.arraycopy(bytes, 0, header, 157, bytes.length);
    }

    /** 读取可用的 POSIX 模式；不支持 POSIX 时保留最基本的可执行位。 */
    private static int fileMode(Path path, int fallback) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            int mode = 0;
            mode |= permissions.contains(PosixFilePermission.OWNER_READ) ? 0400 : 0;
            mode |= permissions.contains(PosixFilePermission.OWNER_WRITE) ? 0200 : 0;
            mode |= permissions.contains(PosixFilePermission.OWNER_EXECUTE) ? 0100 : 0;
            mode |= permissions.contains(PosixFilePermission.GROUP_READ) ? 0040 : 0;
            mode |= permissions.contains(PosixFilePermission.GROUP_WRITE) ? 0020 : 0;
            mode |= permissions.contains(PosixFilePermission.GROUP_EXECUTE) ? 0010 : 0;
            mode |= permissions.contains(PosixFilePermission.OTHERS_READ) ? 0004 : 0;
            mode |= permissions.contains(PosixFilePermission.OTHERS_WRITE) ? 0002 : 0;
            mode |= permissions.contains(PosixFilePermission.OTHERS_EXECUTE) ? 0001 : 0;
            return mode;
        } catch (Exception e) {
            return Files.isExecutable(path) ? fallback | 0111 : fallback;
        }
    }

    /** 恢复归档普通文件的 POSIX 权限；不支持时仅恢复可执行位。 */
    private static void applyFileMode(Path path, int mode) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            addPermission(permissions, mode, 0400, PosixFilePermission.OWNER_READ);
            addPermission(permissions, mode, 0200, PosixFilePermission.OWNER_WRITE);
            addPermission(permissions, mode, 0100, PosixFilePermission.OWNER_EXECUTE);
            addPermission(permissions, mode, 0040, PosixFilePermission.GROUP_READ);
            addPermission(permissions, mode, 0020, PosixFilePermission.GROUP_WRITE);
            addPermission(permissions, mode, 0010, PosixFilePermission.GROUP_EXECUTE);
            addPermission(permissions, mode, 0004, PosixFilePermission.OTHERS_READ);
            addPermission(permissions, mode, 0002, PosixFilePermission.OTHERS_WRITE);
            addPermission(permissions, mode, 0001, PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (Exception e) {
            if ((mode & 0111) != 0) {
                path.toFile().setExecutable(true, false);
            }
        }
    }

    /** 按位将单个 POSIX 权限加入集合。 */
    private static void addPermission(
            Set<PosixFilePermission> target, int mode, int mask, PosixFilePermission permission) {
        if ((mode & mask) != 0) {
            target.add(permission);
        }
    }

    /** 将长路径按 ustar name/prefix 字段拆分。 */
    private static void writeName(byte[] header, String name) throws IOException {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 100) {
            System.arraycopy(bytes, 0, header, 0, bytes.length);
            return;
        }
        int split = name.length();
        while ((split = name.lastIndexOf('/', split - 1)) > 0) {
            String prefix = name.substring(0, split);
            String suffix = name.substring(split + 1);
            byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
            byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
            if (prefixBytes.length <= 155 && suffixBytes.length <= 100) {
                System.arraycopy(suffixBytes, 0, header, 0, suffixBytes.length);
                System.arraycopy(prefixBytes, 0, header, 345, prefixBytes.length);
                return;
            }
        }
        throw new IOException("Profile archive path is too long: " + name);
    }

    /** 写入定长 ASCII 字段。 */
    private static void writeAscii(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, Math.min(length, bytes.length));
    }

    /** 写入以 NUL 结尾的八进制字段。 */
    private static void writeOctal(byte[] target, int offset, int length, long value)
            throws IOException {
        String octal = Long.toOctalString(Math.max(0L, value));
        if (octal.length() > length - 1) {
            throw new IOException("Profile archive numeric field is too large: " + value);
        }
        int padding = length - 1 - octal.length();
        Arrays.fill(target, offset, offset + padding, (byte) '0');
        writeAscii(target, offset + padding, octal.length(), octal);
        target[offset + length - 1] = 0;
    }

    /** 读取并组合 ustar name/prefix 字段。 */
    private static String readName(byte[] header) {
        String name = readString(header, 0, 100);
        String prefix = readString(header, 345, 155);
        return prefix.length() == 0 ? name : prefix + "/" + name;
    }

    /** 读取 NUL 结尾字符串。 */
    private static String readString(byte[] source, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && source[end] != 0) {
            end++;
        }
        return new String(source, offset, end - offset, StandardCharsets.UTF_8);
    }

    /** 校验 tar 头部校验和，避免损坏头部被当作可信路径和长度。 */
    private static void verifyChecksum(byte[] header) throws IOException {
        long recorded = parseOctal(header, 148, 8);
        byte[] copy = header.clone();
        Arrays.fill(copy, 148, 156, (byte) ' ');
        if (recorded != checksum(copy)) {
            throw new IOException("Invalid profile archive header checksum.");
        }
    }

    /** 计算 tar 头块无符号字节和。 */
    private static long checksum(byte[] header) {
        long sum = 0L;
        for (byte value : header) {
            sum += value & 0xff;
        }
        return sum;
    }

    /** 解析 tar 八进制数字字段。 */
    private static long parseOctal(byte[] source, int offset, int length) throws IOException {
        long value = 0L;
        boolean seen = false;
        for (int i = offset; i < offset + length; i++) {
            int current = source[i] & 0xff;
            if (current == 0 || current == ' ') {
                if (seen) {
                    break;
                }
                continue;
            }
            if (current < '0' || current > '7') {
                throw new IOException("Invalid profile archive octal field.");
            }
            seen = true;
            value = (value << 3) + current - '0';
        }
        return value;
    }

    /** 读取恰好一个 tar 块；干净 EOF 返回 false。 */
    private static boolean readBlock(InputStream input, byte[] block) throws IOException {
        int offset = 0;
        while (offset < block.length) {
            int read = input.read(block, offset, block.length - offset);
            if (read < 0) {
                if (offset == 0) {
                    return false;
                }
                throw new IOException("Truncated profile archive header.");
            }
            offset += read;
        }
        return true;
    }

    /** 判断头块是否为 tar 结束零块。 */
    private static boolean allZero(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    /** 复制指定字节数，遇到提前 EOF 时失败。 */
    private static void copy(InputStream input, OutputStream output, long count)
            throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = count;
        while (remaining > 0L) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Truncated profile archive entry.");
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    /** 写入文件内容后的块对齐填充。 */
    private static void writePadding(OutputStream output, long size) throws IOException {
        int padding = (int) ((BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE);
        if (padding > 0) {
            output.write(new byte[padding]);
        }
    }

    /** 跳过文件内容后的块对齐填充。 */
    private static void skipPadding(InputStream input, long size) throws IOException {
        long remaining = (BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) {
                throw new IOException("Truncated profile archive padding.");
            }
            remaining--;
        }
    }

    /** 以原子移动优先替换输出文件。 */
    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
