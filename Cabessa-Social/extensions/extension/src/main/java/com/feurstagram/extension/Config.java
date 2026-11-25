package com.feurstagram.extension;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Fixed product policy. Content and navigation rules never come from preferences.
 * Display preferences and local feed-author exclusions are stored separately from upstream
 * preferences so an old backup cannot relax a content block.
 */
public final class Config {
    public static final String RELEASE_VERSION = "0.1.5";
    private static final String DISPLAY_PREFS = "instagram_social_display";
    private Config() {}

    /** Resolve the process Application context, without storing account data. */
    public static Context getAppContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            return (Context) activityThread.getMethod("currentApplication").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static SharedPreferences displayPrefs(Context supplied) {
        Context context = supplied != null ? supplied.getApplicationContext() : getAppContext();
        if (context == null) context = supplied;
        return context == null ? null : context.getSharedPreferences(DISPLAY_PREFS, Context.MODE_PRIVATE);
    }

    /** Fixed-value compatibility entry point for the view hiders. */
    public static boolean getBlocked(String key, boolean defaultValue) {
        if (key == null) return defaultValue;
        switch (key) {
            case "block_feed":
            case "block_stories":
            case "block_comments":
            case "block_notifications":
            case "nav_show_reels":
                return false;
            case "block_explore":
            case "block_reels":
            case "block_friends_lane":
            case "block_instants":
            case "block_notes":
            case "block_suggested":
            case "block_ads":
            case "block_threads":
            case "block_telemetry":
            case "limit_following_feed":
            case "quoicouview_mode":
            case "nav_show_home":
            case "nav_show_search":
            case "nav_show_create":
            case "nav_show_direct":
            case "nav_show_profile":
                return true;
            case "grayscale":
                return isGrayscale();
            default:
                return defaultValue;
        }
    }

    public static boolean isFeedBlocked() { return false; }
    public static boolean isFollowingFeedOnly() { return true; }
    public static boolean isExploreBlocked() { return true; }
    public static boolean isReelsBlocked() { return true; }
    public static boolean isFriendsLaneBlocked() { return true; }
    public static boolean isStoriesBlocked() { return false; }
    public static boolean isInstantsBlocked() { return true; }
    public static boolean isNotesBlocked() { return true; }
    public static boolean isSuggestedBlocked() { return true; }
    public static boolean isAdsBlocked() { return true; }
    public static boolean isCommentsBlocked() { return false; }
    public static boolean isTelemetryBlocked() { return true; }
    public static boolean isNotificationsButtonBlocked() { return false; }
    public static boolean isThreadsButtonBlocked() { return true; }
    public static boolean isForceSdr() { return true; }

    /** Keep genuine upload, login and network feedback visible. */
    public static boolean arePopupsHidden() { return false; }

    /** The native Home tab is retained as the following-only destination. */
    public static boolean isHomeTabShown() { return true; }
    public static boolean isReelsTabShown() { return false; }
    public static boolean isQuoicouviewEnabled() { return true; }
    public static String getLandingPage() { return "direct"; }

    public static boolean isNavTabShown(String key) {
        if (key == null) return false;
        switch (key) {
            case "nav_show_home":
            case "nav_show_search":
            case "nav_show_create":
            case "nav_show_direct":
            case "nav_show_profile":
                return true;
            default:
                return false;
        }
    }

    public static String navKeyForTab(String tab) {
        if (tab == null) return null;
        switch (tab) {
            case "feed_tab": return "nav_show_home";
            case "search_tab": return "nav_show_search";
            case "clips_tab": return "nav_show_reels";
            case "creation_tab": return "nav_show_create";
            case "direct_tab": return "nav_show_direct";
            case "profile_tab": return "nav_show_profile";
            default: return null;
        }
    }

    public static boolean isGrayscale() {
        try {
            SharedPreferences prefs = displayPrefs(null);
            return prefs != null && prefs.getBoolean("grayscale", false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Persist before handing off to the restart process. No policy key is writable. */
    static boolean setGrayscale(Context context, boolean enabled) {
        try {
            SharedPreferences prefs = displayPrefs(context);
            return prefs != null && prefs.edit().putBoolean("grayscale", enabled).commit();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
