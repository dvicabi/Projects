#!/usr/bin/env bash
# Cabessa Social release build. This script never generates a signing key.
# Passwords are read by the JDK/SDK tools from environment variables, not argv.
set +x
set -euo pipefail
umask 077

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK=""
CHECK_ONLY=0
INSTALL=0
ALLOW_OWN_SIGNATURE=0

usage() {
    cat <<'USAGE'
Usage: ./build.sh [--check] <instagram.apk|.apkm|.xapk|.apks> [--install] [--allow-own-signature]

--check                 Check local prerequisites without compiling or installing.
--install               Install the verified result on the connected ADB device.
--allow-own-signature   Explicitly include own-signing-key deep-link compatibility.
                        The default preserves Instagram's original trust checks.

Required signing environment (an existing, backed-up key outside this repository):
  CABESSA_KEYSTORE       Absolute path to the keystore
  CABESSA_KEY_ALIAS      Alias of the release key
  CABESSA_KEYSTORE_PASS  Keystore password, supplied privately in the environment
  CABESSA_KEY_PASS       Key password (defaults to the keystore password)

Public compiler inputs: MORPHE_CLI, MORPHE_PATCHES_LIBRARY, CABESSA_KOTLIN_LIB_DIR.
Optional: APKEDITOR_JAR, CABESSA_BUILD_TOOLS_VERSION (default 35.0.0),
          CABESSA_EXPECTED_BASE_SHA256, CABESSA_EXPECTED_SIGNER_SHA256.
See docs/BUILD_FROM_SOURCE.md for setup and the required on-device checks.
USAGE
}

for arg in "$@"; do
    case "$arg" in
        --help|-h) usage; exit 0 ;;
        --check) CHECK_ONLY=1 ;;
        --install) INSTALL=1 ;;
        --allow-own-signature) ALLOW_OWN_SIGNATURE=1 ;;
        --*) printf 'Unsupported option: %s\n' "$arg" >&2; usage >&2; exit 2 ;;
        *)
            if [ -n "$APK" ]; then
                printf 'Only one input APK or bundle is supported.\n' >&2
                exit 2
            fi
            APK="$arg"
            ;;
    esac
done

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/keytool}"
KEYTOOL="${KEYTOOL:-keytool}"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/javac}"
JAVAC="${JAVAC:-javac}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export ANDROID_HOME
BUILD_TOOLS_VERSION="${CABESSA_BUILD_TOOLS_VERSION:-35.0.0}"
SDK_TOOLS="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
APKSIGNER="$SDK_TOOLS/apksigner"
AAPT2="$SDK_TOOLS/aapt2"
ZIPALIGN="$SDK_TOOLS/zipalign"
KEYSTORE="${CABESSA_KEYSTORE:-}"
KEY_ALIAS="${CABESSA_KEY_ALIAS:-}"
CLI="${MORPHE_CLI:-}"
EDITOR="${APKEDITOR_JAR:-}"
CHECKER="$DIR/scripts/release_checks.py"

# Require an unambiguous tool selection. A newer file's mtime is not a version pin.
shopt -s nullglob
if [ -z "$CLI" ]; then
    cli_candidates=("$DIR"/tools/morphe-cli-*.jar "$DIR"/tools/morphe-desktop-*.jar)
    if [ "${#cli_candidates[@]}" -eq 1 ]; then CLI="${cli_candidates[0]}"; fi
fi
if [ -z "$EDITOR" ]; then
    editor_candidates=("$DIR"/tools/APKEditor-*.jar)
    if [ "${#editor_candidates[@]}" -eq 1 ]; then EDITOR="${editor_candidates[0]}"; fi
fi
shopt -u nullglob

missing=()
if ! command -v python3 >/dev/null 2>&1; then missing+=("Python 3 is required for release validation."); fi
if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
    missing+=("JDK 21 is required; set JAVA_HOME to its installation.")
else
    java_version="$("$JAVA_BIN" -version 2>&1)"
    if [[ ! "$java_version" =~ version\ \"21[\.\"] ]]; then
        missing+=("JDK 21 is required; the selected Java is a different version.")
    fi
fi
if ! command -v "$KEYTOOL" >/dev/null 2>&1; then missing+=("JDK keytool is unavailable."); fi
if ! command -v "$JAVAC" >/dev/null 2>&1; then missing+=("JDK javac is unavailable; install a full JDK, not only a JRE."); fi
if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME/platforms" ]; then
    missing+=("ANDROID_HOME must point to an SDK with the required Android platform installed.")
fi
for sdk_tool in "$APKSIGNER" "$AAPT2" "$ZIPALIGN"; do
    if [ ! -x "$sdk_tool" ]; then missing+=("Required Android build tool is missing: $sdk_tool"); fi
done
if [ -z "$CLI" ] || [ ! -f "$CLI" ]; then
    missing+=("Set MORPHE_CLI to one reviewed Morphe CLI JAR (or place exactly one in tools/).")
fi
if [ ! -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ] || [ ! -f "$DIR/gradlew" ]; then
    missing+=("The Gradle wrapper is incomplete.")
fi
if [ ! -f "$CHECKER" ]; then missing+=("The release validation script is missing."); fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    missing+=("Supply an existing official Instagram APK or supported split bundle.")
else
    case "$APK" in
        *.apk) ;;
        *.apkm|*.xapk|*.apks)
            if [ -z "$EDITOR" ] || [ ! -f "$EDITOR" ]; then
                missing+=("Split bundles require APKEDITOR_JAR or exactly one tools/APKEditor-*.jar.")
            fi
            ;;
        *) missing+=("Input must end in .apk, .apkm, .xapk or .apks.") ;;
    esac
fi
if [ -z "$KEYSTORE" ] || [ ! -f "$KEYSTORE" ]; then
    missing+=("CABESSA_KEYSTORE must identify an existing release keystore; none will be generated.")
elif command -v python3 >/dev/null 2>&1; then
    if ! python3 "$CHECKER" check-keystore "$KEYSTORE" "$DIR"; then
        missing+=("Keep the release keystore outside the source repository.")
    fi
fi
if [ -z "$KEY_ALIAS" ]; then missing+=("Set CABESSA_KEY_ALIAS to the existing release key alias."); fi
if [ -z "${CABESSA_KEYSTORE_PASS:-}" ]; then
    missing+=("Supply CABESSA_KEYSTORE_PASS privately in the environment.")
fi
if [ "$INSTALL" -eq 1 ] && ! command -v adb >/dev/null 2>&1; then missing+=("--install requires adb on PATH."); fi
if [ "${#missing[@]}" -ne 0 ]; then
    printf 'Build prerequisites are incomplete:\n' >&2
    printf '  - %s\n' "${missing[@]}" >&2
    exit 1
fi
export MORPHE_CLI="$CLI"
python3 "$DIR/scripts/build_bundle.py" --check

if [ "$CHECK_ONLY" -eq 1 ]; then
    printf 'Local prerequisites are present. Network access, credentials, patch compatibility and device behavior are not yet verified.\n'
    exit 0
fi

APK="$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve())' "$APK")"
CLI="$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve())' "$CLI")"
KEYSTORE="$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve())' "$KEYSTORE")"
export CABESSA_KEYSTORE_PASS
export CABESSA_KEY_PASS="${CABESSA_KEY_PASS:-$CABESSA_KEYSTORE_PASS}"
mkdir -p "$DIR/build" "$DIR/dist"
WORK="$(mktemp -d "$DIR/build/release.XXXXXXXX")"
cleanup() {
    result=$?
    if [ "$result" -ne 0 ]; then
        printf 'Build failed; no new release was delivered. Diagnostic files: %s\n' "$WORK" >&2
        rm -f "$WORK/unsigned.apk" "$WORK/aligned.apk" "$WORK/cabessa-social.apk"
    fi
}
trap cleanup EXIT

INPUT_APK="$APK"
INPUT_SHA="$(python3 "$CHECKER" sha256 "$INPUT_APK")"
if [ -n "${CABESSA_EXPECTED_BASE_SHA256:-}" ]; then
    python3 "$CHECKER" check-hash "$INPUT_SHA" "$CABESSA_EXPECTED_BASE_SHA256"
fi

# Export only the public certificate. The private key never enters the build tree.
"$KEYTOOL" -exportcert -alias "$KEY_ALIAS" -keystore "$KEYSTORE" \
    -storepass:env CABESSA_KEYSTORE_PASS -file "$WORK/release-certificate.der" >/dev/null
SIGNER_SHA="$(python3 "$CHECKER" sha256 "$WORK/release-certificate.der")"
if [ -n "${CABESSA_EXPECTED_SIGNER_SHA256:-}" ]; then
    python3 "$CHECKER" check-hash "$SIGNER_SHA" "$CABESSA_EXPECTED_SIGNER_SHA256"
fi

case "$APK" in
    *.apkm|*.xapk|*.apks)
        EDITOR="$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve())' "$EDITOR")"
        # Verify every input APK before merging, with one common signing identity.
        python3 "$CHECKER" verify-bundle "$APK" "$APKSIGNER" "$WORK/bundle-verification.json"
        APK="$WORK/merged-base.apk"
        "$JAVA_BIN" -Xmx4g -jar "$EDITOR" m -f -i "$INPUT_APK" -o "$APK" > "$WORK/merge.log" 2>&1
        ;;
    *)
        "$APKSIGNER" verify --verbose --print-certs "$APK" > "$WORK/base-signature.txt"
        python3 "$CHECKER" check-base-signer "$WORK/base-signature.txt"
        ;;
esac
BASE_SHA="$(python3 "$CHECKER" sha256 "$APK")"

printf 'Building the patch bundle.\n'
python3 "$CHECKER" source-snapshot "$DIR" "$WORK/source-before-build.json"
python3 "$DIR/scripts/build_bundle.py"
MPP="$(python3 "$CHECKER" find-bundle "$DIR/patches/build/libs")"
REPORT="$WORK/patch-report.json"
ARGS=(-jar "$CLI" patch -p "$MPP" -r "$REPORT" -o "$WORK/unsigned.apk" --unsigned)
if [ "$ALLOW_OWN_SIGNATURE" -eq 1 ]; then ARGS+=(-e "Own signing-key compatibility"); fi
ARGS+=("$APK")
printf 'Applying the fixed social policy.\n'
"$JAVA_BIN" -Xmx4g "${ARGS[@]}"
python3 "$CHECKER" check-report "$REPORT" "$ALLOW_OWN_SIGNATURE"
"$ZIPALIGN" -P 16 -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

printf 'Signing with the configured release key.\n'
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:CABESSA_KEYSTORE_PASS --key-pass env:CABESSA_KEY_PASS \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --v4-signing-enabled false --out "$WORK/cabessa-social.apk" "$WORK/aligned.apk"
"$APKSIGNER" verify --verbose --print-certs "$WORK/cabessa-social.apk" > "$WORK/release-signature.txt"
"$ZIPALIGN" -c -P 16 4 "$WORK/cabessa-social.apk"
"$AAPT2" dump badging "$WORK/cabessa-social.apk" > "$WORK/release-badging.txt"
python3 "$CHECKER" verify-release "$WORK/cabessa-social.apk" \
    "$WORK/release-signature.txt" "$WORK/release-badging.txt" "$SIGNER_SHA"
python3 "$CHECKER" receipt "$DIR" "$WORK" "$INPUT_APK" "$INPUT_SHA" "$BASE_SHA" \
    "$SIGNER_SHA" "$CLI" "$MPP" "$ALLOW_OWN_SIGNATURE" "$EDITOR"

# Only a verified, signed APK is copied to dist. No private signing material is copied.
cp "$WORK/patch-report.json" "$DIR/dist/patch-report.json"
cp "$WORK/release-signature.txt" "$DIR/dist/release-signature.txt"
cp "$WORK/release-certificate.der" "$DIR/dist/release-certificate.der"
cp "$WORK/build-receipt.json" "$DIR/dist/build-receipt.json"
cp "$WORK/SHA256SUMS.txt" "$DIR/dist/SHA256SUMS.txt"
mv "$WORK/cabessa-social.apk" "$DIR/dist/cabessa-social.apk"
printf 'Verified build: %s\n' "$DIR/dist/cabessa-social.apk"
printf 'On-device acceptance and traffic checks remain required before calling this build validated.\n'
if [ "$INSTALL" -eq 1 ]; then adb install -r "$DIR/dist/cabessa-social.apk"; fi
