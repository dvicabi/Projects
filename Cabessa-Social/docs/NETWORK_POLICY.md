# Fixed network policy

This fork keeps Instagram's native networking, account authentication, uploads,
messages, receipts and story-view reporting. It does not introduce a proxy or
credential store. The changes below describe source behavior; device verification
is required before treating the APK as a complete implementation of the product
requirements.

## Following feed

`LimitFeed.setFollowingHeader` writes `pagination_source=following` on every
main-feed request, including refreshes and requests whose header map was absent.
It copies the original map instead of mutating a possibly immutable map. There is
no runtime preference that selects the ranked feed. The final string-parameter
setters inside the common timeline builder enforce the same mode after later
native parameter maps are merged. Other parameters and other builders are preserved.

Successful Following timeline responses, including older pages, now pass through
unchanged at the response-filter layer. Native pagination cursors, flags, and
embedded comment/media data are retained. The request header is still forced to
Following and the existing parser-level ad, recommendation and Reel filters
remain active. Blocked surfaces retain their own pagination suppression.

The user requested continuous chronological scrolling of followed accounts.
The server decides available history, ordering and continuation. The existing
header rewrite is not independent verification of authors' follow relationships
or global chronological ordering across pages. Real-device validation is required
for both the initial page and subsequent pages; do not claim an unlimited archive.

## Reels and other content

In the 0.1.4 source preview the same one-item request gate is reused for explicit organic CLIPS Story sticker clicks; no wider endpoint exception is introduced. The new patch has not been applied to an APK.

Known Reel delivery endpoints are canceled in the native request path. In 0.1.3,
`SharedReelRequestGate` makes a narrow exception for a Direct-selected, single-ID
`/clips/items/` request with its own fresh module. Both request and response
classification require that exact capability; all other Reel requests remain
blocked. The upstream broad opened-Reel exception and response wait remain
removed. See [single-player implementation](SINGLE_SHARED_REEL_DESIGN.md).
The path matcher requires an Instagram domain and complete path-family boundaries;
it does not block arbitrary URLs containing `reel`, `discover` or `clips`.

The Stories paths `/feed/reels_tray/` and `/feed/reels_media/` are distinct from
Reels playback and remain allowed. The policy also leaves normal post uploads,
likes, comments, account search, profile data, direct video attachments, login and
security paths alone. Existing ad and suggested-unit filters are retained.

The native `Media` model provides an additional guard. The patch locates its
`product_type` getter by its wire-field literal and signature. The inspected stock
APK itself compares that getter's result with `clips`. Clips-marked media are
removed from parsed feed items. Their native `video_versions`,
`video_dash_manifest` and `local_video_path` getters return null, an already
supported missing-data result in those native methods. This also covers media
loaded from other endpoints or local caches when they use these getters. Ordinary
video, Stories and unrecognized product types keep the native getter behavior;
video media type alone is never a reason to block playback. A separately named
copy of the original video_versions getter is reserved for the isolated player;
it preserves lazy decoding without relaxing any existing native caller.

This is stronger than an endpoint deny list but must still be validated on a
device. A client path that uses a different model or already constructed player
source may need another hook. The request block uses Instagram's native error
handling for blocked native routes. The custom Direct player handles its own
selected-item failures locally and never falls back to the native Reel feed.

## Telemetry and diagnostics

Known telemetry upload paths are canceled only on Instagram and Meta domains.
Unrelated hosts with the same path are unaffected. Blocking is fixed by the
application policy; it does not suppress read receipts, typing or story views.
It also does not prove that every telemetry path in Instagram's proprietary code
is covered, particularly transports outside the hooked Tigon entry point.

The request/response filters no longer contain the upstream debug capture,
response recording, trace logging, or Ghost calls. They do not retain response
bodies for diagnostics. HTTP error responses are passed through. Unsupported
non-object or compressed payloads preserve their bytes rather than being replaced
with fabricated successful responses.

## Verification

`python3 tests/test_response_filter.py` compiles the shipped Java policy/filter
classes against a small test harness and checks them using Python's JSON parser.
It requires a JDK with `java` and `javac`. Coverage includes UTF-8 and escape
sequences split across arbitrary transport chunks, opaque cursor value types,
preservation of nested comment/media pagination, allowed native routes, blocked
unselected shared-Reel requests, the selected request exception, spoofed host/path boundaries, fixed Following headers, HTTP
errors, concurrent response isolation and state cleanup.
The media-classification cases verify that only the exact `clips` discriminator
is rejected, while normal, unknown and absent discriminators remain untouched.

These tests verify pure policy logic. They do not substitute for patch fingerprint
validation, final APK inspection, or real-device acceptance tests.
