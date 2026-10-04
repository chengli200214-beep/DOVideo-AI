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

    /**
     * 仅允许带主机名的 http/https 链接；会同步解析 DNS（阻塞）。
     * 链接非法、无法解析或指向非公网地址时一律抛出 {@link IllegalArgumentException}。
     */
    public static void requirePublicHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("仅支持合法的公网 HTTP/HTTPS 视频链接");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("仅支持合法的公网 HTTP/HTTPS 视频链接", e);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("仅支持合法的公网 HTTP/HTTPS 视频链接");
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无法解析视频链接的主机地址", e);
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
        if (address.isAnyLocalAddress()          // 0.0.0.0、::
                || address.isLoopbackAddress()   // 127.0.0.0/8、::1
                || address.isLinkLocalAddress()  // 169.254.0.0/16、fe80::/10
                || address.isSiteLocalAddress()  // 10/8、172.16/12、192.168/16
                || address.isMulticastAddress()) { // 224.0.0.0/4、ff00::/8
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
        if (bytes.length != 16) {
            return false;
        }
        if ((bytes[0] & 0xFE) == 0xFC) {                                     // fc00::/7 IPv6 ULA
            return true;
        }
        // 内嵌 IPv4 的 IPv6：IPv4 兼容地址 ::a.b.c.d，以及 NAT64 64:ff9b::/96，按内嵌的 IPv4 判定
        if (isZero(bytes, 0, 12) || (bytes[0] == 0x00 && bytes[1] == 0x64 && (bytes[2] & 0xFF) == 0xFF
                && (bytes[3] & 0xFF) == 0x9B && isZero(bytes, 4, 12))) {
            try {
                return isDisallowed(InetAddress.getByAddress(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]}));
            } catch (UnknownHostException e) {
                return true;
            }
        }
        return false;
    }

    private static boolean isZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) return false;
        }
        return true;
    }
}
