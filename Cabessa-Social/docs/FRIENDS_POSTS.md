# Friends' posts in 0.1.3

The combined Stories/messages home has an action labelled
**פרסומים מהחברים · מהחדש לישן** (Friends' posts · newest first).
It opens the existing native Following destination. The feed uses native
continuous pagination, so scrolling loads older returned posts without a fixed
client-side post or page limit. Returning to the combined home restores the
messages/Stories destination.

Here, friends means accounts followed by the signed-in account. This is not a
new mutual-friend or Close Friends filter. Instagram decides which posts,
history depth and continuation are available. It is not an archive of expired
Stories or deleted/unavailable posts.

## Enforcing the request mode

The main timeline request map is copied and assigned
`pagination_source=following`, even when the original map is null or immutable.
The native request toString fingerprint is restricted to the class with Map
fields, because the supported APK has another unrelated class using the same
text.

The map is identified from the actual Map.get(pagination_source) call. The
patch also guards final string-parameter writes in the common timeline builder,
which both ordinary and streaming timeline requests use. This prevents later
native map merges from selecting a ranked mode again. Only that builder is
patched; unrelated request builders retain their own behavior. Every other
parameter, including max_id, is preserved.

From source preview 0.1.5, explicit hidden-author selections filter matching top-level timeline entries. Cursors and continuation flags remain unchanged. See [FEED_EXCLUSIONS.md](FEED_EXCLUSIONS.md). Previously timeline bodies passed through.
Existing parse-level advertisement, suggested-unit and Reel filters remain.
The source does not fetch every followed profile, independently verify each
relationship, or sort a complete archive locally. Chronology is obtained by
selecting the service's Following timeline and must be checked against actual
account data across multiple pages.

## Verification

Tests run the actual Java helpers against immutable maps, later ranked-source
overrides, absent maps, opaque cursors, Hebrew/English labels, story-row
replacement and navigation failures. Native bytecode inspection grounded the
request map and final builder hook in the exact Instagram base. The full APK
build verifies those fingerprints against that base.

On the phone: open the home action, scroll through several page loads, inspect
upload dates and authors, confirm no suggested/advertising units appear, and
check Back, refresh, Stories and conversations. History ends when Instagram
stops returning continuation data.
