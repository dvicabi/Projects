#!/usr/bin/env python3
"""Build the local patch bundle from source with public, hash-pinned tools.

The default release route does not require Morphe's private Gradle registry.
No code is downloaded, keys generated, or APKs installed by this script.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

from release_checks import sha256

ROOT = Path(__file__).resolve().parents[1]
LOCK = Path(__file__).with_name("manual_toolchain.json")


def checked_file(path: Path, digest: str | None = None) -> Path:
    if not path.is_file():
        raise ValueError(f"Required tool file is missing: {path}")
    if digest and sha256(path) != digest:
        raise ValueError(f"Tool checksum differs from the reviewed version: {path.name}")
    return path.resolve()


def tool(name: str) -> str:
    selected = str(Path(os.environ["JAVA_HOME"]) / "bin" / name) if os.environ.get("JAVA_HOME") else name
    resolved = shutil.which(selected)
    if not resolved:
        raise ValueError(f"JDK 21 tool is unavailable: {name}")
    return resolved


def resolve_tools() -> dict:
    lock = json.loads(LOCK.read_text())
    cli = checked_file(Path(os.environ.get("MORPHE_CLI", ROOT / "tools/morphe-desktop-2.3.0-all.jar")), lock["morphe_cli"]["sha256"])
    library = checked_file(Path(os.environ.get("MORPHE_PATCHES_LIBRARY", ROOT / "tools/morphe-patches-library-2.3.0.jar")), lock["patches_library"]["sha256"])
    kotlin_dir = Path(os.environ.get("CABESSA_KOTLIN_LIB_DIR", ROOT / "tools/gradle-9.5.0/lib"))
    compiler_jars = [checked_file(kotlin_dir / name, digest) for name, digest in lock["kotlin_compiler_libraries"].items()]
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    build_tools = sdk / "build-tools" / os.environ.get("CABESSA_BUILD_TOOLS_VERSION", "35.0.0")
    android = checked_file(sdk / "platforms/android-35/android.jar")
    d8 = checked_file(build_tools / "lib/d8.jar")
    return {"java": tool("java"), "javac": tool("javac"), "cli": cli, "library": library,
            "compiler_jars": compiler_jars, "android": android, "d8": d8, "lock": lock}


def run(command: list[str | Path], logfile: Path) -> None:
    with logfile.open("w") as output:
        result = subprocess.run([str(part) for part in command], stdout=output, stderr=subprocess.STDOUT, cwd=ROOT)
    if result.returncode:
        tail = logfile.read_text(errors="replace")[-10000:]
        raise ValueError(f"Compilation failed; see {logfile}\n{tail}")


def write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, entries[name], compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def classes_in(directory: Path) -> dict[str, bytes]:
    return {path.relative_to(directory).as_posix(): path.read_bytes() for path in sorted(directory.rglob("*.class"))}


def build(tools: dict) -> Path:
    work_root = ROOT / "build/manual"
    work_root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="bundle-", dir=work_root))
    java_classes, patch_classes, dex_output = (work / name for name in ("java-classes", "patch-classes", "dex"))
    for directory in (java_classes, patch_classes, dex_output):
        directory.mkdir()

    java_sources = sorted((ROOT / "extensions/extension/src/main/java").rglob("*.java"))
    kotlin_sources = sorted((ROOT / "patches/src/main/kotlin").rglob("*.kt"))
    if not java_sources or not kotlin_sources:
        raise ValueError("Extension and patch source files are required.")
    print(f"Compiling {len(java_sources)} Java extension sources.", file=sys.stderr)
    run([tools["javac"], "--release", "17", "-encoding", "UTF-8", "-classpath", tools["android"],
         "-d", java_classes, *java_sources], work / "javac.log")
    extension_jar = work / "extension-classes.jar"
    write_zip(extension_jar, classes_in(java_classes))
    run([tools["java"], "-Xmx2g", "-cp", tools["d8"], "com.android.tools.r8.D8", "--release",
         "--min-api", "28", "--lib", tools["android"], "--output", dex_output, extension_jar], work / "d8.log")
    dexes = list(dex_output.glob("*.dex"))
    if len(dexes) != 1 or dexes[0].name != "classes.dex":
        raise ValueError("Expected a single extension DEX; inspect the extension size before changing this gate.")

    print(f"Compiling {len(kotlin_sources)} Kotlin patch sources.", file=sys.stderr)
    compiler_cp = os.pathsep.join(str(path) for path in tools["compiler_jars"])
    target_cp = os.pathsep.join(str(tools[name]) for name in ("cli", "library"))
    run([tools["java"], "-Xmx2g", "-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
         "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-Xcontext-parameters",
         "-module-name", "cabessa_social", "-classpath", target_cp, "-d", patch_classes,
         *kotlin_sources], work / "kotlinc.log")

    entries = classes_in(patch_classes)
    entries["extensions/extension.mpe"] = dexes[0].read_bytes()
    if not entries["extensions/extension.mpe"].startswith(b"dex\n"):
        raise ValueError("The runtime extension must contain raw DEX bytes.")
    # Only the helper namespace is bundled. The library also contains unrelated
    # patches; including those would change the discovered patch set.
    with zipfile.ZipFile(tools["library"]) as library:
        for name in library.namelist():
            if name.startswith("app/morphe/util/") and name.endswith(".class"):
                entries[name] = library.read(name)
    with zipfile.ZipFile(tools["cli"]) as cli:
        properties = cli.read("app/morphe/patcher/version.properties").decode()
    patcher_version = next(line.partition("=")[2].strip() for line in properties.splitlines() if line.startswith("version="))
    metadata = {
        "Manifest-Version": "1.0", "Name": "Cabessa Social Patches",
        "Description": "Fixed social-only Instagram policy", "Version": "0.1.6",
        "Patcher-Version": patcher_version, "Author": "Cabessa Social contributors",
        "License": "GPLv3",
    }
    entries["META-INF/MANIFEST.MF"] = ("\r\n".join(f"{key}: {value}" for key, value in metadata.items()) + "\r\n\r\n").encode()
    resource_root = ROOT / "patches/src/main/resources"
    if resource_root.exists():
        for path in sorted(resource_root.rglob("*")):
            if path.is_file():
                name = path.relative_to(resource_root).as_posix()
                if name in entries:
                    raise ValueError(f"Duplicate bundle resource: {name}")
                entries[name] = path.read_bytes()
    entries["META-INF/LICENSE"] = (ROOT / "LICENSE").read_bytes()
    entries["META-INF/NOTICE"] = (ROOT / "NOTICE").read_bytes()
    for name, content in entries.items():
        if name.endswith(".class"):
            if len(content) < 8 or content[:4] != b"\xca\xfe\xba\xbe" or struct.unpack_from(">H", content, 6)[0] > 61:
                raise ValueError(f"Bundle class is not compatible with the Java 17 patch loader: {name}")
    staged = work / "cabessa-social-patches-0.1.6.mpp"
    write_zip(staged, entries)
    run([tools["java"], "-jar", tools["cli"], "list-patches", f"--patches={staged}"], work / "bundle-load.log")
    listing = (work / "bundle-load.log").read_text()
    # This smoke check verifies class loading, not applicability to the target APK.
    if "Content response filtering" not in listing or "Clone" not in listing:
        raise ValueError(f"The CLI did not discover the expected local patches; see {work / 'bundle-load.log'}")
    output_dir = ROOT / "patches/build/libs"
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / staged.name
    os.replace(staged, output)
    receipt = {
        "release_version": metadata["Version"],
        "build_route": "public-tools-manual-source-build",
        "bundle_sha256": sha256(output), "patcher_version": patcher_version,
        "morphe_cli_sha256": sha256(tools["cli"]), "patches_library_sha256": sha256(tools["library"]),
        "android_api_jar_sha256": sha256(tools["android"]), "d8_jar_sha256": sha256(tools["d8"]),
        "compiler_jars_sha256": {path.name: sha256(path) for path in tools["compiler_jars"]},
        "java_sources": len(java_sources), "kotlin_sources": len(kotlin_sources),
        "extension_dex_sha256": hashlib.sha256(entries["extensions/extension.mpe"]).hexdigest(),
        "jvm_target": 17, "extension_min_api": 28,
        "local_logs": str(work.relative_to(ROOT)),
    }
    (output_dir / "bundle-build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"Patch bundle compiled and loaded by the CLI. Logs: {work}", file=sys.stderr)
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    tools = resolve_tools()
    if args.check:
        print("Public compiler and patch tools match the pinned hashes.")
    else:
        print(build(tools))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"Bundle build failed: {error}", file=sys.stderr)
        sys.exit(1)
