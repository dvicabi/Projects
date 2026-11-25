package com.feurstagram.extension;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An in-memory capability for one explicit DM selection. This never enables a
 * Reel feed: both a fresh per-request module and exactly one media ID must match.
 * There are no preferences, credentials, persisted IDs or timed global unlocks.
 */
final class SharedReelRequestGate {
    private static final long TTL_NANOS = 30_000_000_000L;
    private static final int MAX_PENDING = 4;
    private static final Pattern ID = Pattern.compile("[0-9]{1,30}(?:_[0-9]{1,30})?");
    private static final Pattern ONE_ID = Pattern.compile("\\[\\s*\"([0-9]{1,30}(?:_[0-9]{1,30})?)\"\\s*\\]");
    private static final LinkedHashMap<String, Grant> PENDING = new LinkedHashMap<>();

    private SharedReelRequestGate() {}

    private static final class Grant {
        final String id;
        final long started;
        Grant(String id, long started) { this.id = id; this.started = started; }
    }

    static String authorize(String mediaId) {
        return authorize(mediaId, System.nanoTime());
    }

    static synchronized String authorize(String mediaId, long now) {
        if (mediaId == null || !ID.matcher(mediaId).matches()) return null;
        prune(now);
        while (PENDING.size() >= MAX_PENDING) {
            Iterator<String> first = PENDING.keySet().iterator();
            first.next();
            first.remove();
        }
        String module = "cabessa_single_" + UUID.randomUUID().toString().replace("-", "");
        PENDING.put(module, new Grant(mediaId, now));
        return module;
    }

    static synchronized void revoke(String module) {
        if (module != null) PENDING.remove(module);
    }

    static boolean allows(URI uri) {
        return allows(uri, System.nanoTime());
    }

    static synchronized boolean allows(URI uri, long now) {
        prune(now);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || !RequestPolicy.isInstagram(uri) || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !"/clips/items/".equals(RequestPolicy.path(uri))) return false;
        Map<String, String> query = query(uri.getRawQuery());
        if (query == null) return false;
        Grant grant = PENDING.get(query.get("container_module"));
        String ids = query.get("clips_media_ids");
        if (grant == null || ids == null) return false;
        Matcher selected = ONE_ID.matcher(ids);
        // Require the exact requested representation here. The response-model
        // comparison below permits Instagram to append its owner suffix.
        if (!selected.matches() || !grant.id.equals(selected.group(1))) return false;
        for (String key : query.keySet()) {
            if (key.equals("max_id") || key.equals("min_id") || key.equals("cursor")
                    || key.equals("next_max_id")) return false;
        }
        return true;
    }

    static boolean sameMediaId(String expected, String actual) {
        if (expected == null || actual == null || !ID.matcher(expected).matches()
                || !ID.matcher(actual).matches()) return false;
        if (expected.indexOf('_') >= 0 && actual.indexOf('_') >= 0) return expected.equals(actual);
        return primaryId(expected).equals(primaryId(actual));
    }

    private static String primaryId(String value) {
        int separator = value.indexOf('_');
        return separator < 0 ? value : value.substring(0, separator);
    }

    private static void prune(long now) {
        Iterator<Grant> grants = PENDING.values().iterator();
        while (grants.hasNext()) {
            long elapsed = now - grants.next().started;
            if (elapsed < 0 || elapsed >= TTL_NANOS) grants.remove();
        }
    }

    private static Map<String, String> query(String raw) {
        if (raw == null || raw.length() > 16_384) return null;
        HashMap<String, String> result = new HashMap<>();
        try {
            for (String part : raw.split("&", -1)) {
                int equal = part.indexOf('=');
                if (equal <= 0) return null;
                String key = URLDecoder.decode(part.substring(0, equal), StandardCharsets.UTF_8.name());
                String value = URLDecoder.decode(part.substring(equal + 1), StandardCharsets.UTF_8.name());
                // Duplicate/encoded-duplicate parameters are ambiguous; fail closed.
                if (result.put(key, value) != null) return null;
            }
            return result;
        } catch (Exception malformed) {
            return null;
        }
    }
}
