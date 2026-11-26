package com.feurstagram.extension;

/** TEST APK ONLY. Local fixtures avoid Instagram credentials and external media. */
public final class SingleReelSource {
    private SingleReelSource() {}
    public static String authorizedCdnUrl(String source) {
        return source != null && (source.startsWith("file://")
                || source.startsWith("http://127.0.0.1:")) ? source : null;
    }
}
