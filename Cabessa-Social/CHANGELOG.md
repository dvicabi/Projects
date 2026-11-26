# Changelog

## 0.1.6 — consistent controls and isolated Story playback

- Use one restrained light/dark control system for Home, settings, hidden accounts and the shared-video player. Preserve Hebrew RTL, 48dp targets and large-text layouts.
- Replace the fullscreen video dialog with a non-exported opaque Activity; keep the source Story paused during lookup and playback.
- Wait until the original Activity has completed stopping before preparing a decoder. Require foreground ownership and a new Play tap after returning from background or recreation.
- Release media resources, cancel lookup and revoke the selected-request capability on cancellation or backgrounding.
- Add behavioral lifecycle regressions, compiled-APK manifest/class gates and an Android framework harness using synthetic media. The harness does not validate Instagram account behavior.
- Signed APK produced with the original release certificate. 29 source/release tests, eight Android framework scenarios and 48 UI renders passed. Authenticated Instagram phone acceptance remains pending; see [the publication report](release/0.1.6-PUBLICATION.md).

## 0.1.5 — persistent local feed exclusions

- Add a home and settings entry for hiding and restoring feed authors.
- Store explicit exclusions in private Android preferences; observe up to 200 recent authors in process memory.
- Prefer stable author IDs, upgrade manual handles on observation, and preserve native feed cursors and order.
- Restrict filtering to successful timeline responses; keep profiles, stories, messages and login unchanged.
- Signed APK produced on 2026-11-26; real-device acceptance remained pending.

## 0.1.4 — shared Reel from Stories (source preview)

- Intercept the organic CLIPS story reshare sticker before opening the native Reel pager.
- Reuse the isolated player and native authenticated one-media fetch. Match native primary/fallback identifier precedence; reject missing or malformed IDs.
- Retain DM playback, Following feed and all restrictions; generalize shared-player copy.
- Java loader regression cases and static inspection of the existing APK pass. Kotlin compilation, patch application, signing and device testing remain pending.

## 0.1.3 — single shared Reel and continuous Following feed

- Builds the previous Following-pagination source work into an installable update.
- Adds final timeline-parameter enforcement so later native map merges cannot restore ranked feed.
- Labels the combined-home shortcut “Friends’ posts · newest first”.
- Intercepts Direct Reel selections and resolves only the selected media through native authenticated transport.
- Adds a fullscreen single-video player with pause, replay, seek, close, grayscale, cancellation and resource cleanup.
- Keeps general Reel navigation and media getters guarded; a per-request ID/module capability permits the selected fetch only.
- Preserves the existing package, signing certificate, working native login and default original signature-trust checks.
- Adds targeted gate, loader, URL-source and Following-parameter regression coverage. Device playback/chronology checks remain pending.

## 0.1.2 — source preview (not built)

- Restore native continuation in the Following-only feed, while preserving ad and suggested-unit filtering and the existing Reel block.
- Retain the Friends’ posts shortcut from 0.1.1.
- Add regression checks for first and subsequent feed responses, preserving server-provided continuation.
- Shared-Reel playback is under design; the existing strict playback block remains unchanged.
- Full APK build and device acceptance are pending.

## 0.1.1 — source preview (not built)

- Added a Friends’ posts shortcut below the inbox stories tray.
- Opens the existing native Following-only destination.
- Retains the finite feed, original login flow and all fixed restrictions.
- Older posts outside the server-provided batch remain accessible through profiles; full feed-history pagination is not implemented.
- Android build and device validation are pending.

## 0.1.0-beta.1 — 2026-11-25

- First signed personal Android test build based on Instagram 456.0.0.53.78.
- Fixed content policy, stories/messages home, finite Following feed, grayscale.
- Removed external updater, Debug bridge, Ghost Mode and unlock timer.
- Signature-trust bypass not applied.
- Build verification completed; real-device and runtime-network testing pending.

The original upstream changelog is retained in [docs/upstream/FEURSTAGRAM_CHANGELOG.md](docs/upstream/FEURSTAGRAM_CHANGELOG.md).
