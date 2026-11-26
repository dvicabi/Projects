package com.feurstagram.extension;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/** Entry point for exactly one selected Reel, never the native recommendation pager. */
public final class SingleReelPlayer {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<SingleReelActivity> active = new WeakReference<>(null);
    private static SingleReelHandoff launchPending;
    private static Runnable launchTimeout;

    private SingleReelPlayer() {}

    /** Replaced by the version-resolved patch with the selected media's MP4 URL. */
    public static String mediaVideoUrl(Object media) { return null; }

    /** A known Reel is consumed even when unavailable; failure never unlocks native Reels. */
    public static boolean open(Context context, Object media) {
        if (!Block.isReelMedia(media)) return false;
        String candidate;
        try { candidate = SingleReelSource.authorizedCdnUrl(mediaVideoUrl(media)); }
        catch (Throwable ignored) { candidate = null; }
        final String source = candidate;
        if (Looper.myLooper() == Looper.getMainLooper()) present(context, source);
        else MAIN.post(() -> present(context, source));
        return true;
    }

    public static void unavailable(Context context) {
        if (Looper.myLooper() == Looper.getMainLooper()) present(context, null);
        else MAIN.post(() -> present(context, null));
    }

    private static void present(Context context, String source) {
        Activity activity = Activities.forContext(context);
        if (activity instanceof SingleReelActivity) ((SingleReelActivity) activity).presentSource(source);
        else launch(context, source);
    }

    /** Launch the opaque loading screen before any asynchronous media lookup. */
    static void resolve(Context context, Object session, String mediaId) {
        launch(context, null, new SingleReelHandoff.Selection(session, mediaId));
    }

    /**
     * A full opaque Activity is essential here. A fullscreen Dialog leaves the
     * underlying Story Activity RESUMED, so its video and timeline keep running.
     * Register the stop barrier before Android starts the new Activity.
     */
    static void launch(Context context, String source) {
        launch(context, source, null);
    }

    private static void launch(Context context, String source, SingleReelHandoff.Selection selection) {
        Activity origin = Activities.forContext(context);
        if (!Activities.hasWindow(origin) || launchPending != null) return;
        SingleReelHandoff handoff = SingleReelHandoff.begin(origin);
        if (selection != null) handoff.setSelection(selection.session, selection.mediaId);
        Intent intent = new Intent(origin, SingleReelActivity.class);
        intent.putExtra(SingleReelActivity.EXTRA_SOURCE, source);
        intent.putExtra(SingleReelActivity.EXTRA_HANDOFF, handoff.id);
        intent.putExtra(SingleReelActivity.EXTRA_LOOKUP, selection != null);
        launchPending = handoff;
        try {
            origin.startActivity(intent);
            // If Android never creates the target, release the session as well
            // as the launch flag, even if the source has already stopped.
            launchTimeout = () -> {
                if (launchPending != handoff) return;
                launchPending = null;
                handoff.close();
            };
            MAIN.postDelayed(launchTimeout, 5000L);
        } catch (RuntimeException ignored) {
            launchPending = null;
            handoff.close();
            Toast.makeText(origin, text(origin, "לא ניתן לפתוח את הסרטון כרגע. נסה שוב.",
                    "Can't open this video right now. Try again."), Toast.LENGTH_LONG).show();
        }
    }

    static void created(SingleReelActivity activity) {
        if (launchPending != null && launchPending.id == activity.getIntent().getLongExtra(SingleReelActivity.EXTRA_HANDOFF, -1)) {
            launchPending = null;
            if (launchTimeout != null) MAIN.removeCallbacks(launchTimeout);
            launchTimeout = null;
        }
        SingleReelActivity previous = active.get();
        if (previous != null && previous != activity) previous.finish();
        active = new WeakReference<>(activity);
    }

    static void destroyed(SingleReelActivity activity) {
        if (active.get() == activity) active.clear();
    }

    static String text(Context context, String he, String en) {
        String language = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
        return "he".equals(language) || "iw".equals(language) ? he : en;
    }
}
