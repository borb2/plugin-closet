package me.sirborb.plugincloset.gui;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Small pure helpers for the menus. ponytail: one class, not one per format. */
public final class Lore {

    private Lore() {
    }

    /**
     * Advance a scroll-select index that runs over {@code -1 .. size-1}, where -1 is the
     * "All" entry, wrapping past either end. Pure so it can be asserted without a server.
     */
    public static int cycle(int index, int size, boolean back) {
        int n = size + 1;                       // the "All" slot plus one per entry
        return ((index + 1 + (back ? -1 : 1)) % n + n) % n - 1;
    }

    /** 1234 -> "1.2K", 10250865 -> "10.3M". */
    public static String downloads(long n) {
        if (n < 1_000) return Long.toString(n);
        if (n < 1_000_000) return trim(n / 1_000.0) + "K";
        if (n < 1_000_000_000) return trim(n / 1_000_000.0) + "M";
        return trim(n / 1_000_000_000.0) + "B";
    }

    private static String trim(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** 1536000 -> "1.5 MB". Jar sizes only, so KB is the smallest unit worth printing. */
    public static String bytes(long n) {
        if (n <= 0) return "";
        if (n < 1024 * 1024) return trim(n / 1024.0) + " KB";
        return trim(n / 1024.0 / 1024.0) + " MB";
    }

    /** "3 days ago". Coarse on purpose — nobody needs minutes on a plugin listing. */
    public static String relative(Instant then) {
        if (then == null || then.equals(Instant.EPOCH)) return "unknown";
        Duration d = Duration.between(then, Instant.now());
        if (d.isNegative()) return "just now";
        long days = d.toDays();
        if (days >= 365) return plural(days / 365, "year");
        if (days >= 30) return plural(days / 30, "month");
        if (days >= 1) return plural(days, "day");
        long hours = d.toHours();
        if (hours >= 1) return plural(hours, "hour");
        long mins = d.toMinutes();
        return mins >= 1 ? plural(mins, "minute") : "just now";
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s") + " ago";
    }

    /** Greedy word wrap. Words longer than the limit get their own line rather than split. */
    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) return lines;
        StringBuilder line = new StringBuilder();
        for (String word : text.replace('\n', ' ').trim().split("\\s+")) {
            if (line.isEmpty()) {
                line.append(word);
            } else if (line.length() + 1 + word.length() <= width) {
                line.append(' ').append(word);
            } else {
                lines.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /** "1.21.4, 1.21.5, ... (+12 more)" — MC version lists are often enormous. */
    public static String versions(List<String> versions, int max) {
        if (versions == null || versions.isEmpty()) return "unknown";
        if (versions.size() <= max) return String.join(", ", versions);
        return String.join(", ", versions.subList(versions.size() - max, versions.size()))
                + " (+" + (versions.size() - max) + " more)";
    }
}
