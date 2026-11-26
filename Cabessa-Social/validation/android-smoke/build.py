#!/usr/bin/env python3
"""Build an isolated instrumentation APK from the current production Java files."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT / "build/android-smoke"
SDK = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
TOOLS = SDK / "build-tools" / os.environ.get("CABESSA_BUILD_TOOLS_VERSION", "35.0.0")
ANDROID = SDK / "platforms/android-35/android.jar"


def run(*args):
    subprocess.run([str(value) for value in args], check=True)


def main():
    for path in [ANDROID, TOOLS / "aapt2", TOOLS / "lib/d8.jar", TOOLS / "apksigner"]:
        if not path.is_file():
            raise SystemExit(f"Missing SDK input: {path}")
    if OUT.exists():
        shutil.rmtree(OUT)
    for part in ["classes", "dex", "assets", "generated"]:
        (OUT / part).mkdir(parents=True)
    run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
        "color=c=0x32425a:s=360x640:r=24", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
        "-t", "60", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "64k",
        "-movflags", "+faststart", OUT / "assets/fixture.mp4")
    run(TOOLS / "aapt2", "compile", "--dir", HERE / "res", "-o", OUT / "resources.zip")
    run(TOOLS / "aapt2", "link", "-o", OUT / "resources.apk", "-I", ANDROID,
        "--manifest", HERE / "AndroidManifest.xml", "--min-sdk-version", "28", "--target-sdk-version", "37",
        "-A", OUT / "assets", "--java", OUT / "generated", OUT / "resources.zip")
    production = sorted((ROOT / "extensions/extension/src/main/java").rglob("*.java"))
    sources = [p for p in production if p.name not in {"SingleReelSource.java", "SharedReelLoader.java"}]
    loader = next(p for p in production if p.name == "SharedReelLoader.java").read_text()
    for original, replacement in {
        "public static Object nativeFetch(Object session, String jsonIds, String module, NativeReelCallback callback) { return null; }":
            "public static Object nativeFetch(Object session, String jsonIds, String module, NativeReelCallback callback) { return SmokeNativeBridge.fetch(session,jsonIds,module,callback); }",
        "public static void nativeCancel(Object request) {}":
            "public static void nativeCancel(Object request) { SmokeNativeBridge.cancel(request); }",
    }.items():
        if loader.count(original) != 1:
            raise SystemExit("Native test bridge contract changed; review before modifying the harness")
        loader = loader.replace(original, replacement)
    loader_file = OUT / "generated/com/feurstagram/extension/SharedReelLoader.java"
    loader_file.parent.mkdir(parents=True, exist_ok=True)
    loader_file.write_text(loader)
    sources += sorted((HERE / "java").rglob("*.java"))
    sources += sorted((OUT / "generated").rglob("*.java"))
    run("javac", "--release", "17", "-encoding", "UTF-8", "-classpath", ANDROID,
        "-d", OUT / "classes", *sources)
    with zipfile.ZipFile(OUT / "classes.jar", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in (OUT / "classes").rglob("*.class"):
            archive.write(path, path.relative_to(OUT / "classes"))
    run("java", "-cp", TOOLS / "lib/d8.jar", "com.android.tools.r8.D8", "--release", "--min-api", "28",
        "--lib", ANDROID, "--output", OUT / "dex", OUT / "classes.jar")
    shutil.copyfile(OUT / "resources.apk", OUT / "unsigned.apk")
    with zipfile.ZipFile(OUT / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as archive:
        for path in (OUT / "dex").glob("*.dex"):
            archive.write(path, path.name)
    # This disposable key signs only the isolated test package. Never the release APK.
    run("keytool", "-genkeypair", "-noprompt", "-keystore", OUT / "smoke-only.p12", "-storetype", "PKCS12",
        "-storepass", "android", "-keypass", "android", "-alias", "smoke", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "2", "-dname", "CN=Cabessa Framework Test Only")
    run(TOOLS / "zipalign", "-P", "16", "-f", "4", OUT / "unsigned.apk", OUT / "aligned.apk")
    run(TOOLS / "apksigner", "sign", "--ks", OUT / "smoke-only.p12", "--ks-pass", "pass:android",
        "--key-pass", "pass:android", "--out", OUT / "cabessa-framework-smoke.apk", OUT / "aligned.apk")
    (OUT / "source-receipt.json").write_text(json.dumps({
        "production_sources": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                               for p in production if p.name != "SingleReelSource.java"},
        "test_doubles": ["SingleReelSource allows only local file/loopback fixtures; production CDN gate excluded from this APK",
                         "Only nativeFetch/nativeCancel placeholder bridge bodies in SharedReelLoader are replaced; its lifecycle and request gate remain production code"],
        "scope": "Android framework and synthetic caller; no Instagram account or real Story integration",
    }, indent=2) + "\n")
    print(OUT / "cabessa-framework-smoke.apk")


if __name__ == "__main__":
    main()
