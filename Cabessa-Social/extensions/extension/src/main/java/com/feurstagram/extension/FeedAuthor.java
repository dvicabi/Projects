package com.feurstagram.extension;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Recognize only the owner of a top-level timeline post, never tagged or carousel users. */
final class FeedAuthor {
    final String id, username;
    private FeedAuthor(String id, String username) { this.id = id; this.username = username; }

    static FeedAuthor fromItem(byte[] bytes) {
        try {
            Reader reader = new Reader(new String(bytes, StandardCharsets.UTF_8));
            Map<String, Object> root = object(reader.value(0));
            reader.ws();
            if (root == null || reader.p != reader.s.length()) return null;
            Map<String, Object> media = object(root.get("media_or_ad"));
            if (media == null) media = object(root.get("media"));
            // Direct media objects in a root items list require media markers.
            if (media == null && root.containsKey("taken_at") && (root.containsKey("pk") || root.containsKey("id"))) media = root;
            if (media == null) return null;
            Map<String, Object> user = object(media.get("user"));
            if (user == null) return null;
            String name = FeedMuteRules.name(string(user.get("username")));
            String id = FeedMuteRules.id(string(user.get("pk")));
            if (id == null) id = FeedMuteRules.id(string(user.get("pk_id")));
            if (id == null) id = FeedMuteRules.id(string(user.get("id")));
            return name == null ? null : new FeedAuthor(id, name);
        } catch (RuntimeException ignored) { return null; }
    }
    private static String string(Object v) { return v instanceof String ? (String) v : null; }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }
    static String key(byte[] bytes) {
        try { return new Reader(new String(bytes, StandardCharsets.UTF_8)).quoted(); }
        catch (RuntimeException ignored) { return null; }
    }

    /** Bounded JSON reader. Arrays are validated but not retained; numeric IDs keep exact digits. */
    private static final class Reader {
        final String s; int p, tokens;
        Reader(String s) { this.s = s; }
        void ws() { while (p < s.length() && " \r\n\t".indexOf(s.charAt(p)) >= 0) p++; }
        char take() { if (p >= s.length()) throw new IllegalArgumentException(); return s.charAt(p++); }
        boolean eat(char c) { ws(); if (p < s.length() && s.charAt(p) == c) { p++; return true; } return false; }
        void need(char c) { if (!eat(c)) throw new IllegalArgumentException(); }
        String quoted() {
            ws(); if (take() != '"') throw new IllegalArgumentException();
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = take(); if (c == '"') return b.toString();
                if (c < 32) throw new IllegalArgumentException();
                if (c == '\\') {
                    c = take();
                    switch (c) {
                        case '"': case '\\': case '/': break;
                        case 'b': c = '\b'; break; case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break;
                        case 'u':
                            int n = 0;
                            for (int i=0;i<4;i++) { int d=Character.digit(take(),16); if(d<0) throw new IllegalArgumentException(); n=n*16+d; }
                            c=(char)n; break;
                        default: throw new IllegalArgumentException();
                    }
                }
                b.append(c);
            }
        }
        Object value(int depth) {
            if (depth > 64 || ++tokens > 100000) throw new IllegalArgumentException();
            ws(); if (p >= s.length()) throw new IllegalArgumentException();
            char c = s.charAt(p);
            if (c == '"') return quoted();
            if (eat('{')) {
                Map<String,Object> result = new HashMap<>();
                if (eat('}')) return result;
                do { String key=quoted(); need(':'); if (result.containsKey(key)) throw new IllegalArgumentException(); result.put(key,value(depth+1)); } while(eat(','));
                need('}'); return result;
            }
            if (eat('[')) {
                if (!eat(']')) { do { value(depth+1); } while(eat(',')); need(']'); }
                return null;
            }
            int start = p;
            while (p < s.length() && " ,]}\r\n\t".indexOf(s.charAt(p)) < 0) p++;
            String token = s.substring(start,p);
            if ("true".equals(token)||"false".equals(token)||"null".equals(token)) return null;
            if (!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IllegalArgumentException();
            return token;
        }
    }
}
