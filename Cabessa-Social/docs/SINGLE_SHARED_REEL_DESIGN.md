# Single shared Reel player: implementation in 0.1.6

## Story and Direct entry points

The organic Story CLIPS reshare sticker and a selected Reel attachment in Direct
use the same isolated player. See [Story share integration](STORY_SHARED_REEL.md)
for the inspected native Story route and [the 0.1.6 report](../release/0.1.6-PUBLICATION.md)
for completed validation and the remaining phone acceptance check.

## User flow

Tap a shared Reel in a Story, direct conversation or group conversation. An
opaque, non-exported `SingleReelActivity` opens immediately, before the selected
media is resolved through Instagram's existing authenticated transport. Lookup
and playback happen in this same Activity. The original screen must finish
stopping before lookup or decoder preparation begins. The selected video is not
uploaded, converted to a Story, or passed to Instagram's Reel pager.

The player contains exactly one video source. It offers pause, play, replay,
seeking within the selected video, and close/back to the original screen.
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

The old fullscreen Dialog could leave the source Story Activity running. The
new Activity uses the platform opaque `Theme.Material.NoActionBar`, in the same
task and process. `SingleReelHandoff` watches the exact original Activity, then
posts its completion after the stop callback so the source's remaining
`onStop()`/fragment work has finished. Failure to observe a completed stop within
five seconds fails closed. Audio focus is additional protection, not the source
pause mechanism.

The selected ID and native session stay in a private, one-use in-memory handoff;
they are not serialized in an Intent or saved state. Leaving during lookup
cancels the native request and revokes its capability. Reopening the attachment
is required after this cancellation. Late responses cannot reopen the player.

The player uses TextureView so its image participates in the existing grayscale
view filter. It releases MediaPlayer, Surface, screen-on state and audio focus
when covered, closed or stopped. After resolved-media playback is interrupted,
it restores the playback position but requires an explicit Play tap. The actual
Window focus is reconciled after recreation, including Android's case where a
new focus-gained callback is not delivered. Playback requires the Activity to be
resumed, focused and foreground, and the source to have stopped.

It stops at completion, pauses on audio-focus loss, bounds media preparation to
30 seconds and bounds seek completion to 10 seconds. Deleted, restricted,
unsupported or unavailable media shows a local error and a route back; it never
opens the unrestricted native Reel viewer as a fallback. In multi-window use,
a source that remains started may produce the stop-timeout error rather than
allow parallel playback.

Controls use the shared light/dark palette, 48dp touch targets and the Activity
context's layout direction. Video time and the seekbar remain left-to-right.
Explicit system insets preserve the player's 16dp horizontal content margins.

## Validation and boundaries

Automated tests exercise actual Java policy/loader logic with native bridge and
Android UI doubles. They cover exact media selection, CDN URL validation,
cancellation, timeout, stale callbacks, synchronous completion, wrong/duplicate
response items and activity changes. The APK build checks native fingerprints,
compilation, required patches, final manifest/DEX classes and signing.

The [Android framework harness](../validation/android-smoke/README.md) also runs
the production player and UI against a synthetic source Activity and actual
MediaPlayer. It verifies source-stop ordering, decoded frames, controls,
background release, restoration without autoplay, Back, error handling and
pending lookup cancellation. It records the production source hashes and the
exact native-bridge and media-source test replacements. These checks are
distinct from real playback with an authenticated account on a phone.

Phone acceptance still needs a real Story containing video/music and a shared
Reel: confirm that both its audio and progress stop during lookup and playback,
then that Back returns correctly to the Story. Also check the Direct entry
point, backgrounding while loading/playing and the controls in the app's actual
theme/language. A different native entry point or future server/media response
shape may require maintenance.

Android references: [MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer),
[state and resource lifecycle](https://developer.android.com/media/platform/mediaplayer/state-resources),
[TextureView listener](https://developer.android.com/reference/android/view/TextureView.SurfaceTextureListener).
