#!/usr/bin/env python3
"""Fail-closed checks for Cabessa Social builds; standard library only.

These checks validate build artifacts, not Instagram behavior or telemetry coverage.
No private key or password is read into a receipt.
"""
from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "com.dvircab.social"
LABEL = "Cabessa Social"
OWN_KEY_PATCH = "Own signing-key compatibility"
REQUIRED_PATCHES = {
    "Clone", "Content response filtering", "Feed item filtering",
    "Limit feed to following profiles", "Quoicouview mode", "Restart relay",
    "Notes tray", "Settings entry point", "Grayscale", "Preserve post caption",
    "Single shared Reel player",
}
FORBIDDEN_PATCHES = {"Debug bridge", "Install-packages permission", "Ghost mode", "Signature check bypass"}
FORBIDDEN_CLASSES = tuple(
    ("Lcom/feurstagram/extension/" + name + ";").encode()
    for name in ("DebugBridge", "UpdateChecker", "Ghost", "Timer", "TimerPill", "TimerSettings")
)


def fail(message: str) -> None:
    raise ValueError(message)


def sha256(path: Path | str) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_digest(value: str) -> str:
    normalized = value.strip().replace(":", "").lower()
    if re.fullmatch(r"[0-9a-f]{64}", normalized) is None:
        fail("Expected a complete SHA-256 digest.")
    return normalized


def same_digest(actual: str, expected: str) -> None:
    if normalize_digest(actual) != normalize_digest(expected):
        fail("SHA-256 does not match the expected value.")


def signer_digests(text: str) -> set[str]:
    # SDK 35 prints signer ranges for v3.1 key rotation, and #N for the usual
    # non-rotating signature. Neither Source Stamp certificates nor public-key
    # hashes are the APK signing certificate.
    digests = re.findall(
        r"^Signer(?: #\d+| \(minSdkVersion=\d+, maxSdkVersion=\d+\)) certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$",
        text, re.M,
    )
    if not digests:
        fail("No APK signer certificate was reported by apksigner.")
    return {normalize_digest(value) for value in digests}


def trusted_base_signers() -> set[str]:
    record = json.loads(Path(__file__).with_name("trusted_instagram_signers.json").read_text())
    if record.get("package_name") != "com.instagram.android":
        fail("The trusted input signer record has an unexpected package.")
    values = record.get("sha256_cert_fingerprints")
    if not isinstance(values, list) or not values:
        fail("The trusted input signer list is empty or invalid.")
    return {normalize_digest(value) for value in values}


def check_base_signers(text: str) -> set[str]:
    actual = signer_digests(text)
    if not actual.issubset(trusted_base_signers()):
        fail("An input APK signer does not match the reviewed official Instagram certificates.")
    return actual


def check_report(report: dict, allow_own_signature: bool) -> list[str]:
    if not isinstance(report, dict):
        fail("Patch report must be an object.")
    applied = report.get("appliedPatches")
    # Morphe 2.3.0 serializes Result.appliedPatches as List<String>; empty
    # default collections may be omitted by Kotlin serialization.
    failed = report.get("failedPatches", [])
    if not isinstance(applied, list) or not isinstance(failed, list):
        fail("Unsupported patch report schema; Morphe Result patch lists are required.")
    if (failed or report.get("success") is not True or report.get("error") or report.get("exception")
            or report.get("failureReason") or report.get("failureDetail")):
        fail("The patch report contains a failure.")
    steps = report.get("stepResults")
    if not isinstance(steps, list) or not steps:
        fail("The patch report has no step results.")
    for step in steps:
        if not isinstance(step, dict) or step.get("success") is not True or step.get("error"):
            fail("A patching step did not succeed.")
    names = set()
    for patch in applied:
        if not isinstance(patch, str) or not patch:
            fail("Invalid applied patch record.")
        names.add(patch)
    missing = REQUIRED_PATCHES - names
    if missing:
        fail("Required patches are absent: " + ", ".join(sorted(missing)))
    prohibited = FORBIDDEN_PATCHES & names
    if prohibited:
        fail("Prohibited patches were applied: " + ", ".join(sorted(prohibited)))
    if (OWN_KEY_PATCH in names) != allow_own_signature:
        fail("Signing-key compatibility does not match the explicit build option.")
    return sorted(names)


def check_badging(text: str) -> None:
    package = re.search(r"^package: name='([^']+)'", text, re.M)
    if not package or package[1] != PACKAGE:
        fail("Unexpected application package.")
    label = re.search(r"^application-label:'([^']*)'", text, re.M)
    if not label or label[1] != LABEL:
        fail("Unexpected application label.")
    if re.search(r"^application-(debuggable|testOnly)\b", text, re.M):
        fail("Debuggable or test-only APKs cannot be released.")
    if "android.permission.REQUEST_INSTALL_PACKAGES" in text:
        fail("The external installer permission is still present.")


def check_dex(apk: Path) -> None:
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        dex_names = [name for name in names if re.fullmatch(r"classes(?:\d+)?\.dex", name)]
        if "AndroidManifest.xml" not in names or not dex_names:
            fail("The output is not a complete APK.")
        for name in dex_names:
            body = archive.read(name)
            for descriptor in FORBIDDEN_CLASSES:
                if descriptor in body:
                    fail("Removed extension code remains in the APK: " + descriptor.decode())


def verify_bundle(bundle: Path, apksigner: str, output: Path) -> None:
    records = []
    common_signers = None
    with zipfile.ZipFile(bundle) as archive, tempfile.TemporaryDirectory(prefix="cabessa-input-") as temp:
        apks = [item for item in archive.infolist() if item.filename.lower().endswith(".apk") and not item.is_dir()]
        if not apks or len(apks) > 1000 or sum(item.file_size for item in apks) > 4 * 1024**3:
            fail("Input bundle has an unexpected APK inventory or expanded size.")
        for number, item in enumerate(apks):
            # Never use archive paths as filesystem paths.
            target = Path(temp) / f"input-{number}.apk"
            with archive.open(item) as source, target.open("wb") as destination:
                shutil.copyfileobj(source, destination)
            result = subprocess.run([apksigner, "verify", "--verbose", "--print-certs", str(target)],
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
            signers = check_base_signers(result.stdout)
            if common_signers is None:
                common_signers = signers
            elif signers != common_signers:
                fail("Input bundle APKs have different signing certificates.")
            records.append({"archive_entry": item.filename, "sha256": sha256(target), "signer_sha256": sorted(signers)})
    output.write_text(json.dumps({"verified_apks": records}, indent=2) + "\n")


def source_files(root: Path) -> dict[str, str]:
    candidates = []
    for directory in ("patches/src", "extensions/extension/src", "scripts"):
        candidates.extend(path for path in (root / directory).rglob("*") if path.is_file() and "__pycache__" not in path.parts)
    for name in ("build.sh", "settings.gradle.kts", "patches/build.gradle.kts", "extensions/extension/build.gradle.kts",
                 "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"):
        path = root / name
        if path.is_file():
            candidates.append(path)
    return {str(path.relative_to(root)): sha256(path) for path in sorted(set(candidates))}


def receipt(args: list[str]) -> None:
    root, work, input_path, input_sha, base_sha, signer_sha, cli, bundle, own_key, editor = args
    root, work = Path(root), Path(work)
    same_digest(sha256(input_path), input_sha)
    current_source = source_files(root)
    source_record = work / "source-before-build.json"
    if not source_record.is_file() or json.loads(source_record.read_text()) != current_source:
        fail("Source files changed during the build, or the source snapshot is missing.")
    patch_report = json.loads((work / "patch-report.json").read_text())
    patches = check_report(patch_report, own_key == "1")
    result = {
        "schema_version": 1,
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "package": PACKAGE,
        "application_label": LABEL,
        "input_file": Path(input_path).name,
        "input_sha256": normalize_digest(input_sha),
        "input_signer_trust_source": json.loads(Path(__file__).with_name("trusted_instagram_signers.json").read_text()),
        "patched_base_sha256": normalize_digest(base_sha),
        "apk_sha256": sha256(work / "cabessa-social.apk"),
        "signer_certificate_sha256": normalize_digest(signer_sha),
        "own_signing_key_compatibility": own_key == "1",
        "morphe_cli_sha256": sha256(cli),
        "patch_bundle_sha256": sha256(bundle),
        "bundle_build": json.loads((Path(bundle).parent / "bundle-build-receipt.json").read_text()),
        "patch_report_sha256": sha256(work / "patch-report.json"),
        "applied_patches": patches,
        "source_files_sha256": current_source,
        "validation": {
            "apk_signature": "passed",
            "expected_signer": "passed",
            "package_label_permissions_debug_status": "passed",
            "removed_extension_classes": "passed",
            "required_patches": "passed",
            "on_device_acceptance": "not_run_by_build_script",
            "traffic_privacy_validation": "not_run_by_build_script",
        },
    }
    if editor and Path(input_path).suffix in {".apkm", ".xapk", ".apks"}:
        result["apkeditor_sha256"] = sha256(editor)
        result["input_apk_signatures"] = json.loads((work / "bundle-verification.json").read_text())
    else:
        result["input_signer_sha256"] = sorted(signer_digests((work / "base-signature.txt").read_text()))
    (work / "build-receipt.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    public_files = ("cabessa-social.apk", "patch-report.json", "release-signature.txt", "release-certificate.der", "build-receipt.json")
    (work / "SHA256SUMS.txt").write_text("".join(f"{sha256(work / name)}  {name}\n" for name in public_files))


def main(args: list[str]) -> None:
    command, *values = args
    if command == "sha256":
        print(sha256(values[0]))
    elif command == "check-hash":
        same_digest(*values)
    elif command == "check-keystore":
        key, root = (Path(value).resolve() for value in values)
        if key == root or root in key.parents:
            fail("Release keystores must be stored outside the source repository.")
    elif command == "verify-bundle":
        verify_bundle(Path(values[0]), values[1], Path(values[2]))
    elif command == "check-base-signer":
        check_base_signers(Path(values[0]).read_text())
    elif command == "find-bundle":
        bundles = [path for path in Path(values[0]).glob("*.mpp") if not re.search(r"-(sources|javadoc)\.mpp$", path.name)]
        if len(bundles) != 1:
            fail("Expected exactly one built .mpp patch bundle; remove stale build outputs and rebuild.")
        print(bundles[0].resolve())
    elif command == "check-report":
        patches = check_report(json.loads(Path(values[0]).read_text()), values[1] == "1")
        print(f"Patch report verified: {len(patches)} patches.")
    elif command == "verify-release":
        apk, signature, badging, signer = values
        if signer_digests(Path(signature).read_text()) != {normalize_digest(signer)}:
            fail("The signed APK does not match the selected release certificate.")
        check_badging(Path(badging).read_text())
        check_dex(Path(apk))
        print("APK package, label, release flags, permissions, extension classes and signer verified.")
    elif command == "source-snapshot":
        Path(values[1]).write_text(json.dumps(source_files(Path(values[0])), indent=2) + "\n")
    elif command == "receipt":
        receipt(values)
    else:
        fail("Unknown release validation command.")


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except (ValueError, OSError, KeyError, IndexError, json.JSONDecodeError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Release validation failed: {error}", file=sys.stderr)
        sys.exit(1)
