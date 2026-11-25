# Build Cabessa Social

Cabessa Social applies the included patches to a separately supplied Instagram
Android APK. The result uses the fixed package `com.dvircab.social` and the label
`Cabessa Social`, so it can coexist with the official app. The Instagram APK and
the private signing key are not included in this source project.

## Local requirements

- JDK 21, including `java`, `javac` and `keytool`.
- Python 3 for the release gates and receipt.
- Android SDK with platform `android-35` and build-tools
  35.0.0. Set `ANDROID_HOME`; set `CABESSA_BUILD_TOOLS_VERSION` to select another
  installed build-tools version intentionally.
- Morphe Desktop 2.3.0 and the public Morphe patches library 2.3.0. Set
  `MORPHE_CLI` and `MORPHE_PATCHES_LIBRARY` to their absolute JAR paths.
- The Kotlin compiler libraries from the verified Gradle 9.5.0 binary
  distribution. Set `CABESSA_KOTLIN_LIB_DIR` to that distribution's `lib/`
  directory. Gradle itself and its private plugin registry are not used by the
  release route. Exact public download URLs and required hashes are listed in
  `scripts/manual_toolchain.json`; the build verifies them before compilation.
- For `.apkm`, `.xapk` or `.apks` input only, a reviewed APKEditor JAR selected by
  `APKEDITOR_JAR` or exactly one `tools/APKEditor-*.jar`.
- An Instagram APK or bundle whose origin and version you have checked. The
  script verifies the input APK signatures, records their certificates, and
  requires them to match the reviewed official certificate list in
  `scripts/trusted_instagram_signers.json`. This list was obtained from
  [Instagram's published app links](https://www.instagram.com/.well-known/assetlinks.json)
  on 25 November 2026 and includes the old and rotated signing certificates.
  A future certificate change needs an explicit review and source update.
- An existing release keystore, stored outside this repository and backed up
  securely. A new key is never silently generated as a fallback.

Once those public tools and the SDK are installed, compilation requires no
GitHub credentials. The original upstream Gradle files are retained for source
history; `build.sh` uses `scripts/build_bundle.py` instead. That script compiles
the Java extension, converts it to raw DEX with D8, compiles the Kotlin patches
for Java 17, and packages a desktop-compatible `.mpp`. It includes only the
public library's utility namespace, excluding unrelated patches.

Example tool selection:

```bash
export MORPHE_CLI="/path/to/morphe-desktop-2.3.0-all.jar"
export MORPHE_PATCHES_LIBRARY="/path/to/morphe-patches-library-2.3.0.jar"
export CABESSA_KOTLIN_LIB_DIR="/path/to/gradle-9.5.0/lib"
```

## Configure the release identity

The following example selects an existing key. It does not create or replace a
key. Enter the password privately; do not paste it into a chat, source file or
shell command argument.

```bash
export CABESSA_KEYSTORE="$HOME/.local/share/cabessa-social/release.p12"
export CABESSA_KEY_ALIAS="cabessa-social-release"
read -r -s -p "Keystore password: " CABESSA_KEYSTORE_PASS
printf '\n'
export CABESSA_KEYSTORE_PASS
```

If the key has a different password, read and export `CABESSA_KEY_PASS` in the
same way. Otherwise it defaults to `CABESSA_KEYSTORE_PASS`. `keytool` and
`apksigner` read these variables directly; passwords are not placed in process
arguments or build receipts. Keep the key for every future update. The build
does not send it to a third party or bundle it with the APK.

For a known input or an existing release identity, set the full expected digests:

```bash
export CABESSA_EXPECTED_BASE_SHA256="<64 hexadecimal characters from a trusted input record>"
export CABESSA_EXPECTED_SIGNER_SHA256="<64 hexadecimal characters for your release certificate>"
```

The first variable checks the supplied file, including a bundle if one is used.
The receipt also records the exact merged APK hash, when merging is necessary.

## Check and build

```bash
./build.sh --check /absolute/path/to/instagram.apk
./build.sh /absolute/path/to/instagram.apk
```

The check reports missing local prerequisites and verifies the public tool
hashes. It does not download dependencies, compile, sign, install or prove compatibility with an Instagram
version. The build uses only the fixed social policy; there is no debug build
flag or runtime override profile.

The build fails unless all of the following pass:

1. Input signatures verify against the reviewed official certificates, and
   split APKs have a common signing identity.
2. The patch report contains all required patches and no failed, debug, updater,
   Ghost Mode or broad signature-bypass patch.
3. The resulting APK has the expected package and label, is not debuggable or
   test-only, and lacks the package-install permission and removed extension
   classes.
4. Alignment for 16 KiB native-library pages and APK signature verification
   succeed; the APK's certificate
   matches the selected release key's public certificate.
5. Source files and the supplied input did not change during the build.

The signed result is `dist/cabessa-social.apk`. The same directory receives
`build-receipt.json`, `patch-report.json`, `release-signature.txt`, the public
certificate `release-certificate.der`, and `SHA256SUMS.txt`. The receipt records
source hashes, compiler and helper hashes, input hash, merged-base hash, patch-bundle hash, CLI hash, applied
patches, signature mode, APK hash and certificate fingerprint. It contains no
private key or password.

An unknown patch-report format fails the build rather than being treated as a
successful patch. A failed build leaves diagnostics in the reported temporary
build directory and does not deliver a new APK. If `dist/` already contained a
previous release, that previous APK may still be there; inspect its receipt.

## Signature compatibility investigation

Default builds omit the optional signing-key compatibility patch and preserve
Instagram's original internal trust checks. Test login, notifications, shared
links, profile and post links, and the Android share sheet on the target device.
Do not describe compatibility as established until those tests have run.

If those tests identify a concrete signing-related navigation failure, the
limited compatibility variant can be built explicitly:

```bash
./build.sh /absolute/path/to/instagram.apk --allow-own-signature
```

That variant trusts the current app's signing key while retaining Instagram's
decision for other keys. Legacy unconditional trust replacement is prohibited
and fails the build. The receipt clearly records whether this opt-in was used.
It does not disable Android APK signing or TLS certificate validation.

## Install and validate

After reviewing the build receipt:

```bash
adb install -r dist/cabessa-social.apk
```

Alternatively, `--install` installs a newly built, verified artifact through an
already connected ADB device. Package verification is not a runtime acceptance
test. Exercise messages, groups, stories, uploads, social notifications,
following-only feed, person search, profiles, likes/comments and grayscale.
Verify forbidden Reel entry points, cached content and direct links as well.
Observe actual traffic to evaluate telemetry coverage. The build receipt marks
device and privacy acceptance as not performed by the build script.

Manual future updates must use the same release key and pass the same checks.
An Instagram server or APK change can require maintaining these patches even
though the external updater has been removed.

## Validate the release gates themselves

```bash
python3 -m unittest discover -s scripts -p 'test_release_checks.py'
```

These are local tests of rejection behavior. They do not simulate Instagram or
claim on-device validation.
