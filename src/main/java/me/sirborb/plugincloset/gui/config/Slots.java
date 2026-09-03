package me.sirborb.plugincloset.gui.config;

import java.util.ArrayList;
import java.util.List;

/** Parses {@code "45"}, {@code "0-35"}, {@code "36-44,53"} into slot numbers. */
public final class Slots {

    private Slots() {
    }

    public static int[] parse(String spec) {
        if (spec == null || spec.isBlank()) return new int[0];
        List<Integer> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int dash = p.indexOf('-', 1);
            try {
                if (dash < 0) {
                    out.add(Integer.parseInt(p));
                } else {
                    int from = Integer.parseInt(p.substring(0, dash).trim());
                    int to = Integer.parseInt(p.substring(dash + 1).trim());
                    for (int i = Math.min(from, to); i <= Math.max(from, to); i++) out.add(i);
                }
            } catch (NumberFormatException e) {
                // A typo in one range must not blank the whole menu.
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }
}
