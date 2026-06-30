package com.jimuqu.solon.claw.support;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.net.InetAddress;

/** 汇总安全策略相关测试桩，避免各测试类重复维护本地回环和固定 DNS 行为。 */
public final class SecurityPolicyTestSupport {
    /** 工具类不允许实例化。 */
    private SecurityPolicyTestSupport() {}

    /** 用固定 IP 替代真实 DNS 解析，便于测试 URL 安全边界对解析结果的处理。 */
    public static class FixedDnsSecurityPolicyService extends SecurityPolicyService {
        /** 测试期指定的解析结果 IP。 */
        private final String ip;

        /**
         * 创建固定 DNS 解析策略。
         *
         * @param appConfig 应用配置。
         * @param ip 测试期所有主机名都解析到的 IP。
         */
        public FixedDnsSecurityPolicyService(AppConfig appConfig, String ip) {
            super(appConfig);
            this.ip = ip;
        }

        /**
         * 将任意主机解析为测试指定 IP。
         *
         * @param host 被解析的主机名。
         * @return 固定 IP 对应的地址列表。
         */
        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            return new InetAddress[] {InetAddress.getByName(ip)};
        }
    }

    /** 允许本地测试服务器 URL，仍保留父类对云元数据等危险目标的阻断。 */
    public static class AllowLocalButBlockMetadataSecurityPolicyService
            extends SecurityPolicyService {
        /**
         * 创建允许本地回环测试服务器的安全策略。
         *
         * @param appConfig 应用配置。
         */
        public AllowLocalButBlockMetadataSecurityPolicyService(AppConfig appConfig) {
            super(appConfig);
        }

        /**
         * 允许测试用本地回环 URL，其余 URL 仍交给父类策略判定。
         *
         * @param url 待检查 URL。
         * @return URL 安全判定。
         */
        @Override
        public UrlVerdict checkUrl(String url) {
            if (isLoopbackTestUrl(url)) {
                return UrlVerdict.allow();
            }
            return super.checkUrl(url);
        }

        /**
         * 允许模型列表等硬边界检查访问测试用本地回环 URL。
         *
         * @param url 待检查 URL。
         * @param allowPrivateOverride 私网访问覆盖策略。
         * @return URL 安全判定。
         */
        @Override
        public UrlVerdict checkUrlSafety(String url, Boolean allowPrivateOverride) {
            if (isLoopbackTestUrl(url)) {
                return UrlVerdict.allow();
            }
            return super.checkUrlSafety(url, allowPrivateOverride);
        }

        /**
         * 允许附件和技能中心测试访问本地回环 URL。
         *
         * @param url 待检查 URL。
         * @return URL 安全判定。
         */
        @Override
        public UrlVerdict checkUrlBlockingPrivate(String url) {
            if (isLoopbackTestUrl(url)) {
                return UrlVerdict.allow();
            }
            return super.checkUrlBlockingPrivate(url);
        }

        /**
         * 避免 127.0.0.1 测试服务器被真实私网解析规则阻断。
         *
         * @param host 被解析的主机名。
         * @return 本地回环测试主机的安全替代解析结果。
         */
        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            if ("127.0.0.1".equals(host)) {
                return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
            }
            return new InetAddress[] {InetAddress.getByName(host)};
        }

        /**
         * 判断 URL 是否指向本测试套件启动的本地回环服务。
         *
         * @param url 待检查 URL。
         * @return 是本地回环测试 URL 时返回 true。
         */
        private static boolean isLoopbackTestUrl(String url) {
            return url != null && (url.contains("127.0.0.1") || url.contains("localhost"));
        }
    }
}
