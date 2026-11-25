package com.feurstagram.extension;

import java.util.HashMap;
import java.util.Map;

/**
 * Rewrites the main-feed request's pagination header so the home feed returns
 * only posts from accounts you follow (the chronological "Following" feed)
 * instead of the recommended/ranked feed. This is a fixed policy, including
 * refresh requests; no preference or pagination header can select the ranked feed.
 *
 * Invoked from the feed-request constructor with the request's parameter map,
 * and again while the native timeline builder serialises its parameters. The
 * second guard covers additional maps merged after the original request map.
 */
public final class LimitFeed {

    private LimitFeed() {}

    private static final String PAGINATION_KEY = "pagination_source";

    public static Map<String, String> setFollowingHeader(Map<String, String> headers) {
        // The original map may be immutable, so copy before mutating.
        Map<String, String> patched = headers == null ? new HashMap<String, String>() : new HashMap<>(headers);
        patched.put(PAGINATION_KEY, "following");
        return patched;
    }

    /** Called only by the native timeline parameter builder, never other APIs. */
    public static String followingParameter(String name, String value) {
        return PAGINATION_KEY.equals(name) ? "following" : value;
    }
}
