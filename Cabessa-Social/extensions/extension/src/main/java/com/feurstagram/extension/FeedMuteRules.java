package com.feurstagram.extension;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Local feed-only selections. Prefer a stable numeric author ID when available. */
final class FeedMuteRules {
    static final int MAX_RULES = 1000;
    private FeedMuteRules() {}

    static String name(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("@")) value = value.substring(1);
        value = value.toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9._]{1,30}") ? value : null;
    }
    static String id(String raw) {
        return raw != null && raw.matches("[0-9]{1,30}") ? raw : null;
    }
    static String rule(String rawId, String rawName) {
        String name = name(rawName), id = id(rawId);
        return name == null ? null : (id == null ? "" : id) + "|" + name;
    }
    static String ruleId(String rule) {
        int split = rule == null ? -1 : rule.indexOf('|');
        return split < 0 ? null : id(rule.substring(0, split));
    }
    static String ruleName(String rule) {
        int split = rule == null ? -1 : rule.indexOf('|');
        return split < 0 ? null : name(rule.substring(split + 1));
    }
    static boolean matches(String rule, String rawId, String rawName) {
        String rid = ruleId(rule), authorId = id(rawId), rn = ruleName(rule), author = name(rawName);
        if (rn == null) return false;
        // A known ID is authoritative, even if the handle has changed or been reused.
        if (rid != null) return authorId != null && rid.equals(authorId);
        return author != null && rn.equals(author);
    }
    static String matching(Collection<String> rules, String authorId, String author) {
        for (String rule : rules) if (matches(rule, authorId, author)) return rule;
        return null;
    }
    static Set<String> clean(Collection<String> rules) {
        Set<String> result = new LinkedHashSet<>();
        if (rules != null) for (String r : rules) {
            String canonical = rule(ruleId(r), ruleName(r));
            if (canonical != null && result.size() < MAX_RULES) result.add(canonical);
        }
        return result;
    }
}
