<div align="center">

# CABESSA SOCIAL

**More connection. Less distraction.**

![Version](https://img.shields.io/badge/version-0.1.3%20beta-5069AC)
![Android](https://img.shields.io/badge/Android-9%2B-3DDC84)
![Architecture](https://img.shields.io/badge/ABI-ARM64-5069AC)
![License](https://img.shields.io/badge/patches-GPLv3-blue)

סטורי · הודעות · פרסומים מהנעקבים · סרטון אחד בכל פעם

</div>

## Release 0.1.3

| Feature | Behavior |
| --- | --- |
| Friends' posts | Following feed with chronological continuation |
| Shared reels | Single-video player; no next reel |
| Home | Messages and stories together |
| Privacy | No added external update service or debug bridge |
| Install | Android 9+, ARM64; package `com.dvircab.social` |

**Publication status:** this branch is a partial source snapshot, not yet the complete buildable repository. The complete 195-file source archive and signed APK were produced separately and remain in the project conversation. A GitHub Release asset has not been uploaded. No private signing material is included.

## Verification

The previous build report records 16 applied patches and 21 passed automated tests. Real-device playback, feed order, notification behavior and runtime traffic still need verification. See [build report](docs/BUILD_REPORT.md) and [release checksums](release/SHA256SUMS.txt).

## Selected source

- [Following-only policy](extensions/extension/src/main/java/com/feurstagram/extension/LimitFeed.java)
- [Request policy](extensions/extension/src/main/java/com/feurstagram/extension/RequestPolicy.java)
- [One-reel request gate](extensions/extension/src/main/java/com/feurstagram/extension/SharedReelRequestGate.java)
- [Media source validator](extensions/extension/src/main/java/com/feurstagram/extension/SingleReelSource.java)

## Attribution

Modification sources are derived from [FeurStagram](https://github.com/jean-voila/FeurStagram) and governed by GPLv3. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Instagram is proprietary, separate software. This project is unofficial and is not affiliated with Meta. Modified clients can cause compatibility or account restrictions.

