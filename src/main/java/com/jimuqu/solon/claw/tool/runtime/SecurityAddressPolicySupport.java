package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 封装 URL 安全策略中的地址分类与 IPv4 变体解析，避免主策略服务承担底层地址判定细节。 */
final class SecurityAddressPolicySupport {
    /** 始终阻断的云元数据主机名，任何网络审批都不能绕过这些目标。 */
    private static final String[] ALWAYS_BLOCKED_HOSTS =
            new String[] {"metadata.google.internal", "metadata.goog"};

    /** 始终阻断的云元数据和链路本地 IP 文本，覆盖常见云厂商凭据端点。 */
    private static final String[] ALWAYS_BLOCKED_IPS =
            new String[] {
                "169.254.169.254",
                "169.254.170.2",
                "169.254.169.253",
                "100.100.100.200",
                "fd00:ec2::254",
                "fd00:ec2:0:0:0:0:0:254"
            };

    /** 可在 HTTPS/WSS 场景下放行文档保留地址的主机白名单。 */
    private static final String[] TRUSTED_PRIVATE_IP_HOSTS =
            new String[] {"multimedia.nt.qq.com.cn"};

    /** 普通点分十进制 IPv4 字面量，用于识别无协议主机文本。 */
    private static final Pattern DECIMAL_IPV4_PATTERN =
            Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

    /** 创建地址策略辅助类；该类只提供静态判定能力。 */
    private SecurityAddressPolicySupport() {}

    /**
     * 返回始终阻断主机名快照，用于策略摘要展示。
     *
     * @return 始终阻断主机名列表。
     */
    static List<String> alwaysBlockedHosts() {
        return Arrays.asList(ALWAYS_BLOCKED_HOSTS);
    }

    /**
     * 返回始终阻断 IP 文本快照，用于策略摘要展示。
     *
     * @return 始终阻断 IP 文本列表。
     */
    static List<String> alwaysBlockedIps() {
        return Arrays.asList(ALWAYS_BLOCKED_IPS);
    }

    /**
     * 返回可信私有地址主机快照，用于策略摘要展示。
     *
     * @return 可信私有地址主机列表。
     */
    static List<String> trustedPrivateIpHosts() {
        return Arrays.asList(TRUSTED_PRIVATE_IP_HOSTS);
    }

    /**
     * 判断主机名是否命中不可绕过的云元数据主机名。
     *
     * @param host 已规范化的主机名。
     * @return 命中始终阻断主机名时返回 true。
     */
    static boolean isAlwaysBlockedHost(String host) {
        for (String blocked : ALWAYS_BLOCKED_HOSTS) {
            if (blocked.equals(host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断主机名是否属于可接受文档保留地址的可信主机。
     *
     * @param host 已规范化的主机名。
     * @return 命中可信私有地址主机时返回 true。
     */
    static boolean isTrustedPrivateIpHost(String host) {
        return contains(TRUSTED_PRIVATE_IP_HOSTS, host);
    }

    /**
     * 判断主机文本是否是本机名称或可直接归类的地址字面量。
     *
     * @param host 主机名或地址文本。
     * @return 本机名称、IPv6 文本或 IPv4 字面量时返回 true。
     */
    static boolean isLocalOrAddressLiteral(String host) {
        String value = StrUtil.nullToEmpty(host).toLowerCase(Locale.ROOT);
        if ("localhost".equals(value)) {
            return true;
        }
        if (value.indexOf(':') >= 0) {
            return true;
        }
        return parseObfuscatedIpv4(value) != null || DECIMAL_IPV4_PATTERN.matcher(value).matches();
    }

    /**
     * 判断 IP 文本是否命中不可绕过的云元数据或链路本地地址。
     *
     * @param ip DNS 解析得到的 IP 文本。
     * @return 命中始终阻断 IP 时返回 true。
     */
    static boolean isAlwaysBlockedIp(String ip) {
        if (contains(ALWAYS_BLOCKED_IPS, ip)) {
            return true;
        }
        return ip != null && ip.startsWith("169.254.");
    }

    /**
     * 判断地址对象是否命中不可绕过的云元数据地址，包含 IPv4-mapped IPv6 形式。
     *
     * @param address DNS 或字面量解析得到的地址。
     * @return 命中始终阻断地址时返回 true。
     */
    static boolean isAlwaysBlockedAddress(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] rawAddress = address.getAddress();
        if (rawAddress == null) {
            return false;
        }
        if (rawAddress.length == 4) {
            return isAlwaysBlockedIpv4(
                    rawAddress[0] & 0xff,
                    rawAddress[1] & 0xff,
                    rawAddress[2] & 0xff,
                    rawAddress[3] & 0xff);
        }
        if (rawAddress.length == 16
                && isZeroSuffix(rawAddress, 0, 10)
                && rawAddress[10] == (byte) 0xff
                && rawAddress[11] == (byte) 0xff) {
            return isAlwaysBlockedIpv4(
                    rawAddress[12] & 0xff,
                    rawAddress[13] & 0xff,
                    rawAddress[14] & 0xff,
                    rawAddress[15] & 0xff);
        }
        if (rawAddress.length == 16 && isZeroSuffix(rawAddress, 0, 12)) {
            return isAlwaysBlockedIpv4(
                    rawAddress[12] & 0xff,
                    rawAddress[13] & 0xff,
                    rawAddress[14] & 0xff,
                    rawAddress[15] & 0xff);
        }
        return false;
    }

    /**
     * 判断 IPv4 四段是否属于不可绕过的云元数据地址。
     *
     * @param a 第一段。
     * @param b 第二段。
     * @param c 第三段。
     * @param d 第四段。
     * @return 命中始终阻断 IPv4 地址时返回 true。
     */
    static boolean isAlwaysBlockedIpv4(int a, int b, int c, int d) {
        if (a == 169 && b == 254) {
            return true;
        }
        return (a == 100 && b == 100 && c == 100 && d == 200);
    }

    /**
     * 判断地址是否属于内网、本机、链路本地、多播、保留或文档地址范围。
     *
     * @param address DNS 解析得到的地址。
     * @param ip 地址文本，用于覆盖混淆 IPv4 或带 zone id 的 IPv6 文本。
     * @return 应按内网地址处理时返回 true。
     */
    static boolean isPrivateOrInternal(InetAddress address, String ip) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || address.isLinkLocalAddress()) {
            return true;
        }
        if (ip == null) {
            return false;
        }
        byte[] rawAddress = address.getAddress();
        if (rawAddress.length == 16
                && isZeroSuffix(rawAddress, 0, 10)
                && rawAddress[10] == (byte) 0xff
                && rawAddress[11] == (byte) 0xff) {
            int a = rawAddress[12] & 0xff;
            int b = rawAddress[13] & 0xff;
            int c = rawAddress[14] & 0xff;
            int d = rawAddress[15] & 0xff;
            if (isBlockedIpv4(a, b, c, d)) {
                return true;
            }
        }
        if (rawAddress.length == 16 && isZeroSuffix(rawAddress, 0, 12)) {
            int a = rawAddress[12] & 0xff;
            int b = rawAddress[13] & 0xff;
            int c = rawAddress[14] & 0xff;
            int d = rawAddress[15] & 0xff;
            if (isBlockedIpv4(a, b, c, d)) {
                return true;
            }
        }
        if (isBlockedIpv4Text(ip)) {
            return true;
        }
        return isBlockedIpv6Address(rawAddress);
    }

    /**
     * 判断 IPv4 文本是否属于内网、保留或文档地址范围。
     *
     * @param ip 地址文本。
     * @return 应按内网地址处理时返回 true。
     */
    static boolean isBlockedIpv4Text(String ip) {
        String value = StrUtil.nullToEmpty(ip);
        int percent = value.indexOf('%');
        if (percent >= 0) {
            value = value.substring(0, percent);
        }
        int[] obfuscated = parseObfuscatedIpv4(value);
        if (obfuscated != null) {
            return isBlockedIpv4(obfuscated[0], obfuscated[1], obfuscated[2], obfuscated[3]);
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            try {
                if (parts[i].length() == 0) {
                    return false;
                }
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        return isBlockedIpv4(octets[0], octets[1], octets[2], octets[3]);
    }

    /**
     * 判断 IPv4 四段是否属于内网、保留或文档地址范围。
     *
     * @param a 第一段。
     * @param b 第二段。
     * @param c 第三段。
     * @param d 第四段。
     * @return 应按内网地址处理时返回 true。
     */
    static boolean isBlockedIpv4(int a, int b, int c, int d) {
        if (a == 0 || a == 10 || a == 127 || a >= 224) {
            return true;
        }
        if (a == 100 && b >= 64 && b <= 127) {
            return true;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return true;
        }
        if (a == 192 && b == 168) {
            return true;
        }
        if (a == 198 && (b == 18 || b == 19)) {
            return true;
        }
        return (a == 192 && b == 0 && (c == 0 || c == 2))
                || (a == 198 && b == 51 && c == 100)
                || (a == 203 && b == 0 && c == 113);
    }

    /**
     * 判断地址是否属于可信主机允许的文档保留 IPv4 范围。
     *
     * @param address DNS 解析得到的地址。
     * @return 位于可信文档保留地址范围时返回 true。
     */
    static boolean isTrustedPrivateAddress(InetAddress address) {
        byte[] rawAddress = address == null ? null : address.getAddress();
        if (rawAddress == null || rawAddress.length != 4) {
            return false;
        }
        int a = rawAddress[0] & 0xff;
        int b = rawAddress[1] & 0xff;
        return a == 198 && (b == 18 || b == 19);
    }

    /**
     * 判断地址是否属于代理 fake-ip 常用的 198.18.0.0/15 范围；DNS 解析命中时不应把公共域名误判为内网字面量。
     *
     * @param address DNS 解析得到的地址。
     * @return 位于代理 fake-ip 范围时返回 true。
     */
    static boolean isProxyFakeIpAddress(InetAddress address) {
        return isTrustedPrivateAddress(address);
    }

    /**
     * 判断 IPv4 四段是否同时覆盖内网和云元数据阻断范围。
     *
     * @param octets IPv4 四段数组。
     * @return 命中任意阻断范围时返回 true。
     */
    static boolean isBlockedOrAlwaysBlockedIpv4(int[] octets) {
        return octets != null
                && octets.length == 4
                && (isAlwaysBlockedIpv4(octets[0], octets[1], octets[2], octets[3])
                        || isBlockedIpv4(octets[0], octets[1], octets[2], octets[3]));
    }

    /**
     * 解析主机文本中的 IPv4 字面量，支持点分十进制和历史网络库常见的混淆形式。
     *
     * @param host 主机名或地址文本。
     * @return 成功解析时返回 IPv4 四段，否则返回 null。
     */
    static int[] parseIpv4HostLiteral(String host) {
        int[] obfuscated = parseObfuscatedIpv4(host);
        if (obfuscated != null) {
            return obfuscated;
        }
        String value = StrUtil.nullToEmpty(host).toLowerCase(Locale.ROOT).trim();
        if (value.indexOf(':') >= 0) {
            return null;
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (!Pattern.compile("^\\d{1,3}$").matcher(parts[i]).matches()) {
                return null;
            }
            try {
                octets[i] = Integer.parseInt(parts[i], 10);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return null;
            }
        }
        return octets;
    }

    /**
     * 解析十进制整数、十六进制、八进制等混淆 IPv4 文本。
     *
     * @param host 主机名或地址文本。
     * @return 成功解析时返回 IPv4 四段，否则返回 null。
     */
    static int[] parseObfuscatedIpv4(String host) {
        String value = StrUtil.nullToEmpty(host).toLowerCase(Locale.ROOT).trim();
        if (value.length() == 0 || value.indexOf(':') >= 0) {
            return null;
        }
        if (value.startsWith("0x") && value.indexOf('.') < 0) {
            return parseIpv4Number(value.substring(2), 16);
        }
        if (Pattern.compile("^\\d+$").matcher(value).matches()) {
            return parseIpv4Number(value, 10);
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        boolean nonDecimal = false;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int radix = 10;
            if (part.startsWith("0x") && part.length() > 2) {
                radix = 16;
                part = part.substring(2);
                nonDecimal = true;
            } else if (part.length() > 1 && part.charAt(0) == '0') {
                radix = 8;
                nonDecimal = true;
            }
            if (!isNumberInRadix(part, radix)) {
                return null;
            }
            try {
                octets[i] = Integer.parseInt(part, radix);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return null;
            }
        }
        return nonDecimal ? octets : null;
    }

    /**
     * 将 IPv4 四段格式化为标准点分十进制文本。
     *
     * @param octets IPv4 四段数组。
     * @return 点分十进制 IPv4 文本。
     */
    static String formatIpv4(int[] octets) {
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }

    /**
     * 解析 32 位整数字面量形式的 IPv4 地址。
     *
     * @param raw 原始数字文本。
     * @param radix 数字进制。
     * @return 成功解析时返回 IPv4 四段，否则返回 null。
     */
    private static int[] parseIpv4Number(String raw, int radix) {
        if (!isNumberInRadix(raw, radix)) {
            return null;
        }
        try {
            BigInteger value = new BigInteger(raw, radix);
            if (value.signum() < 0 || value.bitLength() > 32) {
                return null;
            }
            long packed = value.longValue();
            return new int[] {
                (int) ((packed >> 24) & 0xff),
                (int) ((packed >> 16) & 0xff),
                (int) ((packed >> 8) & 0xff),
                (int) (packed & 0xff)
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 判断字符串是否完全符合指定进制的数字字符集合。
     *
     * @param value 待解析的数字文本。
     * @param radix 数字进制。
     * @return 所有字符都属于该进制时返回 true。
     */
    private static boolean isNumberInRadix(String value, int radix) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), radix) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断 IPv6 地址字节是否属于内网、保留、文档、多播或 NAT64 特殊范围。
     *
     * @param rawAddress IPv6 地址字节。
     * @return 应按内网地址处理时返回 true。
     */
    private static boolean isBlockedIpv6Address(byte[] rawAddress) {
        if (rawAddress == null || rawAddress.length != 16) {
            return false;
        }
        int first = unsignedShort(rawAddress, 0);
        int second = unsignedShort(rawAddress, 2);
        if (first == 0 || first == 1 || first == 0x2002 || first >= 0xff00) {
            return true;
        }
        if (first >= 0xfc00 && first <= 0xfdff) {
            return true;
        }
        if (first == 0x2001 && second < 0x0200) {
            return true;
        }
        if (first == 0x2001 && second == 0x0db8) {
            return true;
        }
        return first == 0x0064 && second == 0xff9b && isZeroSuffix(rawAddress, 4, 8);
    }

    /**
     * 读取无符号 16 位网络序数值。
     *
     * @param bytes 地址字节。
     * @param offset 起始偏移。
     * @return 无符号 16 位数值。
     */
    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    /**
     * 判断地址字节片段是否全为零，用于识别 IPv4-mapped IPv6 和 NAT64 前缀。
     *
     * @param bytes 地址字节。
     * @param offset 起始偏移。
     * @param length 检查长度。
     * @return 指定片段全为零时返回 true。
     */
    private static boolean isZeroSuffix(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 忽略大小写判断数组中是否包含指定值。
     *
     * @param values 候选值数组。
     * @param value 待检查值。
     * @return 包含指定值时返回 true。
     */
    private static boolean contains(String[] values, String value) {
        for (String item : values) {
            if (item.equalsIgnoreCase(StrUtil.nullToEmpty(value))) {
                return true;
            }
        }
        return false;
    }
}
