"""Exercise one-item network capabilities, independent of Android or a login."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
HARNESS = r'''
package com.feurstagram.extension;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
public class GateHarness {
    static final long NOW = 1_000_000_000L;
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static String request(String module, String ids) {
        return "https://i.instagram.com/api/v1/clips/items/?clips_media_ids=" + encode(ids)
                + "&container_module=" + module;
    }
    static void allowed(String url) {
        if (!SharedReelRequestGate.allows(URI.create(url), NOW + 1))
            throw new AssertionError("Selected item blocked");
    }
    static void denied(String url) {
        if (SharedReelRequestGate.allows(URI.create(url), NOW + 1))
            throw new AssertionError("Unselected item or ambiguous route was allowed");
    }
    public static void main(String[] args) {
        for (String invalid : new String[]{null, "", "abc", "12/34", "12?x=3", "12_", "12_3_4", "-12"}) {
            if (SharedReelRequestGate.authorize(invalid, NOW) != null)
                throw new AssertionError("Invalid media ID authorized");
        }
        String token = SharedReelRequestGate.authorize("123456789_42", NOW);
        String selected = request(token, "[\"123456789_42\"]");
        allowed(selected);
        allowed(selected + "&include_embedded=1");
        denied(request(token, "[\"999999999_42\"]"));
        denied(request(token, "[\"123456789_42\",\"999999999_42\"]"));
        denied(request(token, "[]"));
        denied(request(token, "[\"123456789_42\"] trailing"));
        denied(request("unknown_module", "[\"123456789_42\"]"));
        denied(selected.replace("https:", "http:"));
        denied(selected.replace("i.instagram.com", "instagram.com.evil.test"));
        denied(selected.replace("i.instagram.com", "user@i.instagram.com"));
        denied(selected.replace("i.instagram.com", "i.instagram.com:444"));
        denied(selected + "#fragment");
        denied(selected.replace("/clips/items/", "/clips/discover/stream/"));
        denied(selected.replace("/clips/items/", "/clips/items/next/"));
        denied(selected + "&max_id=other");
        denied(selected + "&cursor=other");
        denied(selected + "&clips_media_ids=" + encode("[\"999999999_42\"]"));
        denied(selected + "&%63ontainer_module=" + token);
        denied(selected.replace("container_module=", "container_module%3D"));
        denied("https://i.instagram.com/api/v1/clips/items/");
        if (!SharedReelRequestGate.sameMediaId("123456789", "123456789_42")
                || !SharedReelRequestGate.sameMediaId("123456789_42", "123456789")
                || SharedReelRequestGate.sameMediaId("123456789", "123456780")
                || SharedReelRequestGate.sameMediaId("123456789_42", "123456789_43")
                || SharedReelRequestGate.sameMediaId("123456789", "123456789_fake"))
            throw new AssertionError("Response identity comparison");
        String other = SharedReelRequestGate.authorize("777", NOW);
        SharedReelRequestGate.revoke(token);
        denied(selected);
        allowed(request(other, "[\"777\"]"));
        if (SharedReelRequestGate.allows(URI.create(request(other, "[\"777\"]")), NOW + 30_000_000_000L))
            throw new AssertionError("Expired capability was accepted");
        String oldest = SharedReelRequestGate.authorize("1", NOW);
        for (int n = 2; n <= 5; n++) SharedReelRequestGate.authorize(Integer.toString(n), NOW);
        denied(request(oldest, "[\"1\"]"));
        SharedReelRequestGate.revoke(null);
        System.out.println("One selected ID, exact origin/path/module, duplicate parameters, expiry, revocation, bounded pending grants: OK");
    }
}
'''


class SharedReelGateTests(unittest.TestCase):
    def test_request_capability_is_scoped_and_expires(self):
        java_home = Path(os.environ.get("JAVA_HOME", "/not-configured"))
        javac = str(java_home / "bin/javac") if (java_home / "bin/javac").exists() else shutil.which("javac")
        java = str(java_home / "bin/java") if (java_home / "bin/java").exists() else shutil.which("java")
        if not java or not javac:
            self.fail("JDK required for native policy tests")
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            for name in ("SharedReelRequestGate.java", "RequestPolicy.java"):
                shutil.copy(ROOT / "extensions/extension/src/main/java/com/feurstagram/extension" / name, target / name)
            (target / "GateHarness.java").write_text(HARNESS)
            subprocess.run([javac, "-d", directory, *map(str, target.glob("*.java"))], check=True)
            subprocess.run([java, "-cp", directory, "com.feurstagram.extension.GateHarness"], check=True)


if __name__ == "__main__":
    unittest.main()
