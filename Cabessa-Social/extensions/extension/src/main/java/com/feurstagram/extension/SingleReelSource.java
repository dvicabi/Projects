package com.feurstagram.extension;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Validates the one existing media URL handed to the isolated player. */
final class SingleReelSource {
    private SingleReelSource() {}

    /**
     * Preserve the original signed query. Never construct a media endpoint,
     * attach account cookies, or accept file/content/custom-scheme URLs.
     */
    static String authorizedCdnUrl(String candidate) {
        if (candidate == null || candidate.length() > 16384) return null;
        try {
            URI uri = new URI(candidate);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            host = host.toLowerCase(Locale.ROOT);
            boolean mediaHost = host.equals("cdninstagram.com") || host.endsWith(".cdninstagram.com")
                    || host.equals("fbcdn.net") || host.endsWith(".fbcdn.net");
            if (!mediaHost || uri.getRawPath() == null || uri.getRawPath().isEmpty()) return null;
            return uri.toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return null;
        }
    }
}
