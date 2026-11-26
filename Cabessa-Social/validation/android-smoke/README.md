# Android framework smoke test

This separate test application compiles the **current production Java sources**
and runs them on Android. It does not package Instagram, use account credentials,
or sign with the Cabessa Social release key.

## Run

Required: JDK 17 or 21, Python 3, ffmpeg, Android platform 35, build-tools 35.0.0,
platform-tools and an already booted Android emulator. Set `ANDROID_HOME`, then:

```bash
bash validation/android-smoke/run.sh
```

The test APK targets API 37, like the inspected Instagram base. The platform 35
compile library suffices because the extension uses no newer APIs. The runner
temporarily changes emulator display size and density for the narrow render and
resets both settings on exit. Run only against a disposable emulator.

## Meaningful checks

1. An opaque production `SingleReelActivity` stops its synthetic caller before
   preparing or starting the shared video. The caller deliberately continues
   playing audio during `onPause` and pauses only after `super.onStop()`.
   It also checks that the shared decoder is not constructed inside that super
   callback before the caller's remaining stop logic runs.
2. The actual playback button pauses and resumes a real `MediaPlayer`.
3. Covering the player with another opaque Activity releases its decoder;
   returning prepares a paused player and requires an explicit Play tap.
4. Actual `Activity.recreate()` retains position without autoplay.
5. Android Back closes the player and returns to the synthetic source.
6. An unavailable local media file leaves no decoder running; Back still works.
7. A delayed loopback media response arriving after the player is covered cannot
   restart background playback.
8. The source is stopped before a metadata lookup begins. While that lookup
   remains pending, Back cancels it and revokes the scoped request capability;
   a late callback cannot reopen the player.

The test also renders actual production inbox buttons, profile shortcut, settings
and hidden-account pages in Hebrew and English, light and dark themes. A second pass uses a genuine
320dp display width with 130% font scale. Inbox button targets and ellipsis are
checked programmatically; screenshots require visual review.

## Scope and test double

`SingleReelSource` is replaced in the test APK. Its test implementation
accepts local fixture files and a loopback HTTP server; **this replacement is
never compiled into the release APK**. Production URL authorization remains
covered by `tests/test_single_reel_source.py`. Two empty patch-time native request
bridge bodies (`nativeFetch` and `nativeCancel`) are replaced by a pending test
request; all loader lifecycle and request authorization code remains unchanged.
Original production source hashes and exact override descriptions are recorded
in `build/android-smoke/source-receipt.json`.

The test launches the production package-private handoff entry point. It does not
exercise the native Instagram media lookup bridges, Story controller, network
stack or patch bytecode. Therefore passing demonstrates framework lifecycle and
control behavior with a synthetic caller; it does not prove real Story playback
on the user's device.

## Outputs

- `build/android-smoke/device-results/smoke-result.json`: framework results.
- `build/android-smoke/device-results/*.png`: production Views rendered inside
  the explicitly labeled test shell, plus player and error screens.
- `build/android-smoke/narrow-results/smoke-result-narrow.json` and `*.png`:
  narrow-display/large-text render results.
- `build/android-smoke/instrumentation.txt`, `narrow-render.txt` and
  `android-crashes.txt`: runtime diagnostics.

The scripts fail when instrumentation crashes, required assertions fail, or the
structured result is missing. Prior app data is cleared before the initial run
so a stale result cannot pass a new run.
