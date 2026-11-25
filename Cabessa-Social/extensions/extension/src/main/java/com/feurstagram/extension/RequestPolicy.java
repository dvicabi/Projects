package com.feurstagram.extension;

import java.net.URI;
import java.util.Locale;

/** Pure request classification. A matching path on an unrelated host is never blocked. */
final class RequestPolicy {
    private RequestPolicy() {}

    static boolean isInstagram(URI uri) {
        return uri != null && domain(uri.getHost(), "instagram.com");
    }

    private static boolean domain(String host, String domain) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals(domain) || normalized.endsWith("." + domain);
    }

    /** Strip only the native REST version prefix, never arbitrary path segments. */
    static String path(URI uri) {
        if (uri == null || uri.getPath() == null) return "";
        String path = uri.getPath();
        return path.startsWith("/api/v1/") ? path.substring(7) : path;
    }

    static boolean family(String path, String prefix) {
        if (prefix.startsWith("/api/v1/")) prefix = prefix.substring(7);
        if (prefix.endsWith("/")) {
            return path.equals(prefix.substring(0, prefix.length() - 1)) || path.startsWith(prefix);
        }
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    static boolean isTimeline(URI uri) {
        return isInstagram(uri) && family(path(uri), "/feed/timeline/");
    }

    /** Known Reel delivery paths, including a Reel opened on its own from a DM. */
    static boolean isReelDelivery(URI uri) {
        if (!isInstagram(uri)) return false;
        String path = path(uri);
        return family(path, "/clips/home/") || family(path, "/clips/discover/")
                || family(path, "/clips/get_blend_medias/") || family(path, "/clips/items/")
                || family(path, "/clips/user/")
                || family(path, "/reel/") || family(path, "/reels/");
    }

    static boolean isTelemetry(URI uri) {
        if (uri == null || !(isInstagram(uri) || domain(uri.getHost(), "facebook.com")
                || domain(uri.getHost(), "facebook.net"))) return false;
        String path = path(uri);
        return family(path, "/pigeon_nest") || family(path, "/logging_client_events")
                || family(path, "/launcher/mobileconfiglogging/") || family(path, "/news/log/")
                || path.equals("/fbevents.js") || path.endsWith("/fbevents.js");
    }

}
