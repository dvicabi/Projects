package com.feurstagram.extension;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Filters successful delivery JSON while preserving native error and authentication bodies. */
public final class ResponseFilter {
    private ResponseFilter() {}

    // One state per request dispatcher; weak keys also release abandoned requests.
    private static final Map<Object, JsonContentFilter> RESPONSES =
            Collections.synchronizedMap(new WeakHashMap<Object, JsonContentFilter>());

    private static final Map<Object, FollowingMuteFilter> FOLLOWING =
            Collections.synchronizedMap(new WeakHashMap<Object, FollowingMuteFilter>());

    // Replaced with direct, version-resolved field reads by the patch.
    static URI requestUri(Object request) { return null; }
    static int responseStatus(Object response) { return 0; }

    public static void onResponse(Object dispatcher, Object response, Object request) {
        FOLLOWING.remove(dispatcher);
        URI uri = requestUri(request);
        int status = responseStatus(response);
        if (status < 200 || status >= 300) {
            RESPONSES.remove(dispatcher);
            return;
        }
        if (Block.hidesResponse(uri)) {
            String path = RequestPolicy.path(uri);
            RESPONSES.put(dispatcher, new JsonContentFilter(RequestPolicy.family(path, "/ads/graphql/")));
        } else {
            RESPONSES.remove(dispatcher);
            // Only the native timeline response; profiles, Stories, DMs, login
            // and all other surfaces keep their original bodies.
            if (RequestPolicy.isTimeline(uri)) FOLLOWING.put(dispatcher, new FollowingMuteFilter());
        }
    }

    public static ByteBuffer onBody(Object dispatcher, Object request, ByteBuffer body) {
        JsonContentFilter filter = RESPONSES.get(dispatcher);
        if (body == null) return null;
        if (filter != null) return filter.filter(body);
        FollowingMuteFilter following = FOLLOWING.get(dispatcher);
        return following == null ? body : following.filter(body);
    }

    public static void onComplete(Object dispatcher) { RESPONSES.remove(dispatcher); FOLLOWING.remove(dispatcher); }
    public static void onError(Object dispatcher) { onComplete(dispatcher); }
}
