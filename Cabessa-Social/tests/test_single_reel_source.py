"""Exercise CDN URL validation without an Android runtime.

These checks cover URL trust boundaries and preserving signed queries, not
Instagram's media models, actual network authorization or device playback.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'extensions/extension/src/main/java/com/feurstagram/extension/SingleReelSource.java'
HARNESS = r'''package com.feurstagram.extension;
public final class SingleReelSourceHarness {
    static void allowed(String value) {
        if (!value.equals(SingleReelSource.authorizedCdnUrl(value)))
            throw new AssertionError("Signed URL was rejected or changed");
    }
    static void blocked(String value) {
        if (SingleReelSource.authorizedCdnUrl(value) != null)
            throw new AssertionError("Untrusted URL accepted: " + value);
    }
    public static void main(String[] args) {
        allowed("https://scontent.cdninstagram.com/v/t66.30100-16/video.mp4?efg=ab%2Bcd&oh=00_Af&oe=123&_nc_gid=a");
        allowed("https://video-lhr8-1.xx.fbcdn.net/o1/v/test.mp4?token=hello%2Fworld&expires=123");
        allowed("https://VIDEO.FRANKFURT.FBCDN.NET:443/clip.mp4?sig=AbCd%3D%3D");
        blocked(null);
        blocked("");
        blocked("https://video.cdninstagram.com");
        blocked("http://video.cdninstagram.com/clip.mp4");
        blocked("file:///sdcard/private.mp4");
        blocked("content://com.instagram.android/media/123");
        blocked("javascript:alert(1)");
        blocked("//video.cdninstagram.com/clip.mp4");
        blocked("https://cdninstagram.com.attacker.example/clip.mp4");
        blocked("https://notfbcdn.net/clip.mp4");
        blocked("https://fbcdn.net.attacker.example/clip.mp4");
        blocked("https://video.cdninstagram.com@attacker.example/clip.mp4");
        blocked("https://user:secret@video.cdninstagram.com/clip.mp4");
        blocked("https://video.cdninstagram.com:8080/clip.mp4");
        blocked("https://video.cdninstagram.com/clip.mp4#anything");
        blocked("https://127.0.0.1/private.mp4");
        blocked("https://[::1]/private.mp4");
        blocked("https://video.cdninstagram.com/clip.mp4\r\nHeader: surprise");
        blocked("https://video.cdninstagram.com/" + "a".repeat(16384));
        System.out.println("Single Reel source: signed queries retained; non-CDN, credentials, schemes, ports and malformed URLs blocked.");
    }
}'''


class SingleReelSourceTests(unittest.TestCase):
    def test_selected_media_url_boundary(self):
        self.assertTrue(shutil.which('javac') and shutil.which('java'))
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / 'SingleReelSourceHarness.java'
            harness.write_text(HARNESS, encoding='utf-8')
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', directory, str(SOURCE), str(harness)], check=True)
            subprocess.run(['java', '-cp', directory, 'com.feurstagram.extension.SingleReelSourceHarness'], check=True)


if __name__ == '__main__':
    unittest.main()
