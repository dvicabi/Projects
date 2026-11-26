package com.feurstagram.extension;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Wait for the actual Story/Direct Activity to stop before opening another
 * decoder. Audio focus is not a substitute for stopping the native Story clock.
 * All calls and Android lifecycle callbacks run on the main thread.
 */
final class SingleReelHandoff implements Application.ActivityLifecycleCallbacks {
    private static final Map<Long, SingleReelHandoff> PENDING = new HashMap<>();
    private static long nextId;
    private final WeakReference<Activity> origin;
    private final Application application;
    private final Handler handler = new Handler(Looper.getMainLooper());
    final long id;
    private boolean stopped;
    private boolean timedOut;
    private boolean registered;
    private boolean stopQueued;
    private boolean claimed;
    private boolean closed;
    private Runnable listener;
    private Selection selection;
    private final Runnable timeout = () -> {
        if (closed) return;
        if (!claimed) { close(); return; }
        if (stopped) return;
        timedOut = true;
        selection = null;
        unregister();
        if (listener != null) listener.run();
    };

    /** Native session stays only in process memory, never in Intent or saved state. */
    static final class Selection {
        final Object session;
        final String mediaId;
        Selection(Object session, String mediaId) { this.session = session; this.mediaId = mediaId; }
    }

    private SingleReelHandoff(Activity source) {
        id = ++nextId;
        origin = new WeakReference<>(source);
        application = source.getApplication();
        application.registerActivityLifecycleCallbacks(this);
        registered = true;
        handler.postDelayed(timeout, 5000L);
    }

    static SingleReelHandoff begin(Activity source) {
        SingleReelHandoff handoff = new SingleReelHandoff(source);
        PENDING.put(handoff.id, handoff);
        return handoff;
    }

    static SingleReelHandoff find(long id) { return PENDING.get(id); }
    boolean isStopped() { return stopped; }
    boolean hasTimedOut() { return timedOut; }
    void setSelection(Object session, String mediaId) { if (!closed && !timedOut) selection = new Selection(session, mediaId); }
    void discardSelection() { selection = null; }
    Selection takeSelection() {
        if (!stopped || closed || timedOut) return null;
        Selection value = selection;
        selection = null;
        return value;
    }

    void attach(Runnable value) {
        if (closed) return;
        claimed = true;
        listener = value;
        if (stopped) handler.removeCallbacks(timeout);
        if (stopped || timedOut) value.run();
    }

    void detach() { listener = null; }

    void close() {
        closed = true;
        selection = null;
        detach();
        unregister();
        handler.removeCallbacksAndMessages(null);
        origin.clear();
        PENDING.remove(id);
    }

    private void unregister() {
        if (registered) application.unregisterActivityLifecycleCallbacks(this);
        registered = false;
    }

    private void sourceStopped(Activity activity) {
        if (activity != origin.get() || timedOut || stopped || stopQueued || closed) return;
        stopQueued = true;
        // Application.onActivityStopped is dispatched inside Activity.super.onStop;
        // FragmentActivity stops its fragments AFTER calling super. Complete on
        // the next main-loop turn, after the whole native onStop stack unwinds.
        handler.post(() -> {
            if (closed || timedOut || PENDING.get(id) != this) return;
            stopped = true;
            if (claimed) handler.removeCallbacks(timeout);
            unregister();
            origin.clear();
            if (listener != null) listener.run();
        });
    }

    @Override public void onActivityStopped(Activity a) { sourceStopped(a); }
    @Override public void onActivityDestroyed(Activity a) { sourceStopped(a); }
    @Override public void onActivityCreated(Activity a, Bundle state) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityResumed(Activity a) {}
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
}
