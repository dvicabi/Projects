package com.feurstagram.extension;

/** Main-thread state gate: preparation and playback require an exclusive foreground owner. */
final class SingleReelPlaybackState {
    private boolean sourceStopped;
    private boolean resumed;
    private boolean focused;
    private boolean prepared;
    private boolean wantsPlayback;
    private boolean failed;
    private boolean closed;

    SingleReelPlaybackState(boolean autoplay) { wantsPlayback = autoplay; }

    void sourceStopped() { sourceStopped = true; }
    boolean hasStoppedSource() { return sourceStopped; }
    void resume() { resumed = true; }
    void pause() { resumed = false; wantsPlayback = false; }
    void windowFocus(boolean value) {
        if (focused && !value) wantsPlayback = false;
        focused = value;
    }
    void prepared() { prepared = true; }
    void mediaReleased() { prepared = false; }
    void playRequested() { wantsPlayback = true; }
    void pauseRequested() { wantsPlayback = false; }
    void fail() { failed = true; wantsPlayback = false; }
    void close() { closed = true; wantsPlayback = false; }

    boolean canPrepare() { return sourceStopped && resumed && focused && !failed && !closed; }
    boolean canStart() { return canPrepare() && prepared && wantsPlayback; }
}
