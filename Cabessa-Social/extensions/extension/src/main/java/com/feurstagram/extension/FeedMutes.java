package com.feurstagram.extension;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only explicit exclusions are persisted. Recent feed authors live in memory. */
final class FeedMutes {
    private static final String PREFS = "cabessa_feed_exclusions_v1";
    private static final String KEY = "hidden_authors";
    private static final int MAX_RECENT = 200;
    private static final Map<String, String> recent = new LinkedHashMap<>(32, .75f, true);
    private static Set<String> rules;
    private FeedMutes() {}

    private static SharedPreferences prefs(Context supplied) {
        Context context = supplied != null ? supplied : Config.getAppContext();
        if (context == null) return null;
        Context app = context.getApplicationContext();
        return (app != null ? app : context).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static Set<String> load(Context context) {
        if (rules != null) return rules;
        SharedPreferences p = prefs(context);
        if (p == null) return Collections.emptySet(); // retry when app context exists
        rules = FeedMuteRules.clean(p.getStringSet(KEY, Collections.emptySet()));
        return rules;
    }
    private static boolean save(Context context, Set<String> next) {
        try {
            SharedPreferences p = prefs(context);
            if (p == null || !p.edit().putStringSet(KEY, new LinkedHashSet<>(next)).commit()) return false;
            rules = next;
            return true;
        } catch (RuntimeException e) { return false; }
    }
    static synchronized List<String> hidden(Context context) {
        try { return new ArrayList<>(load(context)); }
        catch (RuntimeException e) { return Collections.emptyList(); }
    }
    static synchronized List<String> recent(Context context) {
        List<String> result = new ArrayList<>();
        try {
            Set<String> hidden = load(context);
            for (String r : recent.values()) {
                if (FeedMuteRules.matching(hidden, FeedMuteRules.ruleId(r), FeedMuteRules.ruleName(r)) == null) result.add(r);
            }
        } catch (RuntimeException ignored) { return Collections.emptyList(); }
        Collections.reverse(result);
        return result;
    }
    static synchronized boolean hide(Context context, String rawId, String rawName) {
        String r = FeedMuteRules.rule(rawId, rawName);
        if (r == null) return false;
        try {
            String id = FeedMuteRules.ruleId(r), name = FeedMuteRules.ruleName(r);
            // Resolve an explicitly typed handle to a previously observed author, if unambiguous.
            if (id == null) {
                String candidate = null;
                boolean ambiguous = false;
                for (String seen : recent.values()) {
                    if (name.equals(FeedMuteRules.ruleName(seen)) && FeedMuteRules.ruleId(seen) != null) {
                        if (candidate != null && !candidate.equals(seen)) ambiguous = true;
                        candidate = seen;
                    }
                }
                if (!ambiguous && candidate != null) { r = candidate; id = FeedMuteRules.ruleId(candidate); }
            }
            Set<String> next = new LinkedHashSet<>(load(context));
            if (id == null) for (String existing : next) {
                if (name.equals(FeedMuteRules.ruleName(existing))) return true;
            }
            String matched = FeedMuteRules.matching(next, id, name);
            if (matched != null && FeedMuteRules.ruleId(matched) != null) return true;
            if (matched != null) next.remove(matched);
            if (next.size() >= FeedMuteRules.MAX_RULES) return false;
            next.add(r);
            return save(context, next);
        } catch (RuntimeException e) { return false; }
    }
    static synchronized boolean restore(Context context, String selection) {
        try {
            Set<String> next = new LinkedHashSet<>(load(context));
            String id = FeedMuteRules.ruleId(selection);
            String actual = id == null ? selection : FeedMuteRules.matching(next, id, FeedMuteRules.ruleName(selection));
            if (actual == null || !next.remove(actual)) return true;
            return save(context, next);
        } catch (RuntimeException e) { return false; }
    }
    static synchronized boolean observeAndHide(String rawId, String rawName) {
        String r = FeedMuteRules.rule(rawId, rawName);
        if (r == null) return false;
        String id = FeedMuteRules.ruleId(r), name = FeedMuteRules.ruleName(r);
        try {
            Set<String> current = load(null);
            String matched = FeedMuteRules.matching(current, id, name);
            if (matched != null) {
                // Upgrade a manually typed username to the stable ID once seen.
                // Also keep its displayed handle current if renamed.
                if (id != null && !r.equals(matched)) {
                    Set<String> next = new LinkedHashSet<>(current);
                    next.remove(matched); next.add(r); save(null, next);
                }
                return true;
            }
            recent.put(id == null ? "name:" + name : "id:" + id, r);
            while (recent.size() > MAX_RECENT) recent.remove(recent.keySet().iterator().next());
        } catch (RuntimeException ignored) { /* native feed remains usable */ }
        return false;
    }
}
