# Single shared Reel player: implementation in 0.1.3

## 0.1.4 source-only extension

The organic Story CLIPS reshare sticker is routed to the same loader. See [Story share integration](STORY_SHARED_REEL.md) for the exact inspected route, new tests and pending build/device checks. The remainder of this document describes the last signed release, 0.1.3.

## User flow

Tap a Reel attachment in a direct or group conversation. A cancellable loading
panel resolves that selected media through Instagram's existing authenticated
transport. The result opens in a fullscreen, story-style local player. It is not
uploaded, converted to a Story, or passed to Instagram's Reel pager.

The player contains exactly one video source. It offers pause, play, replay,
seeking within the selected video, and close/back to the same conversation.
Completion stops on that video. There is no next item, swipe navigation,
recommendation adapter or autoplay queue. The display follows the grayscale
preference. The isolated viewer currently has playback controls only; native
Reel like/comment/share buttons are not included. Existing post interactions in
other screens retain their native behavior.

## Grounded integration with Instagram 456.0.0.53.78

`SingleSharedReelPatch.kt` resolves DirectClipsViewerNavigator by its selected
media/message argument strings and checks its parameter layout. In this base,
`LX/0aoY.FBd` receives the selected media ID at p11 and an optional shortcode at
p12. The patch routes that Direct-specific entry point to `SharedReelLoader`
and returns before the native pageable viewer opens. Ordinary message videos
and Stories use their original entry points.

The loader calls the version-resolved `ClipsApiUtilHelper` one-item fetch using
the existing UserSession, request scheduler, parser and callback infrastructure.
It sends one quoted numeric media ID, with an optional numeric owner suffix.
A fresh in-memory module identifies that particular request. No account cookie
or password is copied into the Java extension, persisted or sent to another
service. Shortcodes use Instagram's own converter.

`SharedReelRequestGate` permits only an HTTPS Instagram `/clips/items/` request
whose query has exactly that ID and module. Other requests remain blocked.
Duplicate query keys, multiple IDs, another endpoint, continuation cursors,
untrusted hosts and expired capabilities fail closed. Grants expire after 30
seconds; the visible loader cancels after 20 seconds and revokes its grant on
completion, cancellation, replacement, activity pause or error. These are
individual request deadlines, not a temporary content-unlock setting.

The native response is checked for the selected media ID. An owner suffix can
be added or omitted by native models; conflicting fully specified IDs and
multiple matching response objects are rejected. A late response after cancel
cannot reopen a player.

`FeedItemFilterPatch.kt` copies the original video_versions accessor before
adding the ordinary Reel guard. The copy retains native cached-list and lazy
Pando decoding, and is called only by the isolated player bridge. All existing
native callers still use the guarded getter. The bridge extracts a URL through
`VideoVersionIntf.getUrl()`, verifies an existing HTTPS cdninstagram.com/fbcdn.net
URL, and gives one source to Android's MediaPlayer. The signed query is
preserved. No external resolver, alternate account, download backend or cookie
forwarding is introduced. Cross-domain redirects are disabled.

## Lifecycle and failure behavior

The player uses TextureView so its image participates in the existing grayscale
view filter. It releases MediaPlayer, Surface, screen-on state and audio focus
on close or host activity pause/stop/destroy. It stops at completion, pauses on
audio-focus loss, and bounds media preparation to 30 seconds. Deleted, restricted,
unsupported or unavailable media shows a local error and a route back to chat;
it never opens the unrestricted native Reel viewer as a fallback.

## Validation and boundaries

Automated tests exercise actual Java policy/loader logic with native bridge and
Android UI doubles. They cover exact media selection, CDN URL validation,
cancellation, timeout, stale callbacks, synchronous completion, wrong/duplicate
response items and activity changes. The APK build checks native fingerprints,
compilation, required patches and signing. These checks are distinct from real
playback with an authenticated account on a phone.

Device acceptance still needs: open several fresh and previously opened DM
Reels; try swipes in all directions; reach the end and replay; use Back; cancel
while loading; background the app; receive a second selection; verify ordinary
Stories/videos and grayscale. A different native DM entry point, future server
response shape, DASH-only media or unusual CDN behavior may require maintenance.

Android references: [MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer),
[state and resource lifecycle](https://developer.android.com/media/platform/mediaplayer/state-resources),
[TextureView listener](https://developer.android.com/reference/android/view/TextureView.SurfaceTextureListener).
