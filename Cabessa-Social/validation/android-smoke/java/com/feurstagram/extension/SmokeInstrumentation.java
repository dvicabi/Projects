package com.feurstagram.extension;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageButton;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** No AndroidX, simulated lifecycle calls or private-account traffic. */
public final class SmokeInstrumentation extends Instrumentation {
    private Bundle options;
    private final List<String> checks = new ArrayList<>();
    private final List<String> screenshots = new ArrayList<>();
    private File output;
    private String suffix;
    private int timeoutScale = 1;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); options = arguments == null ? new Bundle() : arguments; start(); }

    @Override public void onStart() {
        JSONObject result = new JSONObject();
        int exit = Activity.RESULT_CANCELED;
        try {
            output = getTargetContext().getExternalFilesDir(null);
            if (output == null || (!output.isDirectory() && !output.mkdirs())) throw new IllegalStateException("No test output directory");
            suffix = options.getString("suffix", "");
            timeoutScale = Integer.parseInt(options.getString("timeoutScale", "1"));
            require(timeoutScale >= 1 && timeoutScale <= 8, "timeoutScale must be1..8");
            boolean screenshotsOnly = "true".equals(options.getString("screenshotsOnly"));
            // Secure actual UI evidence before timing-sensitive media checks on
            // software-only emulators. The same assertions still determine pass/fail.
            boolean screenshotsFirst = timeoutScale > 1 && !screenshotsOnly;
            result.put("screenshots_first", screenshotsFirst);
            if (screenshotsFirst) renderUi();
            if (!screenshotsOnly) exercisePlayback();
            if (!screenshotsFirst) renderUi();
            result.put("status", "passed");
            exit = Activity.RESULT_OK;
        } catch (Throwable error) {
            try { result.put("status", "failed"); result.put("error", trace(error)); } catch (Exception ignored) {}
            try { if (output != null) screenshot("failure"); } catch (Throwable ignored) {}
        }
        try {
            result.put("framework_checks", new JSONArray(checks));
            result.put("timeout_scale", timeoutScale);
            result.put("fixture_duration_seconds", 60);
            result.put("screenshots", new JSONArray(screenshots));
            result.put("scope", "Actual Android Activity/MediaPlayer with synthetic caller and local fixtures; not real Instagram Story integration");
            result.put("test_doubles", "Local fixture URL resolver plus empty nativeFetch/nativeCancel bridge bodies only; release URL gate and real native bridges validated separately");
            Files.write(new File(output, "smoke-result" + (suffix.isEmpty() ? "" : "-" + suffix) + ".json").toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { exit = Activity.RESULT_CANCELED; }
        Bundle report = new Bundle();
        report.putString("stream", "\n" + result.toString() + "\n");
        finish(exit, report);
    }

    private void exercisePlayback() throws Exception {
        SmokeHostActivity origin = (SmokeHostActivity) startActivitySync(intent(SmokeHostActivity.class));
        await("synthetic Story starts", 5000, () -> SmokeHostActivity.resumed && origin.sourcePlayer.isPlaying());
        String fixture = Uri.fromFile(SmokeHostActivity.fixture(origin)).toString();
        SingleReelActivity viewer = launch(origin, fixture);
        await("caller stops before player starts", 8000, () -> {
            if (playing(viewer)) {
                require(SmokeHostActivity.stopped && SmokeHostActivity.stoppedAt > 0, "Player started before source onStop");
                require(!SmokeHostActivity.preparedBeforeStopReturned, "Player prepared inside caller super.onStop before its cleanup finished");
                require(!origin.sourcePlayer.isPlaying(), "Source and shared player overlap");
                return true;
            }
            return false;
        });
        await("real fixture video frame reaches TextureView", 8000, () -> hasFixtureFrame(viewer));
        checks.add("opaque_handoff_waits_for_caller_stop_before_media_start");
        screenshot("shared-player");
        main(() -> { ((Button) field(viewer, "playback")).performClick(); return null; });
        await("explicit pause", 3000, () -> !playing(viewer));
        SystemClock.sleep(250);
        require(main(() -> !playing(viewer)), "Paused player restarted");
        main(() -> { ((Button) field(viewer, "playback")).performClick(); return null; });
        await("explicit play", 3000, () -> playing(viewer));
        checks.add("actual_pause_and_play_controls");

        SmokeCoverActivity cover = cover(viewer);
        await("covered player releases decoder", 5000, () -> field(viewer, "player") == null);
        main(() -> { cover.finish(); return null; });
        await("return prepares without autoplay", 5000, () -> (Boolean) field(viewer, "prepared"));
        SystemClock.sleep(300);
        require(main(() -> !playing(viewer)), "Player autoplayed after returning from background");
        require(main(() -> !origin.sourcePlayer.isPlaying()), "Hidden Story resumed beneath returned player");
        checks.add("covered_player_releases_and_return_requires_explicit_play");
        main(() -> { ((Button) field(viewer, "playback")).performClick(); return null; });
        await("resume after cover", 3000, () -> playing(viewer));
        SystemClock.sleep(500);
        main(() -> { ((Button) field(viewer, "playback")).performClick(); return null; });
        long before = main(() -> (long) ((MediaPlayer) field(viewer, "player")).getCurrentPosition());
        ActivityMonitor recreation = addMonitor(SingleReelActivity.class.getName(), null, false);
        main(() -> { viewer.recreate(); return null; });
        SingleReelActivity restored = (SingleReelActivity) waitForMonitorWithTimeout(recreation, scaled(5000));
        removeMonitor(recreation);
        require(restored != null && restored != viewer, "Recreation did not create a fresh Activity");
        await("recreated player prepared and sought", 5000, () -> (Boolean) field(restored, "prepared") && !(Boolean) field(restored, "restoringPosition"));
        require(main(() -> !playing(restored)), "Recreated player autoplayed");
        long after = main(() -> (long) ((MediaPlayer) field(restored, "player")).getCurrentPosition());
        require(Math.abs(after - before) < 700, "Recreation lost playback position: " + before + " -> " + after);
        checks.add("activity_recreation_preserves_position_without_autoplay");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Back returns source", 5000, () -> restored.isDestroyed() && SmokeHostActivity.resumed && origin.sourcePlayer.isPlaying());
        checks.add("back_releases_shared_decoder_and_returns_to_source");

        SingleReelActivity missing = launch(origin, "file:///not-a-real-video-cabessa-test.mp4");
        await("unavailable media shows failure", 5000, () -> (Boolean) field(missing, "failed"));
        require(main(() -> field(missing, "player") == null && !origin.sourcePlayer.isPlaying()), "Failure leaked a decoder or resumed hidden source");
        screenshot("unavailable-player");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("error Back returns source", 5000, () -> SmokeHostActivity.resumed && origin.sourcePlayer.isPlaying());
        checks.add("unavailable_media_fails_closed_and_back_returns");

        byte[] bytes = Files.readAllBytes(SmokeHostActivity.fixture(origin).toPath());
        try (SlowFixtureServer server = new SlowFixtureServer(bytes, scaled(2000))) {
            SingleReelActivity slow = launch(origin, server.url());
            await("delayed fixture requested", 5000, () -> server.requested);
            SmokeCoverActivity delayedCover = cover(slow);
            await("preparing decoder released when covered", 5000, () -> field(slow, "player") == null);
            SystemClock.sleep(scaled(2400));
            require(main(() -> field(slow, "player") == null && !origin.sourcePlayer.isPlaying()), "Late response restarted hidden video");
            main(() -> { slow.finish(); delayedCover.finish(); return null; });
            await("late response test returns source", 5000, () -> SmokeHostActivity.resumed);
            checks.add("late_preparation_cannot_restart_background_playback");
        }
        SmokeNativeBridge.last = null;
        ActivityMonitor lookupMonitor = addMonitor(SingleReelActivity.class.getName(), null, false);
        main(() -> { SharedReelLoader.openFromStory(origin, new Object(), "123456_789", null); return null; });
        SingleReelActivity lookup = (SingleReelActivity) waitForMonitorWithTimeout(lookupMonitor, scaled(6000));
        removeMonitor(lookupMonitor);
        require(lookup != null, "Pending lookup did not open the opaque Activity first");
        await("metadata lookup waits for caller stop", 5000, () -> SmokeNativeBridge.last != null);
        SmokeNativeBridge.Pending pending = SmokeNativeBridge.last;
        require(pending.originStoppedBeforeFetch, "Native metadata lookup ran while Story source still played");
        require(main(() -> field(lookup,"player") == null && !origin.sourcePlayer.isPlaying()), "Pending lookup owns an unwanted decoder");
        screenshot("shared-lookup");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Back cancels metadata lookup", 5000, () -> lookup.isDestroyed() && pending.cancellations > 0 && SmokeHostActivity.resumed);
        require(main(() -> !SharedReelRequestGate.allows(pending.uri)), "Metadata request capability survived closing player");
        main(() -> { pending.callback.failure(new Object()); return null; });
        SystemClock.sleep(300);
        require(main(() -> SmokeHostActivity.resumed && origin.sourcePlayer.isPlaying()), "Late metadata callback reopened the player");
        checks.add("source_stops_before_pending_metadata_lookup_and_back_revokes_request");
        main(() -> { origin.finish(); return null; });
        waitForIdleSync();
    }

    private SingleReelActivity launch(SmokeHostActivity origin, String url) throws Exception {
        ActivityMonitor monitor = addMonitor(SingleReelActivity.class.getName(), null, false);
        main(() -> { SingleReelPlayer.launch(origin, url); return null; });
        SingleReelActivity viewer = (SingleReelActivity) waitForMonitorWithTimeout(monitor, scaled(6000));
        removeMonitor(monitor);
        require(viewer != null, "SingleReelActivity did not launch");
        return viewer;
    }

    private SmokeCoverActivity cover(Activity viewer) throws Exception {
        ActivityMonitor monitor = addMonitor(SmokeCoverActivity.class.getName(), null, false);
        main(() -> { viewer.startActivity(new Intent(viewer, SmokeCoverActivity.class)); return null; });
        SmokeCoverActivity cover = (SmokeCoverActivity) waitForMonitorWithTimeout(monitor, scaled(5000));
        removeMonitor(monitor);
        require(cover != null, "Opaque cover did not launch");
        return cover;
    }

    private void renderUi() throws Exception {
        boolean large = "true".equals(options.getString("largeFont"));
        Locale savedLocale = Locale.getDefault();
        LocaleList savedLocales = LocaleList.getDefault();
        try {
        // "he" deliberately keeps an English process default while the Activity
        // context is Hebrew, as with a per-app language selection. "he-system"
        // additionally proves the matching Hebrew process/context configuration.
        for (String variant : new String[]{"en", "he", "he-system"}) {
            String language = variant.startsWith("he") ? "he" : "en";
            Locale processLocale = "he-system".equals(variant) ? new Locale("he") : Locale.ENGLISH;
            for (boolean dark : new boolean[]{false, true}) {
                for (String scene : new String[]{"inbox", "profile", "settings", "feed"}) {
                    main(() -> {
                        LocaleList.setDefault(new LocaleList(processLocale));
                        Locale.setDefault(processLocale);
                        SmokeUiActivity.locale = new Locale(language);
                        SmokeUiActivity.dark = dark;
                        SmokeUiActivity.fontScale = large ? 1.3f : 1f;
                        return null;
                    });
                    Intent intent = intent(SmokeUiActivity.class).putExtra("scene", scene);
                    SmokeUiActivity ui = (SmokeUiActivity) startActivitySync(intent);
                    waitForIdleSync();
                    SystemClock.sleep(450);
                    require(main(() -> CabessaUi.colors(ui).dark == dark), "Test shell theme differs from requested palette");
                    main(() -> {
                        require(Locale.getDefault().getLanguage().equals(processLocale.getLanguage()),
                                "Test process locale differs from requested locale scenario");
                        int expected = "he".equals(language) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
                        ViewGroup content = (ViewGroup) ui.findViewById(android.R.id.content);
                        ViewGroup root = (ViewGroup) content.getChildAt(0);
                        require(root.getLayoutDirection() == expected, "Actual root layout direction differs from locale");
                        if ("inbox".equals(scene) || "profile".equals(scene)) {
                            require(root.getChildAt(1).getLayoutDirection() == expected,
                                    "Actual production control container has incorrect RTL/LTR direction");
                        }
                        if ("settings".equals(scene)) {
                            Field open = Settings.class.getDeclaredField("openDialog");
                            open.setAccessible(true);
                            android.app.Dialog dialog = (android.app.Dialog) ((java.lang.ref.WeakReference<?>) open.get(null)).get();
                            require(dialog != null && dialog.isShowing(), "Production settings dialog did not appear");
                            ViewGroup dialogContent = (ViewGroup) dialog.findViewById(android.R.id.content);
                            require(dialogContent.getChildAt(0).getLayoutDirection() == expected,
                                    "Actual production settings page has incorrect RTL/LTR direction");
                        }
                        return null;
                    });
                    if ("inbox".equals(scene)) main(() -> { checkTargets(ui.getWindow().getDecorView()); return null; });
                    checkAccessibleTargets(ui.getResources().getDisplayMetrics().density);
                    screenshot(scene + "-" + variant + "-" + (dark ? "dark" : "light"));
                    if ("settings".equals(scene) || "feed".equals(scene)) {
                        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                        waitForIdleSync();
                    }
                    main(() -> { ui.finish(); return null; });
                    waitForIdleSync();
                }
            }
        }
        } finally {
            main(() -> {
                LocaleList.setDefault(savedLocales);
                Locale.setDefault(savedLocale);
                return null;
            });
        }
    }

    private void checkTargets(View view) {
        if ((view instanceof Button || view instanceof ImageButton) && view.isShown()) {
            int min = Math.round(48 * view.getResources().getDisplayMetrics().density);
            require(view.getHeight() >= min - 1, "Button target height below48dp: " + view.getHeight());
            require(view.getWidth() >= min - 1, "Button target width below48dp: " + view.getWidth());
            if (view instanceof Button) {
                android.text.Layout layout = ((Button) view).getLayout();
                if (layout != null) for (int i = 0; i < layout.getLineCount(); i++) require(layout.getEllipsisCount(i) == 0, "Clipped button label");
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) checkTargets(((ViewGroup) view).getChildAt(i));
    }

    private void checkAccessibleTargets(float density) {
        android.app.UiAutomation automation = getUiAutomation();
        AccessibilityNodeInfo root = null;
        long deadline = SystemClock.uptimeMillis() + scaled(3000);
        while (SystemClock.uptimeMillis() < deadline) {
            root = automation.getRootInActiveWindow();
            if (root != null && getTargetContext().getPackageName().equals(String.valueOf(root.getPackageName()))) break;
            if (root != null) root.recycle();
            root = null;
            SystemClock.sleep(50);
        }
        require(root != null, "No active accessibility hierarchy for actual UI render");
        try {
            require(checkAccessibleNode(root, Math.round(48 * density)) > 0, "Requested UI did not expose any button or switch");
        } finally { root.recycle(); }
    }

    private int checkAccessibleNode(AccessibilityNodeInfo node, int minimum) {
        int found = 0;
        String type = String.valueOf(node.getClassName());
        if (node.isVisibleToUser() && ("android.widget.Button".equals(type)
                || "android.widget.ImageButton".equals(type) || "android.widget.Switch".equals(type))) {
            Rect box = new Rect();
            node.getBoundsInParent(box);
            require(box.height() >= minimum - 1, "Accessible control target shorter than48dp: " + node.getText() + ": " + box);
            require(box.width() >= minimum - 1, "Accessible control target narrower than48dp: " + node.getText() + ": " + box);
            found++;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) found += checkAccessibleNode(child, minimum);
        }
        return found;
    }

    private void screenshot(String name) throws Exception {
        waitForIdleSync();
        String filename = name + (suffix.isEmpty() ? "" : "-" + suffix) + ".png";
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "Framework screenshot unavailable");
        try (FileOutputStream stream = new FileOutputStream(new File(output, filename))) {
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "Screenshot encoding failed");
        } finally { bitmap.recycle(); }
        screenshots.add(filename);
    }

    private Intent intent(Class<? extends Activity> type) { return new Intent(getTargetContext(), type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); }
    private static Object field(Object instance, String name) throws Exception { Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
    private static boolean playing(SingleReelActivity activity) throws Exception { MediaPlayer mp = (MediaPlayer) field(activity, "player"); return mp != null && (Boolean) field(activity, "prepared") && mp.isPlaying(); }
    private static boolean hasFixtureFrame(SingleReelActivity activity) throws Exception {
        android.view.TextureView texture = (android.view.TextureView) field(activity,"texture");
        if (!texture.isAvailable()) return false;
        Bitmap bitmap = texture.getBitmap(3,3);
        if (bitmap == null) return false;
        try {
            int pixel = bitmap.getPixel(1,1);
            int red = android.graphics.Color.red(pixel), blue = android.graphics.Color.blue(pixel);
            return red >= 30 && blue >= 60 && blue > red + 15;
        } finally { bitmap.recycle(); }
    }
    private String viewerSnapshot() {
        JSONObject diagnostic = new JSONObject();
        try {
            Field active = SingleReelPlayer.class.getDeclaredField("active");
            active.setAccessible(true);
            SingleReelActivity viewer = (SingleReelActivity) ((java.lang.ref.WeakReference<?>) active.get(null)).get();
            diagnostic.put("active",viewer != null);
            if (viewer != null) {
                diagnostic.put("finishing",viewer.isFinishing());
                diagnostic.put("destroyed",viewer.isDestroyed());
                diagnostic.put("windowFocus",viewer.hasWindowFocus());
                for (String key : new String[]{"prepared","restoringPosition","failed","finished","lookupPending","lookupStarted","savedPosition"}) {
                    diagnostic.put(key,field(viewer,key));
                }
                diagnostic.put("hasSource",field(viewer,"source") != null);
                android.view.TextureView texture = (android.view.TextureView)field(viewer,"texture");
                diagnostic.put("textureAvailable",texture != null && texture.isAvailable());
                diagnostic.put("textureWidth",texture == null ? 0 : texture.getWidth());
                diagnostic.put("textureHeight",texture == null ? 0 : texture.getHeight());
                Object policy = field(viewer,"state");
                JSONObject state = new JSONObject();
                for (String key : new String[]{"sourceStopped","resumed","focused","prepared","wantsPlayback","failed","closed"}) state.put(key,field(policy,key));
                diagnostic.put("state",state);
                MediaPlayer mp = (MediaPlayer)field(viewer,"player");
                diagnostic.put("playerNull",mp == null);
                if (mp != null) {
                    try { diagnostic.put("position",mp.getCurrentPosition()); diagnostic.put("playing",mp.isPlaying()); diagnostic.put("duration",mp.getDuration()); }
                    catch (IllegalStateException error) { diagnostic.put("mediaError",error.toString()); }
                }
                diagnostic.put("status",String.valueOf(((android.widget.TextView)field(viewer,"status")).getText()));
            }
            diagnostic.put("sourceStopped",SmokeHostActivity.stopped);
            diagnostic.put("sourceResumed",SmokeHostActivity.resumed);
        } catch (Exception error) {
            try { diagnostic.put("snapshotError",error.toString()); } catch (Exception ignored) {}
        }
        return diagnostic.toString();
    }
    private interface Condition { boolean ready() throws Exception; }
    private void await(String label, long timeout, Condition condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + scaled(timeout);
        while (SystemClock.uptimeMillis() < deadline) { if (main(condition::ready)) return; SystemClock.sleep(35); }
        throw new AssertionError("Timed out: " + label + "; viewer=" + main(this::viewerSnapshot));
    }
    private long scaled(long milliseconds) { return milliseconds * timeoutScale; }
    private <T> T main(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { result.set(action.call()); } catch (Throwable caught) { error.set(caught); } });
        if (error.get() != null) throw new Exception("Main-thread test assertion", error.get());
        return result.get();
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static String trace(Throwable error) { StringWriter writer = new StringWriter(); error.printStackTrace(new PrintWriter(writer)); return writer.toString(); }
}
