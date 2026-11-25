"""Regression tests for release rejection gates; no APK or Android device needed."""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from release_checks import (
    FORBIDDEN_CLASSES, OWN_KEY_PATCH, REQUIRED_PATCHES,
    check_badging, check_base_signers, check_dex, check_report, normalize_digest, signer_digests,
)


def good_report():
    return {"success": True, "appliedPatches": sorted(REQUIRED_PATCHES), "failedPatches": [],
            "stepResults": [{"step": "APPLY_PATCHES", "success": True}]}


GOOD_BADGING = "package: name='com.dvircab.social' versionCode='1'\napplication-label:'Cabessa Social'\n"


class ReleaseGateTests(unittest.TestCase):
    def test_complete_report_passes(self):
        self.assertEqual(set(check_report(good_report(), False)), REQUIRED_PATCHES)

    def test_incomplete_or_unknown_report_fails(self):
        for report in ({}, {"appliedPatches": []}, {"appliedPatches": [], "failedPatches": []}):
            with self.subTest(report=report), self.assertRaises(ValueError):
                check_report(report, False)

    def test_failed_patch_rejects_even_when_required_names_are_present(self):
        report = good_report()
        report["failedPatches"] = [{"name": "Other", "error": "Fingerprint no longer matches"}]
        with self.assertRaises(ValueError):
            check_report(report, False)

    def test_debug_or_updater_patch_rejected(self):
        for name in ("Debug bridge", "Install-packages permission", "Ghost mode", "Signature check bypass"):
            report = good_report()
            report["appliedPatches"].append(name)
            with self.subTest(name=name), self.assertRaises(ValueError):
                check_report(report, False)

    def test_signature_option_must_match_applied_patch(self):
        report = good_report()
        with self.assertRaises(ValueError):
            check_report(report, True)
        report["appliedPatches"].append(OWN_KEY_PATCH)
        with self.assertRaises(ValueError):
            check_report(report, False)
        self.assertIn(OWN_KEY_PATCH, check_report(report, True))

    def test_badging_rejects_wrong_identity_and_unsafe_flags(self):
        check_badging(GOOD_BADGING)
        rejected = (
            GOOD_BADGING.replace("com.dvircab.social", "com.instagram.android"),
            GOOD_BADGING.replace("Cabessa Social", "Instagram"),
            GOOD_BADGING + "application-debuggable\n",
            GOOD_BADGING + "uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'\n",
        )
        for text in rejected:
            with self.subTest(text=text), self.assertRaises(ValueError):
                check_badging(text)

    def test_removed_class_reference_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            for marker in (b"ordinary-class", *FORBIDDEN_CLASSES):
                with zipfile.ZipFile(apk, "w") as archive:
                    archive.writestr("AndroidManifest.xml", b"manifest-fixture")
                    archive.writestr("classes.dex", marker)
                if marker == b"ordinary-class":
                    check_dex(apk)
                else:
                    with self.subTest(marker=marker), self.assertRaises(ValueError):
                        check_dex(apk)

    def test_certificate_digest_requires_full_verified_tool_record(self):
        expected = "ab" * 32
        self.assertEqual(signer_digests("Signer #1 certificate SHA-256 digest: " + expected), {expected})
        for text in ("SHA-256: " + expected, "Signer #1 certificate SHA-256 digest: abcd", ""):
            with self.subTest(text=text), self.assertRaises(ValueError):
                signer_digests(text)
        self.assertEqual(normalize_digest(":".join(["AB"] * 32)), expected)

    def test_rotated_official_signers_exclude_source_stamp(self):
        first = "3a10c50c18ba937506c2875e9047be74f8fd8b86fae2ff9feaa50a81fcb4c014"
        second = "5f3e50f435583c9ae626302a71f7340044087a7e2c60adacfc254205a993e305"
        output = (f"Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {first}\n"
                  f"Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {second}\n"
                  f"Source Stamp Signer certificate SHA-256 digest: {'ab' * 32}\n")
        self.assertEqual(check_base_signers(output), {first, second})
        with self.assertRaises(ValueError):
            check_base_signers(output.replace(first, "cd" * 32))

    def test_failed_step_is_not_hidden_by_top_level_success(self):
        report = good_report()
        report["stepResults"].append({"step": "WRITE_APK", "success": False, "error": "failed"})
        with self.assertRaises(ValueError):
            check_report(report, False)


if __name__ == "__main__":
    unittest.main()
