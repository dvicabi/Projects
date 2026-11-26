package com.feurstagram.extension;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** A deliberately strict caller: its video keeps playing until onStop, not onPause. */
public final class SmokeHostActivity extends Activity {
    static volatile SmokeHostActivity instance;
    static volatile boolean resumed;
    static volatile boolean stopped;
    static volatile int stops;
    static volatile long stoppedAt;
    static volatile boolean preparedBeforeStopReturned;
    MediaPlayer sourcePlayer;

    static File fixture(Activity context) throws Exception {
        File file = new File(context.getFilesDir(), "fixture.mp4");
        if (!file.isFile()) {
            try (InputStream input = context.getAssets().open("fixture.mp4");
                 FileOutputStream output = new FileOutputStream(file)) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = input.read(bytes)) >= 0) output.write(bytes, 0, count);
            }
        }
        return file;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        stopped = false;
        stoppedAt = 0;
        preparedBeforeStopReturned = false;
        TextView label = new TextView(this);
        label.setBackgroundColor(Color.BLACK);
        label.setTextColor(Color.WHITE);
        label.setTextSize(22);
        label.setPadding(40, 80, 40, 40);
        label.setText("Cabessa Social framework test\n\nSynthetic Story caller\nAudio pauses only in onStop.");
        setContentView(label);
        try {
            sourcePlayer = MediaPlayer.create(this, Uri.fromFile(fixture(this)));
            if (sourcePlayer == null) throw new IllegalStateException("Fixture MediaPlayer unavailable");
            sourcePlayer.setLooping(true);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    @Override public void onResume() {
        super.onResume();
        resumed = true;
        stopped = false;
        if (sourcePlayer != null) sourcePlayer.start();
    }
    @Override public void onPause() { resumed = false; super.onPause(); }
    @Override public void onStop() {
        // FragmentActivity also calls super before dispatching fragment stop.
        super.onStop();
        try {
            java.lang.reflect.Field active = SingleReelPlayer.class.getDeclaredField("active");
            active.setAccessible(true);
            Object viewer = ((java.lang.ref.WeakReference<?>) active.get(null)).get();
            if (viewer != null) {
                java.lang.reflect.Field decoder = viewer.getClass().getDeclaredField("player");
                decoder.setAccessible(true);
                preparedBeforeStopReturned |= decoder.get(viewer) != null;
            }
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
        if (sourcePlayer != null && sourcePlayer.isPlaying()) sourcePlayer.pause();
        stopped = true;
        stoppedAt = android.os.SystemClock.elapsedRealtimeNanos();
        stops++;
    }
    @Override public void onDestroy() {
        if (sourcePlayer != null) { sourcePlayer.release(); sourcePlayer = null; }
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
