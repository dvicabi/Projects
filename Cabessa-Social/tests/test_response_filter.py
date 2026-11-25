#!/usr/bin/env python3
"""Exercise the shipped Java filter against Python's JSON parser; no SDK needed."""
import base64
import copy
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CONTENT = set("feed_items items items_with_ads tray reels reels_media medias media sections sectional_items users "
              "suggested_users suggestions suggested_accounts ranked_items results ads ad_items edges blend_items ad_media_items netego_media_items".split())
PAGING = set("more_available has_more has_next_page is_more_available more_available_ad_media".split())
CURSORS = set("next_max_id next_min_id max_id min_id next_cursor end_cursor next_page_token paging_token next_url".split())

HARNESS = r'''
package com.feurstagram.extension;
import java.io.*;
import java.net.URI;
import java.nio.*;
import java.util.*;
public class Harness {
    static class Request { URI uri; Request(String url) { uri = URI.create(url); } }
    static class Response { int status; Response(int value) { status = value; } }
    static class Media { String product; Media(String value) { product = value; } }
    static byte[] data(String text) throws Exception { return text.getBytes("UTF-8"); }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("lifecycle")) { lifecycle(); return; }
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(" ", 2);
            int size = Integer.parseInt(parts[0]);
            byte[] bytes = Base64.getDecoder().decode(parts[1]);
            JsonContentFilter filter = new JsonContentFilter();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int offset = 0; offset < bytes.length; offset += size) {
                int length = Math.min(size, bytes.length - offset);
                ByteBuffer chunk = ByteBuffer.allocateDirect(length + 4);
                chunk.position(2); chunk.put(bytes, offset, length); chunk.flip(); chunk.position(2);
                ByteBuffer source = chunk.asReadOnlyBuffer();
                int position = source.position(), limit = source.limit();
                ByteBuffer result = filter.filter(source);
                if (source.position() != position || source.limit() != limit) throw new AssertionError("source changed");
                while (result.hasRemaining()) out.write(result.get());
            }
            System.out.println(Base64.getEncoder().encodeToString(out.toByteArray()));
        }
    }
    static void lifecycle() throws Exception {
        String content = "{\"feed_items\":[{\"secret\":1}],\"more_available\":true,\"status\":\"ok\"}";
        for (int status : new int[]{200, 400, 401, 403, 429, 500}) {
            Object dispatcher = new Object();
            Request request = new Request("https://i.instagram.com/api/v1/feed/timeline/");
            ResponseFilter.onResponse(dispatcher, new Response(status), request);
            ByteBuffer body = ByteBuffer.wrap(data(content));
            ByteBuffer result = ResponseFilter.onBody(dispatcher, request, body);
            if (status == 200 ? !result.equals(body) : result != body) throw new AssertionError("native Following response changed " + status);
            ResponseFilter.onComplete(dispatcher);
            if (ResponseFilter.onBody(dispatcher, request, body) != body) throw new AssertionError("state leaked");
        }
        String[] allowed = {"https://i.instagram.com/api/v1/logging/client_event/",
            "https://i.instagram.com/api/v1/media/123/seen/", "https://i.instagram.com/api/v1/fbupload/",
            "https://i.instagram.com/api/v1/async_critical_notices/", "https://i.instagram.com/api/v1/commerce/",
            "https://example.com/api/v1/feed/timeline/", "https://instagram.com.evil.test/api/v1/feed/timeline/"};
        for (String url : allowed) if (Block.hidesResponse(URI.create(url))) throw new AssertionError(url);
        if (Block.hidesResponse(URI.create("https://i.instagram.com/api/v1/feed/timeline/")))
            throw new AssertionError("Following batch must retain its posts");
        if (Block.hidesResponse(URI.create("https://i.instagram.com/api/v1/feed/timeline/?max_id=older")))
            throw new AssertionError("Following continuation must stay native");
        if (Block.hidesResponse(URI.create("https://i.instagram.com/api/v1/feed/timeline/?%6dax_id=older")))
            throw new AssertionError("escaped continuation key must stay native");
        if (Block.hidesResponse(URI.create("https://i.instagram.com/api/v1/feed/timeline/?max_id=")))
            throw new AssertionError("empty cursor is not a continuation");
        String[] blockedRequests = {"https://i.instagram.com/api/v1/clips/items/?clips_media_ids=1",
            "https://i.instagram.com/api/v1/clips/home/", "https://i.instagram.com/api/v1/clips/discover/stream/",
            "https://www.instagram.com/reel/Abc/", "https://graph.instagram.com/pigeon_nest",
            "https://graph.facebook.com/logging_client_events", "https://connect.facebook.net/en_US/fbevents.js"};
        for (String url : blockedRequests) {
            try { Block.onRequest(URI.create(url)); throw new AssertionError("request allowed " + url); }
            catch (IOException expected) { }
        }
        String selectedModule = SharedReelRequestGate.authorize("123_456");
        URI selectedUri = URI.create("https://i.instagram.com/api/v1/clips/items/?clips_media_ids=%5B%22123_456%22%5D&container_module=" + selectedModule);
        Block.onRequest(selectedUri);
        if (Block.hidesResponse(selectedUri)) throw new AssertionError("selected Reel response hidden");
        Object selectedDispatcher = new Object();
        Request selectedRequest = new Request(selectedUri.toString());
        ResponseFilter.onResponse(selectedDispatcher, new Response(200), selectedRequest);
        ByteBuffer selectedBody = ByteBuffer.wrap(data("{\"items\":[{\"media\":{\"id\":\"123_456\"}}],\"status\":\"ok\"}"));
        if (ResponseFilter.onBody(selectedDispatcher, selectedRequest, selectedBody) != selectedBody)
            throw new AssertionError("selected Reel payload modified");
        ResponseFilter.onComplete(selectedDispatcher);
        SharedReelRequestGate.revoke(selectedModule);
        if (!Block.hidesResponse(selectedUri)) throw new AssertionError("selected capability leaked after revoke");
        try { Block.onRequest(selectedUri); throw new AssertionError("revoked Reel request allowed"); }
        catch (IOException expected) { }
        String[] allowedRequests = {"https://i.instagram.com/api/v1/feed/reels_tray/",
            "https://i.instagram.com/api/v1/feed/reels_media/", "https://i.instagram.com/api/v1/media/seen/?reel=1",
            "https://i.instagram.com/api/v1/media/123/comments/", "https://i.instagram.com/api/v1/media/123/like/",
            "https://i.instagram.com/api/v1/accounts/login/", "https://i.instagram.com/api/v1/challenge/",
            "https://i.instagram.com/api/v1/direct_v2/threads/1/", "https://i.instagram.com/rupload_igvideo/1",
            "https://i.instagram.com/api/v1/media/configure/", "https://i.instagram.com/api/v1/users/search/",
            "https://i.instagram.com/api/v1/discover/surface_with_su/",
            "https://example.com/pigeon_nest", "https://instagram.com.evil.test/api/v1/clips/items/",
            "https://i.instagram.com/api/v1/clips/items_extra/", "https://i.instagram.com/api/v1/not/clips/items/"};
        for (String url : allowedRequests) {
            URI uri = URI.create(url);
            Block.onRequest(uri);
            if (Block.hidesResponse(uri)) throw new AssertionError("allowed response removed " + url);
        }
        for (String url : new String[]{"https://i.instagram.com/api/v1/feed/timeline/",
                "https://i.instagram.com/api/v1/feed/timeline/?max_id=older",
                "https://i.instagram.com/api/v1/feed/timeline/?%6dax_id=older"}) {
            Object page = new Object();
            Request pageRequest = new Request(url);
            ResponseFilter.onResponse(page, new Response(200), pageRequest);
            String json = "{\"feed_items\":[{\"media_or_ad\":{\"taken_at\":123}}],\"more_available\":true,\"next_max_id\":\"next\",\"paging_info\":{\"has_more\":true,\"max_id\":\"opaque\"}}";
            ByteBuffer bytes = ByteBuffer.wrap(data(json));
            if (!ResponseFilter.onBody(page, pageRequest, bytes).equals(bytes))
                throw new AssertionError("Following cursor or data modified " + url);
            ResponseFilter.onComplete(page);
        }
        FeedMutes.enabled = true;
        String filteredInput = "{\"feed_items\":[{\"media_or_ad\":{\"user\":{\"pk\":123,\"username\":\"old\"}}}],\"next_max_id\":\"keep\"}";
        for (String url : new String[]{"https://i.instagram.com/api/v1/feed/timeline/", "https://i.instagram.com/api/v1/feed/timeline/?max_id=next", "https://i.instagram.com/api/v1/feed/user/123/", "https://i.instagram.com/api/v1/feed/reels_tray/", "https://i.instagram.com/api/v1/direct_v2/threads/1/", "https://i.instagram.com/api/v1/accounts/login/", "https://example.com/api/v1/feed/timeline/"}) {
            Object d = new Object(); Request r = new Request(url);
            ResponseFilter.onResponse(d, new Response(200), r);
            ByteBuffer b = ByteBuffer.wrap(data(filteredInput)); ByteBuffer filtered = ResponseFilter.onBody(d,r,b);
            byte[] output = new byte[filtered.remaining()]; filtered.get(output);
            String expected = RequestPolicy.isTimeline(r.uri) ? "{\"feed_items\":[],\"next_max_id\":\"keep\"}" : filteredInput;
            if (!expected.equals(new String(output,"UTF-8"))) throw new AssertionError("mute scope "+url);
            ResponseFilter.onComplete(d);
            if (ResponseFilter.onBody(d,r,b) != b) throw new AssertionError("mute state leaked");
        }
        FeedMutes.enabled = false;
        Object ads = new Object();
        Request adRequest = new Request("https://i.instagram.com/api/v1/ads/graphql/");
        ResponseFilter.onResponse(ads, new Response(200), adRequest);
        ByteBuffer adBody = ByteBuffer.wrap(data("{\"data\":{\"ads\":[1]},\"errors\":[{\"message\":\"kept\"}]}"));
        ByteBuffer adResult = ResponseFilter.onBody(ads, adRequest, adBody);
        byte[] adBytes = new byte[adResult.remaining()]; adResult.get(adBytes);
        if (!new String(adBytes, "UTF-8").equals("{\"data\":{},\"errors\":[{\"message\":\"kept\"}]}"))
            throw new AssertionError("GraphQL ad data/errors");
        ResponseFilter.onComplete(ads);
        Map<String,String> ranked = Collections.singletonMap("pagination_source", "feed_recs");
        if (!"following".equals(LimitFeed.setFollowingHeader(ranked).get("pagination_source")))
            throw new AssertionError("following header");
        if (!"feed_recs".equals(ranked.get("pagination_source"))) throw new AssertionError("original headers mutated");
        if (!"following".equals(LimitFeed.setFollowingHeader(null).get("pagination_source")))
            throw new AssertionError("missing headers must still select Following");
        Map<String,String> other = Collections.singletonMap("pagination_source", "ranked_next_page");
        if (!"following".equals(LimitFeed.setFollowingHeader(other).get("pagination_source")))
            throw new AssertionError("alternate source restored ranked feed");
        Block.replaceFeedItemType("media_or_ad");
        Media reel = new Media("clips");
        if (!Block.isReelMedia(reel) || Block.filterFeedMedia(reel) != null)
            throw new AssertionError("organic Reel was not filtered");
        for (String product : new String[]{"feed", "story", "direct", "carousel_container", "unknown", null}) {
            Media ordinary = new Media(product);
            if (Block.isReelMedia(ordinary) || Block.filterFeedMedia(ordinary) != ordinary)
                throw new AssertionError("ordinary/unknown media blocked " + product);
        }
        if (Block.isReelMedia(null) || Block.isReelMedia(new Object())) throw new AssertionError("non-media classification");
        Object a = new Object(), b = new Object();
        Request req = new Request("https://i.instagram.com/api/v1/clips/discover/stream/");
        ResponseFilter.onResponse(a, new Response(200), req);
        ResponseFilter.onResponse(b, new Response(403), req);
        ByteBuffer first = ByteBuffer.wrap(data("{\"items\":[{\"x\":"));
        ResponseFilter.onBody(a, req, first);
        if (ResponseFilter.onBody(b, req, first) != first) throw new AssertionError("cross-request state");
        ResponseFilter.onError(a);
        if (ResponseFilter.onBody(a, req, first) != first) throw new AssertionError("error state leaked");
        java.lang.reflect.Field field = ResponseFilter.class.getDeclaredField("RESPONSES");
        field.setAccessible(true);
        ResponseFilter.onComplete(b);
        if (!((Map<?,?>) field.get(null)).isEmpty()) throw new AssertionError("active states remain");
        System.out.println("HTTP errors, fixed feed, scoped paths, Reels, telemetry, concurrency and cleanup: OK");
    }
}
'''


class ResponseFilterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.build = Path(cls.tmp.name)
        src = ROOT / "extensions/extension/src/main/java/com/feurstagram/extension"
        for name in ("JsonContentFilter.java", "Block.java", "ResponseFilter.java", "LimitFeed.java", "RequestPolicy.java", "SharedReelRequestGate.java", "FollowingMuteFilter.java", "FeedAuthor.java", "FeedMuteRules.java"):
            text = (src / name).read_text()
            # Stand in for the two direct field-read bridges resolved by Morphe.
            text = text.replace("static URI requestUri(Object request) { return null; }",
                                "static URI requestUri(Object request) { return ((Harness.Request)request).uri; }")
            text = text.replace("static int responseStatus(Object response) { return 0; }",
                                "static int responseStatus(Object response) { return ((Harness.Response)response).status; }")
            text = text.replace("static String mediaProductType(Object media) { return null; }",
                                "static String mediaProductType(Object media) { return media instanceof Harness.Media ? ((Harness.Media)media).product : null; }")
            (cls.build / name).write_text(text)
        (cls.build / "Harness.java").write_text(HARNESS)
        (cls.build / "FeedMutes.java").write_text("package com.feurstagram.extension; class FeedMutes {static boolean enabled;static boolean observeAndHide(String id,String name){return enabled && \"123\".equals(id);}}")
        (cls.build / "Config.java").write_text("package com.feurstagram.extension; class Config {"
            + "".join(f"static boolean is{x}Blocked() {{ return false; }}" for x in ("Feed", "Stories", "Comments"))
            + "".join(f"static boolean is{x}Blocked() {{ return true; }}" for x in
                      ("Explore", "Reels", "Suggested", "Ads", "Telemetry")) + "}")
        home = Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"))
        cls.java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        if not cls.java or not javac:
            raise RuntimeError("A JDK with both java and javac is required to run these tests")
        subprocess.run([javac, "-d", str(cls.build), *map(str, cls.build.glob("*.java"))], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def filter_many(self, inputs):
        encoded = "".join(f"{size} {base64.b64encode(raw).decode()}\n" for size, raw in inputs)
        command = [self.java, "-cp", str(self.build), "com.feurstagram.extension.Harness"]
        result = subprocess.run(command,
                                input=encoded, text=True, capture_output=True, check=True)
        return [base64.b64decode(line) for line in result.stdout.splitlines()]

    def test_chunk_boundaries_and_json_oracle(self):
        rng = random.Random(455)
        cases = [
            {"feed_items": [{"caption": 'é😃 \\" } ]', "items": [1, 2]}], "status": "ok",
             "more_available": True, "paging_info": {"more_available": True, "max_id": "123"}},
            {"tray": [], "items": None, "reels": {"123": {"items": [1]}}, "num_results": 12},
            {"challenge": {"url": "/challenge/", "items": [1], "more_available": True},
             "status": "fail", "message": "checkpoint_required", "error_type": "checkpoint"},
        ]
        for _ in range(200):
            case = {"metadata": {"items": [rng.randrange(100)], "more_available": True},
                    "status": "ok", "unicode": "été 🦆", "text": 'a\\b\"[}:'}
            for key in rng.sample(sorted(CONTENT), 5):
                case[key] = rng.choice([[{"a": [1, {"x": '\\"}'}]}], {"a": [1, 2]}, None, "unchanged", 5])
            case[rng.choice(sorted(PAGING))] = True
            cases.append(case)
        inputs, expected = [], []
        for case in cases:
            want = copy.deepcopy(case)
            for key, value in case.items():
                if key in CONTENT and isinstance(value, (list, dict)): want[key] = type(value)()
                if key in PAGING and isinstance(value, bool): want[key] = False
                if key in CURSORS and key != "next_url": want[key] = None
                if key in ("paging_info", "paging", "page_info") and isinstance(value, dict):
                    for flag in PAGING:
                        if isinstance(value.get(flag), bool): want[key][flag] = False
                    for cursor in CURSORS:
                        if cursor in value: want[key][cursor] = None
            for size in (1, 2, 3, 7, 31, 4096):
                inputs.append((size, json.dumps(case, ensure_ascii=False).encode()))
                expected.append(want)
        actual = self.filter_many(inputs)
        self.assertEqual(len(actual), len(expected))
        for result, want in zip(actual, expected): self.assertEqual(json.loads(result), want)

    def test_escaped_keys_and_unchanged_unknown_fields(self):
        raw = b'{"it\\u0065ms":[{"nested":["\\\"}"]}],"safe":{"items":[1]},"status":"ok"}'
        self.assertEqual(json.loads(self.filter_many([(1, raw)])[0]),
                         {"items": [], "safe": {"items": [1]}, "status": "ok"})
        payloads = [b' \n{"status":"fail","message":"login_required","challenge":{"url":"/c/"}}',
                    b'{"data":{"items":[1]},"extensions":{"trace":"abc"}}', b'\x1f\x8b\x08binary', b'[1,2]']
        self.assertEqual(self.filter_many([(1, raw) for raw in payloads]), payloads)

    def test_streamed_documents(self):
        raw = (b'{"items":[1],"items_with_ads":[{"media":{"id":"x"}}],"status":"ok"}\n'
               b'{"items":[2],"paging_info":{"more_available":true},"status":"ok"}')
        for result in self.filter_many([(n, raw) for n in (1, 2, 17, 4096)]):
            first, second = map(json.loads, result.splitlines())
            self.assertEqual(first, {"items": [], "items_with_ads": [], "status": "ok"})
            self.assertEqual(second, {"items": [], "paging_info": {"more_available": False}, "status": "ok"})

    def test_response_lifecycle(self):
        subprocess.run([self.java, "-cp", str(self.build), "com.feurstagram.extension.Harness", "lifecycle"], check=True)

    def test_cursor_value_types_and_escaped_keys(self):
        inputs, expected = [], []
        for cursor in CURSORS:
            for value in (None, True, False, 0, 23.5, "", "a\\b\"[},", [1, {"k": 2}], {"a": [1]}):
                case = {cursor: value, "status": "ok", "items": [{"text": "retained"}]}
                want = {cursor: value if cursor == "next_url" else None,
                        "status": "ok", "items": []}
                for size in (1, 2, 5, 4096):
                    inputs.append((size, json.dumps(case).encode()))
                    expected.append(want)
        for result, want in zip(self.filter_many(inputs), expected):
            self.assertEqual(json.loads(result), want)
        raw = b'{"next_\\u006dax_id":"next","items":[1],"more_available":true}'
        self.assertEqual(json.loads(self.filter_many([(1, raw)])[0]),
                         {"next_max_id": None, "items": [], "more_available": False})


if __name__ == "__main__":
    unittest.main()
