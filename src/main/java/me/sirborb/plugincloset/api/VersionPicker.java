package me.sirborb.plugincloset.api;

import me.sirborb.plugincloset.model.PluginVersionFile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Chooses which file to install for the running MC version.
 *
 * <p>ponytail: one implementation for both sources rather than the same fallback logic
 * copied into each client.
 */
public final class VersionPicker {

    private VersionPicker() {
    }

    /** How a pick was made, so the confirmation message can be honest about it. */
    public enum Match { EXACT, SAME_MAJOR, NONE }

    public record Pick(PluginVersionFile file, Match match) {
        public boolean isEmpty() {
            return file == null;
        }
    }

    private static final Comparator<PluginVersionFile> NEWEST_FIRST =
            Comparator.comparing(PluginVersionFile::datePublished).reversed();

    /**
     * Newest file declaring exactly {@code mcVersion}; failing that, newest declaring any
     * version on the same major line (26.2 accepts a 26.1 build, which normally runs).
     *
     * <p>Externally hosted files are never picked. Their URL is populated (it points at
     * the author's own site), so a null-URL check alone would let a GitHub release *page*
     * through as if it were a jar.
     */
    public static Pick pick(List<PluginVersionFile> files, String mcVersion) {
        List<PluginVersionFile> usable = files.stream()
                .filter(f -> !f.external())
                .filter(f -> f.downloadUrl() != null && !f.downloadUrl().isBlank())
                .sorted(NEWEST_FIRST)
                .toList();

        Optional<PluginVersionFile> exact = usable.stream()
                .filter(f -> f.gameVersions().contains(mcVersion))
                .findFirst();
        if (exact.isPresent()) return new Pick(exact.get(), Match.EXACT);

        String line = majorLine(mcVersion);
        Optional<PluginVersionFile> sameLine = usable.stream()
                .filter(f -> f.gameVersions().stream().anyMatch(g -> majorLine(g).equals(line)))
                .findFirst();
        return sameLine.map(f -> new Pick(f, Match.SAME_MAJOR))
                .orElse(new Pick(null, Match.NONE));
    }

    /**
     * The "major line" of a version string: everything up to the second dot.
     * {@code 26.1.2 -> 26.1}, {@code 1.21.4 -> 1.21}, {@code 26.2 -> 26.2}.
     */
    public static String majorLine(String version) {
        if (version == null) return "";
        int first = version.indexOf('.');
        if (first < 0) return version;
        int second = version.indexOf('.', first + 1);
        return second < 0 ? version : version.substring(0, second);
    }
}
