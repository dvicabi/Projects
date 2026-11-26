package com.feurstagram.extension;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Replaces the two empty, patch-time native request stubs only in the test APK. */
final class SmokeNativeBridge {
    static volatile Pending last;
    static final class Pending {
        NativeReelCallback callback;
        URI uri;
        boolean originStoppedBeforeFetch;
        int cancellations;
    }
    static Object fetch(Object session, String jsonIds, String module, NativeReelCallback callback) {
        Pending pending = new Pending();
        pending.callback = callback;
        pending.uri = URI.create("https://i.instagram.com/api/v1/clips/items/?clips_media_ids="
                + URLEncoder.encode(jsonIds, StandardCharsets.UTF_8) + "&container_module=" + module);
        pending.originStoppedBeforeFetch = SmokeHostActivity.stopped
                && SmokeHostActivity.instance != null
                && !SmokeHostActivity.instance.sourcePlayer.isPlaying();
        last = pending;
        if (!SharedReelRequestGate.allows(pending.uri)) throw new AssertionError("Selected request lacks its scoped capability");
        return pending;
    }
    static void cancel(Object request) { ((Pending) request).cancellations++; }
}
