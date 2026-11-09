package me.abdiskiosk.minecraftaibuilder.common;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fuzzy matching of block names for "did you mean" suggestions and block search. */
public final class BlockNames {

    private BlockNames() {
    }

    /** Names that share words with {@code type} or are a few edits away, best first. */
    public static List<String> closest(String type, Collection<String> names, int max) {
        Set<String> words = words(type);
        int tolerance = Math.max(2, type.length() / 4);
        return names.stream()
                .filter(n -> overlap(words, words(n)) > 0 || distance(type, n) <= tolerance)
                .sorted(Comparator.<String>comparingLong(n -> -overlap(words, words(n)) * 1000L + distance(type, n)).thenComparing(Comparator.naturalOrder()))
                .limit(max)
                .toList();
    }

    /** Names containing every word of the query, alphabetically. */
    public static List<String> search(String query, Collection<String> names, int max) {
        Set<String> words = words(query);
        return names.stream().filter(n -> words.stream().allMatch(n::contains)).sorted().limit(max).toList();
    }

    /** Matches with {@code *} as the only wildcard. */
    public static boolean glob(String glob, String s) {
        String[] parts = glob.split("\\*", -1);
        int at = 0;
        for (int i = 0; i < parts.length; i++) {
            int found = s.indexOf(parts[i], at);
            if (found < 0 || (i == 0 && found != 0)) return false;
            at = found + parts[i].length();
        }
        return glob.endsWith("*") || at == s.length();
    }

    static Set<String> words(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[_\\s]+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    private static long overlap(Set<String> a, Set<String> b) {
        return a.stream().filter(b::contains).count();
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
