package com.example.server.utils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 服务端主动外连（yt-dlp 拉取、生成产物下载）前的公网地址判定，全项目只此一份实现。
 *
 * <p>这只是应用层的尽力校验：下游进程或客户端会重新解析 DNS 并可能跟随跳转，
 * 存在 DNS rebinding / TOCTOU 风险。生产环境必须叠加网络层出口管控才能彻底杜绝 SSRF。
 */
public final class PublicNetworkAddresses {

    private PublicNetworkAddresses() {
    }

    public static void requirePublicHttpUrl(String value) throws UnknownHostException {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("仅支持合法的公网 HTTP/HTTPS 视频链接");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("仅支持合法的公网 HTTP/HTTPS 视频链接");
        }
        InetAddress[] resolved = InetAddress.getAllByName(host);
        if (resolved.length == 0) {
            throw new IllegalArgumentException("无法解析视频链接的主机地址");
        }
        for (InetAddress address : resolved) {
            if (isDisallowed(address)) {
                throw new IllegalArgumentException("不允许访问本机、内网或保留网段地址");
            }
        }
    }

    /**
     * 拦截回环、任意本地、链路本地（含云元数据端点 169.254.169.254）、RFC1918 内网、
     * IPv6 ULA、运营商级 NAT、基准测试段、组播与保留网段。
     */
    public static boolean isDisallowed(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0) return true;                                     // 0.0.0.0/8
            if (first == 100 && second >= 64 && second <= 127) return true;  // 100.64.0.0/10 运营商级 NAT
            if (first == 169 && second == 254) return true;                  // 169.254.0.0/16 兜底
            if (first == 198 && (second == 18 || second == 19)) return true; // 198.18.0.0/15 基准测试
            return first >= 240;                                             // 240.0.0.0/4 保留段
        }
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;              // fc00::/7 IPv6 ULA
    }
}
