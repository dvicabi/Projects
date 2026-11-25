"""Following-mode invariants when the native timeline merges parameter maps.

Real APK hook applicability is checked by the release build. This test executes
the Java policy with conflicting map sources, immutable input and opaque cursors.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "extensions/extension/src/main/java/com/feurstagram/extension/LimitFeed.java"

HARNESS = r'''
import java.util.*;
import com.feurstagram.extension.LimitFeed;
public class FollowingPolicyHarness {
    static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
    static void append(Map<String,String> outgoing, Map<String,String> source) {
        for (Map.Entry<String,String> entry : source.entrySet()) {
            outgoing.put(entry.getKey(), LimitFeed.followingParameter(entry.getKey(), entry.getValue()));
        }
    }
    public static void main(String[] args) {
        String cursor = "AQDw/AbC==&cursor:2026_987_654";
        Map<String,String> input = new LinkedHashMap<>();
        input.put("pagination_source", "feed_recs");
        input.put("max_id", cursor);
        input.put("reason", "pagination");
        input.put("optional", null);
        Map<String,String> immutable = Collections.unmodifiableMap(input);
        Map<String,String> request = LimitFeed.setFollowingHeader(immutable);
        check("feed_recs".equals(immutable.get("pagination_source")), "caller input was mutated");
        check(cursor.equals(request.get("max_id")), "constructor changed the opaque cursor");
        check("following".equals(request.get("pagination_source")), "initial mode not Following");

        for (String mode : new String[]{"feed_recs", "favorites", "homecoming_all", "ranked_next_page", "", null}) {
            Map<String,String> outgoing = new LinkedHashMap<>();
            append(outgoing, request);
            Map<String,String> later = new LinkedHashMap<>();
            later.put("pagination_source", mode);
            later.put("max_id", cursor);
            later.put("reason", "pagination");
            append(outgoing, later);
            check("following".equals(outgoing.get("pagination_source")), "later source changed feed mode");
            check(cursor.equals(outgoing.get("max_id")), "later source changed the cursor");
            check("pagination".equals(outgoing.get("reason")), "pagination reason was changed");
            check(outgoing.containsKey("optional") && outgoing.get("optional") == null, "null optional field lost");
        }
        check("following".equals(LimitFeed.setFollowingHeader(null).get("pagination_source")), "null map not forced");
        check("value".equals(LimitFeed.followingParameter(null, "value")), "unrelated null key changed");
        check(LimitFeed.followingParameter("max_id", null) == null, "missing cursor invented");
        System.out.println("Following: later map overrides denied; immutable input and native cursors preserved.");
    }
}
'''


class FollowingPolicyTests(unittest.TestCase):
    def test_later_request_maps_cannot_restore_ranked_feed(self):
        self.assertTrue(shutil.which("javac") and shutil.which("java"))
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            harness = folder / "FollowingPolicyHarness.java"
            harness.write_text(HARNESS, encoding="utf-8")
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory, str(SOURCE), str(harness)], check=True)
            subprocess.run(["java", "-cp", directory, "FollowingPolicyHarness"], check=True)


if __name__ == "__main__":
    unittest.main()
