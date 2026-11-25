# Cabessa Social

Personal Android build for Dvir Cabessa, based on the supplied FeurStagram source and Instagram **456.0.0.53.78**.

The installable output is `dist/cabessa-social.apk`, package `com.dvircab.social`, application name **Cabessa Social**. It uses a dedicated release certificate and can be installed alongside the official Instagram package. Target device: **Android 9+ (API 28), ARM64**. The first personal test APK was built and signed on 25 November 2026; its exact checks and hashes are included in the accompanying release records.

## Fixed behavior

- Stories and messages together on the default home destination.
- Native private/group messages, stories, story/post creation, people search, profiles, profile posts, comments, likes and notifications remain enabled.
- The retained native feed tab requests Following content. In 0.1.3, the response and continuation are preserved for native paging. The chronology and account restrictions must be verified on Android.
- Notes, Explore and general Reels destinations remain blocked. A selected Reel
  from a Direct conversation opens in a single-video player, using a scoped
  native fetch and no next-item navigation. Other known Reel delivery requests are blocked. Media explicitly classified by Instagram as `product_type=clips` is filtered from the feed and its common playback sources are suppressed, including cached/embedded paths.
- The content policy is compiled into the app. There is no settings switch or timed unlock for blocked surfaces.
- No FeurStagram external updater, Debug bridge or Ghost Mode is included.
- Known telemetry upload paths are blocked. This is not a claim that the app is anonymous or that all telemetry has been eliminated. Login, message delivery, viewed-story receipts, uploads and other necessary service traffic remain native.
- Optional grayscale affects display, not the color of uploaded files. Display/about settings are available from the profile shortcut or a long press on the retained navigation shortcut.
- Instagram's original signing-trust checks are preserved by default. A separate build-only own-key compatibility patch remains explicitly opt-in; it is not enabled in the default build.

## Install and verify

[התקנה ובדיקה בעברית](INSTALL_HE.md)

This repository describes and builds a personal test release. Compilation and successful APK signing do not establish that login, push notifications, all navigation layouts or every server-delivered media shape work on a real device. The build receipt records what was actually checked. No Instagram credentials are stored in this repository.

## Build

See [BUILD_FROM_SOURCE.md](BUILD_FROM_SOURCE.md). A stock APK with a reviewed official signing certificate and an existing dedicated signing key outside the source tree are required. The release script validates the applied patch report, the resulting package/label, removed classes, signing certificate and APK alignment before delivering `dist/cabessa-social.apk`.

See [NETWORK_POLICY.md](NETWORK_POLICY.md) for the exact network/media policy and its limits. Tests cover streaming response integrity, native Following pagination, endpoint classification, preserved native requests, media classification and fail-closed release checks.

The private signing backup is separate from the source archive. Keep its keystore and password private: the same key is required to update this installed package without replacing its signing identity.

## Attribution

Derived from [FeurStagram](https://github.com/jean-voila/FeurStagram), with changes made for this personal build. The extension and patch source retain their original [GPLv3 license](../LICENSE) and [NOTICE](../NOTICE). The app shows the license and credits offline. Instagram's underlying application is a separate proprietary component; this repository contains the modification source and build instructions, not Instagram's source code.

Upstream historical documents, changelog and design assets describe upstream versions and may contain features deliberately removed from this build. This README and the current build/network/install documents describe Cabessa Social.
