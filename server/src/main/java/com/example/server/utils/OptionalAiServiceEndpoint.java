package com.example.server.utils;

import java.net.URI;
import java.util.Set;

/** Optional analysis integrations require their own complete configuration. */
final class OptionalAiServiceEndpoint {
    private OptionalAiServiceEndpoint() { }

    static String require(boolean enabled, String apiKey, String endpoint, String model, String prefix) {
        if (!enabled || blank(apiKey) || blank(endpoint) || blank(model)) {
            throw new IllegalArgumentException(prefix + " 服务未启用或配置不完整：请单独配置 enabled、api-key、服务地址和 model；不会复用 DeepSeek 密钥");
        }
        URI uri;
        try { uri = URI.create(endpoint); } catch (RuntimeException invalid) { throw invalid(prefix); }
        // Local optional services may use HTTP; remote credentials require HTTPS.
        boolean localHttp = "http".equals(uri.getScheme()) && uri.getHost() != null
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if (uri.getHost() == null || (!"https".equals(uri.getScheme()) && !localHttp)
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() == 0) throw invalid(prefix);
        return endpoint.replaceAll("/+$", "");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static IllegalArgumentException invalid(String prefix) {
        return new IllegalArgumentException(prefix + " 服务地址需为 HTTPS 或本机 HTTP，且不能包含凭据、查询参数或 fragment");
    }
}
