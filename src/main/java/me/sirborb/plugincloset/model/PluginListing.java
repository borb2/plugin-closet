package me.sirborb.plugincloset.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * One search result, normalised so the GUI never has to know which source it came from.
 *
 * @param sourceId Modrinth project id/slug, or the Hangar project slug
 * @param follows  Modrinth "follows" / Hangar "stars"
 * @param iconUrl  nullable
 */
public record PluginListing(
        Source source,
        String sourceId,
        String name,
        String description,
        List<String> authors,
        long downloads,
        long follows,
        Instant datePublished,
        Instant dateUpdated,
        Set<Platform> platforms,
        String latestVersionLabel,
        List<String> supportedMcVersions,
        String iconUrl
) {
    /** Manifest key, per the spec: {@code source:sourceId}. */
    public String key() {
        return source.name() + ":" + sourceId;
    }
}
