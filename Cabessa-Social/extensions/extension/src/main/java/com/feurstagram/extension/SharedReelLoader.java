package com.feurstagram.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Resolves exactly the Reel selected in a direct-message or Story reshare attachment through
 * Instagram's existing authenticated request path. The request capability is
 * revoked on every completion path; it never unlocks native Reel navigation.
 */
public final class SharedReelLoader implements Application.ActivityLifecycleCallbacks {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<SharedReelLoader> active = new WeakReference<>(null);
    private static final long LOAD_TIMEOUT_MS = 20000L;

    private final WeakReference<Activity> activity;
    private final String mediaId;
    private final String module;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private Object request;
    private boolean finished;
    private boolean registered;

    private SharedReelLoader(Activity activity, String mediaId, String module) {
        this.activity = new WeakReference<>(activity);
        this.mediaId = mediaId;
        this.module = module;
    }

    /** Version-resolved native bridges; the patch supplies their exact bodies. */
    public static Object nativeFetch(Object session, String jsonIds, String module, NativeReelCallback callback) { return null; }
    public static void nativeCancel(Object request) {}
    public static List<?> nativeResponseMedia(Object response) { return null; }
    public static String nativeMediaId(Object media) { return null; }
    public static String nativeIdFromCode(String shortcode) { return null; }

    /** A Reel attachment click is always consumed, including lookup failures. */
    public static void open(Context context, Object session, String mediaId, String shortcode) {
        String resolved = mediaId;
        if (resolved == null && shortcode != null) {
            try { resolved = nativeIdFromCode(shortcode); } catch (Throwable ignored) {}
        }
        final String selected = resolved;
        if (Looper.myLooper() == Looper.getMainLooper()) start(context, session, selected);
        else MAIN.post(() -> start(context, session, selected));
    }

    /** Only the explicit native CLIPS sticker click calls this. Match the original
     * null-only fallback; never interpret a story ID or malformed value as a shortcode. */
    public static void openFromStory(Context context, Object session, String primaryId, String fallbackId) {
        open(context, session, primaryId != null ? primaryId : fallbackId, null);
    }

    private static void start(Context context, Object session, String mediaId) {
        SharedReelLoader previous = active.get();
        if (previous != null) previous.finish(false, null);
        Activity owner = Activities.forContext(context);
        if (!Activities.hasWindow(owner) || session == null) {
            SingleReelPlayer.unavailable(context);
            return;
        }
        String module = SharedReelRequestGate.authorize(mediaId);
        if (module == null) {
            SingleReelPlayer.unavailable(owner);
            return;
        }
        SharedReelLoader loader = new SharedReelLoader(owner, mediaId, module);
        active = new WeakReference<>(loader);
        try {
            loader.showLoading(owner);
            owner.getApplication().registerActivityLifecycleCallbacks(loader);
            loader.registered = true;
            loader.handler.postDelayed(() -> loader.finish(true, null), LOAD_TIMEOUT_MS);
            // authorize() only accepts decimal IDs with an optional decimal
            // owner suffix, so this JSON cannot contain an escape or quote.
            Object request = nativeFetch(session, "[\"" + mediaId + "\"]", module, new NativeReelCallback(loader));
            if (loader.finished) {
                // A callback may have completed synchronously during scheduling.
                if (request != null) cancelSafely(request);
            } else if (request == null) {
                loader.finish(true, null);
            } else {
                loader.request = request;
            }
        } catch (Throwable ignored) {
            loader.finish(true, null);
        }
    }

    void success(Object response) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> success(response));
            return;
        }
        if (finished) return;
        Object selected = null;
        try {
            List<?> media = nativeResponseMedia(response);
            if (media != null && media.size() <= 1000) {
                for (Object item : media) {
                    if (SharedReelRequestGate.sameMediaId(mediaId, nativeMediaId(item))) {
                        if (selected != null) { finish(true, null); return; }
                        selected = item;
                    }
                }
            }
        } catch (Throwable ignored) { selected = null; }
        finish(true, selected);
    }

    void failure(Object ignored) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> failure(null));
            return;
        }
        finish(true, null);
    }

    private void showLoading(Activity owner) {
        LinearLayout content = new LinearLayout(owner);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        int padding = Math.round(24 * owner.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        content.addView(new ProgressBar(owner), new LinearLayout.LayoutParams(padding * 2, padding * 2));
        TextView caption = new TextView(owner);
        caption.setText(text(owner, "טוען את הסרטון שנבחר…", "Loading the selected video…"));
        caption.setPadding(0, padding, 0, 0);
        caption.setGravity(Gravity.CENTER);
        content.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog = new AlertDialog.Builder(owner)
                .setTitle(text(owner, "סרטון משותף", "Shared video"))
                .setView(content)
                .setNegativeButton(text(owner, "ביטול", "Cancel"), (ignored, which) -> finish(false, null))
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(ignored -> finish(false, null));
        dialog.setOnDismissListener(ignored -> { if (!finished) finish(false, null); });
        dialog.show();
        if (Config.isGrayscale()) Grayscale.apply(content);
    }

    /** Must run on the UI thread. Later callbacks are ignored after this point. */
    private void finish(boolean presentResult, Object media) {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        SharedReelRequestGate.revoke(module);
        Object pending = request;
        request = null;
        if (pending != null) cancelSafely(pending);
        Activity owner = activity.get();
        if (registered && owner != null) owner.getApplication().unregisterActivityLifecycleCallbacks(this);
        registered = false;
        AlertDialog loading = dialog;
        dialog = null;
        if (loading != null) loading.dismiss();
        if (active.get() == this) active.clear();
        if (!presentResult || !Activities.hasWindow(owner)) return;
        if (media == null || !SingleReelPlayer.open(owner, media)) SingleReelPlayer.unavailable(owner);
    }

    private static void cancelSafely(Object request) {
        try { nativeCancel(request); } catch (Throwable ignored) {}
    }

    private static String text(Context context, String he, String en) {
        String language = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
        return "he".equals(language) || "iw".equals(language) ? he : en;
    }

    @Override public void onActivityPaused(Activity a) { if (a == activity.get()) finish(false, null); }
    @Override public void onActivityStopped(Activity a) { if (a == activity.get()) finish(false, null); }
    @Override public void onActivityDestroyed(Activity a) { if (a == activity.get()) finish(false, null); }
    @Override public void onActivityCreated(Activity a, Bundle state) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityResumed(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
}
