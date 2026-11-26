package com.feurstagram.extension;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Collections;
import java.util.Locale;

/** Opaque foreground owner for one Reel. Back returns to the paused Story or Direct screen. */
public final class SingleReelActivity extends Activity implements TextureView.SurfaceTextureListener {
    static final String EXTRA_SOURCE = "com.feurstagram.extension.REEL_SOURCE";
    static final String EXTRA_HANDOFF = "com.feurstagram.extension.REEL_HANDOFF";
    static final String EXTRA_LOOKUP = "com.feurstagram.extension.REEL_LOOKUP";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
    private SingleReelPlaybackState state;
    private SingleReelHandoff handoff;
    private String source;
    private MediaPlayer player;
    private Surface surface;
    private TextureView texture;
    private TextView status;
    private TextView elapsed;
    private Button playback;
    private SeekBar progress;
    private ProgressBar loading;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean focusHeld;
    private boolean prepared;
    private boolean finished;
    private boolean failed;
    private boolean seeking;
    private boolean restoringPosition;
    private boolean lookupPending;
    private boolean lookupStarted;
    private long savedPosition;
    private int videoWidth;
    private int videoHeight;
    private final Runnable prepareTimeout = () -> fail(false);
    private final Runnable seekTimeout = () -> fail(false);

    @Override protected void onCreate(Bundle saved) {
        // This platform theme is opaque and non-floating. Never use a dialog or
        // translucent theme: the source Activity must reach onStop().
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(saved);
        state = new SingleReelPlaybackState(saved == null);
        source = SingleReelSource.authorizedCdnUrl(getIntent().getStringExtra(EXTRA_SOURCE));
        lookupPending = source == null && getIntent().getBooleanExtra(EXTRA_LOOKUP, false);
        if (saved != null) {
            savedPosition = Math.max(0, saved.getLong("position", 0));
            finished = saved.getBoolean("finished", false);
        }
        createContent();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes).setOnAudioFocusChangeListener(change -> {
                    if (change < 0) {
                        state.pauseRequested();
                        pauseMedia();
                    }
                }, handler).build();
        SingleReelPlayer.created(this);
        handoff = SingleReelHandoff.find(getIntent().getLongExtra(EXTRA_HANDOFF, -1));
        if (handoff != null) handoff.attach(this::onHandoffChanged);
        else if (saved != null && saved.getBoolean("sourceStopped", false)) {
            // Process restoration: the previous process and its decoder no
            // longer exist. Restored playback still requires a fresh Play tap.
            state.sourceStopped();
        } else fail(true);
        if (source == null && !lookupPending) fail(false);
    }

    /** Native request completion updates this same opaque screen, never launches another one. */
    void presentSource(String candidate) {
        if (failed || isFinishing()) return;
        lookupPending = false;
        source = SingleReelSource.authorizedCdnUrl(candidate);
        // Only the signed CDN URL may survive Activity recreation. The native
        // session and pending request are never placed in Intent/saved state.
        getIntent().putExtra(EXTRA_SOURCE, source);
        getIntent().putExtra(EXTRA_LOOKUP, false);
        if (source == null) fail(false);
        else prepareIfPossible();
    }

    private void createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(16), dp(4), dp(16), dp(12));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(text("סרטון משותף", "Shared video"), 17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        ImageButton close = CabessaUi.iconButton(this, CabessaUi.Icon.CLOSE, text("סגירה", "Close"), true);
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);

        FrameLayout frame = new FrameLayout(this);
        texture = new TextureView(this);
        texture.setSurfaceTextureListener(this);
        frame.addView(texture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loading = new ProgressBar(this);
        loading.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
        frame.addView(loading, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        status = label(text("טוען את הסרטון…", "Loading video…"), 15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        statusParams.topMargin = dp(76);
        frame.addView(status, statusParams);
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        progress = new SeekBar(this);
        progress.setMax(1000);
        progress.setEnabled(false);
        progress.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        progress.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xff363636));
        progress.setContentDescription(text("מיקום בסרטון", "Position in video"));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && prepared && player != null) {
                    try { elapsed.setText(time(player.getDuration() * (long) value / 1000) + " / " + time(player.getDuration())); }
                    catch (IllegalStateException ignored) { fail(false); }
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                if (!prepared || player == null) return;
                try {
                    seekPosition(player.getDuration() * (long) bar.getProgress() / 1000);
                    if (finished) {
                        finished = false;
                        state.pauseRequested();
                        status.setVisibility(View.GONE);
                        playback.setText(text("ניגון", "Play"));
                    }
                } catch (IllegalStateException ignored) { fail(false); }
            }
        });
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        elapsed = label("0:00 / 0:00", 13);
        elapsed.setTextColor(0xffa8a8a8);
        elapsed.setTextDirection(View.TEXT_DIRECTION_LTR);
        controls.addView(elapsed, new LinearLayout.LayoutParams(0, dp(48), 1));
        playback = CabessaUi.primaryButton(this, text("ניגון", "Play"), true);
        playback.setEnabled(false);
        playback.setOnClickListener(v -> toggle());
        controls.addView(playback, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(controls);
        setContentView(root);
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.setBackgroundDrawableResource(android.R.color.black);
        window.getDecorView().setSystemUiVisibility(0);
        if (Config.isGrayscale()) Grayscale.apply(root);
    }

    private void onHandoffChanged() {
        if (handoff.hasTimedOut()) { fail(true); return; }
        if (handoff.isStopped()) {
            state.sourceStopped();
            prepareIfPossible();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        state.resume();
        prepareIfPossible();
        maybeStart();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (state == null) return;
        state.windowFocus(hasFocus);
        if (hasFocus) {
            prepareIfPossible();
            maybeStart();
        } else pauseMedia();
    }

    @Override protected void onPause() {
        state.pause();
        SharedReelLoader.cancel(this);
        if (lookupPending) {
            if (handoff != null) handoff.discardSelection();
            fail(false);
        }
        pauseMedia();
        super.onPause();
    }

    @Override protected void onStop() {
        rememberPosition();
        releaseMedia();
        super.onStop();
    }

    @Override protected void onDestroy() {
        state.close();
        releaseMedia();
        if (handoff != null) {
            handoff.detach();
            if (!isChangingConfigurations()) handoff.close();
        }
        SingleReelPlayer.destroyed(this);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle saved) {
        rememberPosition();
        saved.putLong("position", savedPosition);
        saved.putBoolean("finished", finished);
        saved.putBoolean("sourceStopped", state.hasStoppedSource());
        super.onSaveInstanceState(saved);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) { prepareIfPossible(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { fitVideo(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        rememberPosition();
        releaseMedia();
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    private void prepareIfPossible() {
        if (!state.canPrepare()) return;
        if (lookupPending && !lookupStarted) {
            SingleReelHandoff.Selection selection = handoff == null ? null : handoff.takeSelection();
            lookupStarted = true;
            if (selection == null) fail(false);
            else SharedReelLoader.loadInPlayer(this, selection.session, selection.mediaId);
            return;
        }
        if (player != null || source == null || !texture.isAvailable()) return;
        try {
            surface = new Surface(texture.getSurfaceTexture());
            MediaPlayer created = new MediaPlayer();
            player = created;
            loading.setVisibility(View.VISIBLE);
            playback.setEnabled(false);
            created.setAudioAttributes(audioAttributes);
            created.setSurface(surface);
            created.setLooping(false);
            created.setOnPreparedListener(mp -> {
                if (failed || player != mp) return;
                handler.removeCallbacks(prepareTimeout);
                prepared = true;
                state.prepared();
                loading.setVisibility(View.GONE);
                status.setVisibility(finished ? View.VISIBLE : View.GONE);
                if (finished) status.setText(text("הסרטון הסתיים", "Video finished"));
                playback.setText(text(finished ? "ניגון חוזר" : "ניגון", finished ? "Replay" : "Play"));
                playback.setEnabled(true);
                progress.setEnabled(mp.getDuration() > 0);
                videoWidth = mp.getVideoWidth();
                videoHeight = mp.getVideoHeight();
                fitVideo();
                updateProgress();
                if (savedPosition > 0 && !finished) {
                    seekPosition(Math.min(savedPosition, Math.max(0, mp.getDuration() - 1)));
                } else maybeStart();
            });
            created.setOnSeekCompleteListener(mp -> {
                if (player != mp || failed) return;
                handler.removeCallbacks(seekTimeout);
                restoringPosition = false;
                updateProgress();
                maybeStart();
            });
            created.setOnVideoSizeChangedListener((mp, w, h) -> {
                if (player != mp) return;
                videoWidth = w;
                videoHeight = h;
                fitVideo();
            });
            created.setOnCompletionListener(mp -> {
                if (player != mp || failed) return;
                finished = true;
                state.pauseRequested();
                abandonAudioFocus();
                keepScreenOn(false);
                playback.setText(text("ניגון חוזר", "Replay"));
                status.setText(text("הסרטון הסתיים", "Video finished"));
                status.setVisibility(View.VISIBLE);
                progress.setProgress(1000);
                savedPosition = mp.getDuration();
                elapsed.setText(time(savedPosition) + " / " + time(savedPosition));
            });
            created.setOnErrorListener((mp, what, extra) -> { if (player == mp) fail(false); return true; });
            created.setOnInfoListener((mp, what, extra) -> {
                if (player == mp && prepared && !failed) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) loading.setVisibility(View.VISIBLE);
                    else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) loading.setVisibility(View.GONE);
                }
                return false;
            });
            // Original signed CDN URL; no account cookie, new API or resolver.
            created.setDataSource(this, Uri.parse(source), Collections.singletonMap("android-allow-cross-domain-redirect", "0"));
            handler.postDelayed(prepareTimeout, 30000L);
            created.prepareAsync();
        } catch (Exception ignored) { fail(false); }
    }

    private void maybeStart() {
        if (!state.canStart() || restoringPosition || player == null || isFinishing()) return;
        if (!focusHeld) {
            try { focusHeld = audio != null && audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED; }
            catch (RuntimeException ignored) { focusHeld = false; }
            if (!focusHeld) {
                state.pauseRequested();
                status.setText(text("לא ניתן לנגן כרגע. נסה שוב אחרי סיום השמע באפליקציה האחרת.",
                        "Try Play again after audio in the other app has finished."));
                status.setVisibility(View.VISIBLE);
                return;
            }
        }
        try {
            if (finished) {
                finished = false;
                seekPosition(0);
                return;
            }
            player.start();
            status.setVisibility(View.GONE);
            playback.setText(text("השהיה", "Pause"));
            keepScreenOn(true);
            handler.removeCallbacks(tick);
            handler.post(tick);
        } catch (IllegalStateException ignored) { fail(false); }
    }

    private void toggle() {
        if (!prepared || player == null || failed) return;
        try {
            if (player.isPlaying()) {
                state.pauseRequested();
                pauseMedia();
            } else {
                state.playRequested();
                maybeStart();
            }
        } catch (IllegalStateException ignored) { fail(false); }
    }

    private void seekPosition(long position) {
        savedPosition = Math.max(0, position);
        restoringPosition = true;
        handler.removeCallbacks(seekTimeout);
        handler.postDelayed(seekTimeout, 10000L);
        player.seekTo(savedPosition, MediaPlayer.SEEK_CLOSEST);
    }

    private void pauseMedia() {
        if (player != null && prepared) {
            try {
                if (player.isPlaying()) player.pause();
                playback.setText(text(finished ? "ניגון חוזר" : "ניגון", finished ? "Replay" : "Play"));
                rememberPosition();
                updateProgress();
            } catch (IllegalStateException ignored) { fail(false); }
        }
        abandonAudioFocus();
        keepScreenOn(false);
        handler.removeCallbacks(tick);
    }

    private void rememberPosition() {
        if (!prepared || player == null || restoringPosition) return;
        try { savedPosition = Math.max(0, player.getCurrentPosition()); }
        catch (IllegalStateException ignored) {}
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!prepared || player == null || failed) return;
            updateProgress();
            try { if (player != null && player.isPlaying()) handler.postDelayed(this, 300); }
            catch (IllegalStateException ignored) { fail(false); }
        }
    };

    private void updateProgress() {
        if (seeking || player == null || !prepared) return;
        try {
            int duration = player.getDuration();
            int position = player.getCurrentPosition();
            if (duration > 0) progress.setProgress((int) (position * 1000L / duration));
            elapsed.setText(time(position) + " / " + time(duration));
        } catch (IllegalStateException ignored) { fail(false); }
    }

    private void fitVideo() {
        if (texture == null || videoWidth <= 0 || videoHeight <= 0 || texture.getWidth() <= 0 || texture.getHeight() <= 0) return;
        float width = texture.getWidth();
        float height = texture.getHeight();
        float scale = Math.min(width / videoWidth, height / videoHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f);
        texture.setTransform(matrix);
    }

    private void fail(boolean sourceBusy) {
        if (failed) return;
        failed = true;
        state.fail();
        releaseMedia();
        loading.setVisibility(View.GONE);
        status.setText(sourceBusy
                ? text("לא ניתן להשהות את הסרטון הקודם. חזור לסטורי ונסה שוב.", "Couldn't pause the previous video. Return to the Story and try again.")
                : text("הסרטון אינו זמין. ייתכן שנמחק, שהגישה אליו מוגבלת, או שאין חיבור.", "This video is unavailable. It may be deleted, restricted, or offline."));
        status.setVisibility(View.VISIBLE);
        playback.setEnabled(false);
        progress.setEnabled(false);
    }

    private void releaseMedia() {
        handler.removeCallbacksAndMessages(null);
        prepared = false;
        restoringPosition = false;
        state.mediaReleased();
        abandonAudioFocus();
        keepScreenOn(false);
        if (player != null) {
            MediaPlayer old = player;
            player = null;
            old.setOnPreparedListener(null);
            old.setOnCompletionListener(null);
            old.setOnErrorListener(null);
            old.setOnInfoListener(null);
            old.setOnVideoSizeChangedListener(null);
            old.setOnSeekCompleteListener(null);
            try { old.release(); } catch (RuntimeException ignored) {}
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    private void abandonAudioFocus() {
        if (audio != null && focusRequest != null && focusHeld) {
            try { audio.abandonAudioFocusRequest(focusRequest); } catch (RuntimeException ignored) {}
        }
        focusHeld = false;
    }

    private void keepScreenOn(boolean enabled) {
        if (enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private TextView label(String value, int sp) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(sp);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private String text(String he, String en) { return SingleReelPlayer.text(this, he, en); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String time(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
