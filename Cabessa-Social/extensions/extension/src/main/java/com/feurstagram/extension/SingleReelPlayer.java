package com.feurstagram.extension;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Collections;

/**
 * A shared attachment viewer containing exactly one media source. It is a
 * local presentation of the original Reel, not a Story upload or a feed.
 * There is no pager, next item, recommendation request or content-unlock state.
 */
public final class SingleReelPlayer {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Viewer> active = new WeakReference<>(null);

    private SingleReelPlayer() {}

    /** Replaced by the version-resolved patch with the selected media's MP4 URL. */
    public static String mediaVideoUrl(Object media) { return null; }

    /**
     * Called only after the native message or Story reshare attachment entry point. A known Reel is
     * always consumed, even if unavailable, so failure cannot open the native
     * Reel feed. Other media keep their existing native viewer.
     */
    public static boolean open(Context context, Object media) {
        if (!Block.isReelMedia(media)) return false;
        String candidate;
        try { candidate = SingleReelSource.authorizedCdnUrl(mediaVideoUrl(media)); }
        catch (Throwable ignored) { candidate = null; }
        final String source = candidate;
        if (Looper.myLooper() == Looper.getMainLooper()) show(context, source);
        else MAIN.post(() -> show(context, source));
        return true;
    }

    /** Consume a selected but inaccessible attachment without opening a Reel feed. */
    public static void unavailable(Context context) {
        if (Looper.myLooper() == Looper.getMainLooper()) show(context, null);
        else MAIN.post(() -> show(context, null));
    }

    private static void show(Context context, String source) {
        Activity activity = Activities.forContext(context);
        if (!Activities.hasWindow(activity)) {
            Context fallback = context != null ? context : Config.getAppContext();
            if (fallback != null) Toast.makeText(fallback,
                    text(fallback, "לא ניתן לפתוח את הסרטון כרגע. חזור למסך הקודם ונסה שוב.",
                            "Can't open this video right now. Return to the previous screen and try again."),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Viewer previous = active.get();
        if (previous != null) previous.dismiss();
        Viewer viewer = new Viewer(activity, source);
        try {
            viewer.show();
            Window window = viewer.getWindow();
            if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            active = new WeakReference<>(viewer);
        } catch (RuntimeException ignored) {
            viewer.release();
            Toast.makeText(activity, text(activity, "הסרטון אינו זמין כרגע.", "This video is unavailable right now."), Toast.LENGTH_LONG).show();
        }
    }

    private static String text(Context context, String he, String en) {
        String language = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
        return "he".equals(language) || "iw".equals(language) ? he : en;
    }

    private static final class Viewer extends Dialog implements TextureView.SurfaceTextureListener,
            Application.ActivityLifecycleCallbacks {
        private final Activity activity;
        private final String source;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
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
        private boolean released;
        private boolean seeking;
        private boolean registered;
        private int videoWidth;
        private int videoHeight;
        private final Runnable prepareTimeout = () -> fail();

        Viewer(Activity activity, String source) {
            super(activity, android.R.style.Theme_Material_NoActionBar);
            this.activity = activity;
            this.source = source;
            setCanceledOnTouchOutside(false);
        }

        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.BLACK);
            root.setFitsSystemWindows(true);
            root.setPadding(dp(16), dp(8), dp(16), dp(12));

            LinearLayout header = new LinearLayout(activity);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = label(text(activity, "סרטון משותף", "Shared video"), 18);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
            Button close = button(text(activity, "סגירה", "Close"));
            close.setOnClickListener(v -> dismiss());
            header.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
            root.addView(header);

            TextView counter = label("1 / 1", 13);
            counter.setTextColor(0xffb9b9be);
            counter.setContentDescription(text(activity, "סרטון אחד מתוך אחד", "Video one of one"));
            counter.setGravity(Gravity.CENTER);
            root.addView(counter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

            FrameLayout frame = new FrameLayout(activity);
            texture = new TextureView(activity);
            texture.setSurfaceTextureListener(this);
            frame.addView(texture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            loading = new ProgressBar(activity);
            frame.addView(loading, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
            status = label(text(activity, "טוען את הסרטון…", "Loading video…"), 16);
            status.setGravity(Gravity.CENTER);
            status.setPadding(dp(16), dp(16), dp(16), dp(16));
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            statusParams.topMargin = dp(80);
            frame.addView(status, statusParams);
            root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            progress = new SeekBar(activity);
            progress.setMax(1000);
            progress.setEnabled(false);
            progress.setContentDescription(text(activity, "מיקום בסרטון", "Position in this video"));
            progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (fromUser && prepared && player != null) {
                        try { elapsed.setText(time(player.getDuration() * (long) value / 1000) + " / " + time(player.getDuration())); }
                        catch (IllegalStateException ignored) { fail(); }
                    }
                }
                @Override public void onStopTrackingTouch(SeekBar bar) {
                    seeking = false;
                    if (!prepared || player == null) return;
                    try {
                        player.seekTo(player.getDuration() * (long) bar.getProgress() / 1000, MediaPlayer.SEEK_CLOSEST);
                        if (finished) {
                            finished = false;
                            status.setVisibility(View.GONE);
                            playback.setText(text(activity, "ניגון", "Play"));
                        }
                    } catch (IllegalStateException ignored) { fail(); }
                }
            });
            root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
            LinearLayout controls = new LinearLayout(activity);
            controls.setGravity(Gravity.CENTER_VERTICAL);
            elapsed = label("0:00 / 0:00", 13);
            elapsed.setTextDirection(View.TEXT_DIRECTION_LTR);
            controls.addView(elapsed, new LinearLayout.LayoutParams(0, dp(52), 1));
            playback = button(text(activity, "ניגון", "Play"));
            playback.setEnabled(false);
            playback.setOnClickListener(v -> toggle());
            controls.addView(playback, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
            root.addView(controls);
            TextView note = label(text(activity, "הסרטון הזה בלבד · חזרה סוגרת למסך הקודם", "Only this video · Back returns to the previous screen"), 12);
            note.setGravity(Gravity.CENTER);
            note.setTextColor(0xffb9b9be);
            root.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
            setContentView(root);
            Window window = getWindow();
            if (window != null) {
                window.setStatusBarColor(Color.BLACK);
                window.setNavigationBarColor(Color.BLACK);
                window.getDecorView().setSystemUiVisibility(0);
            }
            if (Config.isGrayscale()) Grayscale.apply(root);
            audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes).setOnAudioFocusChangeListener(change -> {
                        if (change < 0) {
                            focusHeld = false;
                            pause();
                        }
                    }, handler).build();
            activity.getApplication().registerActivityLifecycleCallbacks(this);
            registered = true;
            if (source == null) fail();
        }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            if (released || failed || source == null) return;
            try {
                surface = new Surface(surfaceTexture);
                player = new MediaPlayer();
                player.setAudioAttributes(audioAttributes);
                player.setSurface(surface);
                player.setLooping(false);
                player.setOnPreparedListener(mp -> {
                    if (released || failed || player != mp) return;
                    handler.removeCallbacks(prepareTimeout);
                    prepared = true;
                    loading.setVisibility(View.GONE);
                    status.setVisibility(View.GONE);
                    playback.setEnabled(true);
                    progress.setEnabled(mp.getDuration() > 0);
                    videoWidth = mp.getVideoWidth();
                    videoHeight = mp.getVideoHeight();
                    fitVideo();
                    updateProgress();
                    play();
                });
                player.setOnVideoSizeChangedListener((mp, w, h) -> {
                    videoWidth = w;
                    videoHeight = h;
                    fitVideo();
                });
                player.setOnCompletionListener(mp -> {
                    if (released || player != mp) return;
                    finished = true;
                    abandonAudioFocus();
                    keepScreenOn(false);
                    playback.setText(text(activity, "ניגון חוזר", "Replay"));
                    status.setText(text(activity, "הסרטון הסתיים", "Video finished"));
                    status.setVisibility(View.VISIBLE);
                    progress.setProgress(1000);
                    elapsed.setText(time(mp.getDuration()) + " / " + time(mp.getDuration()));
                });
                player.setOnErrorListener((mp, what, extra) -> { fail(); return true; });
                player.setOnInfoListener((mp, what, extra) -> {
                    if (!released && prepared && !failed) {
                        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) loading.setVisibility(View.VISIBLE);
                        else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) loading.setVisibility(View.GONE);
                    }
                    return false;
                });
                // The model supplied this signed URL. No account cookie, session
                // token, API request or third-party resolver is introduced here.
                player.setDataSource(activity, Uri.parse(source), Collections.singletonMap("android-allow-cross-domain-redirect", "0"));
                handler.postDelayed(prepareTimeout, 30000L);
                player.prepareAsync();
            } catch (Exception ignored) { fail(); }
        }

        private void play() {
            if (released || failed || !prepared || player == null) return;
            if (!focusHeld && audio != null) {
                try { focusHeld = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED; }
                catch (RuntimeException ignored) { focusHeld = false; }
                if (!focusHeld) {
                    status.setText(text(activity, "כדי לנגן, נסה שוב אחרי סיום השמע באפליקציה האחרת.",
                            "Try Play again after audio in the other app has finished."));
                    status.setVisibility(View.VISIBLE);
                    return;
                }
            }
            try {
                if (finished) player.seekTo(0, MediaPlayer.SEEK_CLOSEST);
                finished = false;
                player.start();
                status.setVisibility(View.GONE);
                playback.setText(text(activity, "השהיה", "Pause"));
                keepScreenOn(true);
                handler.removeCallbacks(tick);
                handler.post(tick);
            } catch (IllegalStateException ignored) { fail(); }
        }

        private void toggle() {
            if (!prepared || player == null || released || failed) return;
            try { if (player.isPlaying()) pause(); else play(); }
            catch (IllegalStateException ignored) { fail(); }
        }

        private void pause() {
            if (released || !prepared || player == null) return;
            try {
                if (player.isPlaying()) player.pause();
                playback.setText(text(activity, finished ? "ניגון חוזר" : "ניגון", finished ? "Replay" : "Play"));
                abandonAudioFocus();
                keepScreenOn(false);
                handler.removeCallbacks(tick);
                updateProgress();
            } catch (IllegalStateException ignored) { fail(); }
        }

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (released || failed || !prepared || player == null) return;
                updateProgress();
                try { if (player.isPlaying()) handler.postDelayed(this, 300); }
                catch (IllegalStateException ignored) { fail(); }
            }
        };

        private void updateProgress() {
            if (seeking || player == null || !prepared || released) return;
            try {
                int duration = player.getDuration();
                int position = player.getCurrentPosition();
                if (duration > 0) progress.setProgress((int) (position * 1000L / duration));
                elapsed.setText(time(position) + " / " + time(duration));
            } catch (IllegalStateException ignored) { fail(); }
        }

        private void fitVideo() {
            if (texture == null || videoWidth <= 0 || videoHeight <= 0
                    || texture.getWidth() <= 0 || texture.getHeight() <= 0) return;
            float width = texture.getWidth();
            float height = texture.getHeight();
            float scale = Math.min(width / videoWidth, height / videoHeight);
            Matrix matrix = new Matrix();
            matrix.setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f);
            texture.setTransform(matrix);
        }

        private void fail() {
            if (released || failed) return;
            failed = true;
            prepared = false;
            releaseMedia();
            if (loading != null) loading.setVisibility(View.GONE);
            if (status != null) {
                status.setText(text(activity,
                        "לא ניתן לנגן את הסרטון הזה. ייתכן שנמחק, שהגישה אליו מוגבלת, או שאין חיבור. אפשר לחזור למסך הקודם ולנסות לפתוח אותו שוב.",
                        "This video can't be played. It may be deleted, restricted, or offline. Return to the previous screen and try opening it again."));
                status.setVisibility(View.VISIBLE);
            }
            if (playback != null) playback.setEnabled(false);
            if (progress != null) progress.setEnabled(false);
        }

        private void releaseMedia() {
            handler.removeCallbacksAndMessages(null);
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
                try { old.release(); } catch (RuntimeException ignored) {}
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
        }

        private void abandonAudioFocus() {
            if (audio != null && focusRequest != null) {
                try { audio.abandonAudioFocusRequest(focusRequest); } catch (RuntimeException ignored) {}
            }
            focusHeld = false;
        }

        private void keepScreenOn(boolean enabled) {
            Window window = getWindow();
            if (window != null) {
                if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }

        private void release() {
            if (released) return;
            released = true;
            releaseMedia();
            if (registered) {
                activity.getApplication().unregisterActivityLifecycleCallbacks(this);
                registered = false;
            }
            if (active.get() == this) active.clear();
        }

        @Override protected void onStop() { release(); super.onStop(); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) { fitVideo(); }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) { releaseMedia(); prepared = false; return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}
        @Override public void onActivityPaused(Activity a) { if (a == activity) dismiss(); }
        @Override public void onActivityStopped(Activity a) { if (a == activity) dismiss(); }
        @Override public void onActivityDestroyed(Activity a) { if (a == activity) dismiss(); }
        @Override public void onActivityCreated(Activity a, Bundle state) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityResumed(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}

        private TextView label(String value, int sp) {
            TextView label = new TextView(activity);
            label.setText(value);
            label.setTextSize(sp);
            label.setTextColor(Color.WHITE);
            label.setGravity(Gravity.CENTER_VERTICAL);
            return label;
        }

        private Button button(String value) {
            Button button = new Button(activity);
            button.setText(value);
            button.setAllCaps(false);
            button.setTextColor(Color.WHITE);
            button.setMinHeight(dp(48));
            button.setMinWidth(dp(80));
            return button;
        }

        private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
        private String time(long millis) {
            long seconds = Math.max(0, millis) / 1000;
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
    }
}
